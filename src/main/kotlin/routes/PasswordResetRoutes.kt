package com.yourcompany.zeiterfassung.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import com.yourcompany.zeiterfassung.dto.ForgotRequest
import com.yourcompany.zeiterfassung.dto.ResetRequest
import com.yourcompany.zeiterfassung.dto.VerifyRequest
import com.yourcompany.zeiterfassung.db.Users
import com.yourcompany.zeiterfassung.db.PasswordResetTokens
import com.yourcompany.zeiterfassung.service.EmailService
import com.yourcompany.zeiterfassung.service.EmailTemplates
import com.yourcompany.zeiterfassung.service.PasswordResetRateLimiter
import com.twilio.exception.ApiException
import com.twilio.rest.api.v2010.account.Message
import com.twilio.type.PhoneNumber
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import javax.mail.MessagingException

fun Route.passwordResetRoutes(twilioFrom: String, env: io.github.cdimascio.dotenv.Dotenv) {

    post("/forgot-password") {
        val req = call.receive<ForgotRequest>()
        call.application.environment.log.info("📨 Received forgot-password request for ${req.to}")

        // 🔒 Rate limiting disabled temporarily
        // val tooFrequent = !PasswordResetRateLimiter.allow(req.to)
        // if (tooFrequent) {
        //     call.application.environment.log.warn("🚫 Rate limit exceeded for ${req.to}")
        //     return@post call.respond(
        //         HttpStatusCode.TooManyRequests,
        //         mapOf("error" to "Too many requests. Try again later.")
        //     )
        // }

        val userRow = transaction {
            Users.select { (Users.email eq req.to) or (Users.phone eq req.to) }
                .singleOrNull()
        }

        if (userRow == null) {
            call.application.environment.log.warn("🔍 No user found for ${req.to}")
            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
        }

        val userId = userRow[Users.id].value
        val code = (100000..999999).random().toString()
        val channel = if (req.to.contains("@")) "email" else "sms"
        val expiresAt = LocalDateTime.now().plusMinutes(10)

        transaction {
            PasswordResetTokens.deleteWhere {
                (PasswordResetTokens.userId eq userId) and (PasswordResetTokens.channel eq channel)
            }
            PasswordResetTokens.insert {
                it[PasswordResetTokens.userId] = userId
                it[PasswordResetTokens.channel] = channel
                it[PasswordResetTokens.destination] = req.to
                it[PasswordResetTokens.code] = code
                it[PasswordResetTokens.expiresAt] = expiresAt
            }
        }

        try {
            when (channel) {
                "sms" -> {
                    val fromNumber = PhoneNumber(twilioFrom)
                    val toNumber = PhoneNumber(req.to)
                    val messageBody = "<#> Dein Code ist $code\nDeineApp"

                    // 📋 Логируем входные данные
                    call.application.environment.log.info("📲 Preparing SMS send:")
                    call.application.environment.log.info("Twilio → From=$fromNumber, To=$toNumber, Body=\"$messageBody\"")

                    try {
                        val message = Message.creator(
                            toNumber,
                            fromNumber,
                            messageBody
                        ).create()

                        // ✅ Логируем результат
                        call.application.environment.log.info("✅ SMS code sent to ${req.to}, SID=${message.sid}, Status=${message.status}")
                    } catch (e: ApiException) {
                        val reason = e.message ?: "Unknown Twilio API error"
                        call.application.environment.log.error("❌ Twilio error: $reason", e)
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "SMS delivery failed: $reason"))
                    } catch (e: Exception) {
                        val reason = e.message ?: "Unexpected error during SMS send"
                        call.application.environment.log.error("❌ Unexpected SMS failure: $reason", e)
                        return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "SMS send failed: $reason"))
                    }
                }

                "email" -> {
                    val htmlBody = EmailTemplates.buildResetPasswordHtml(code)

                    // Выводим основные SMTP-переменные (на случай багов в .env)
                    val smtpHost = System.getenv("SMTP_HOST") ?: "unknown"
                    val smtpPort = System.getenv("SMTP_PORT") ?: "unknown"
                    val smtpUser = System.getenv("SMTP_USER") ?: "unknown"

                    call.application.environment.log.info("📤 Preparing to send email:")
                    call.application.environment.log.info("SMTP → host=$smtpHost, port=$smtpPort, user=$smtpUser")

                    try {
                        EmailService.send(
                            to = req.to,
                            subject = "Passwort zurücksetzen",
                            body = htmlBody,
                            env = env
                        )
                        call.application.environment.log.info("✅ Email code sent to ${req.to}")
                    } catch (e: MessagingException) {
                        val reason = e.message ?: "Unknown MessagingException"
                        call.application.environment.log.error("❌ MessagingException while sending email: $reason", e)
                        return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Email delivery failed: $reason"))
                    } catch (e: Exception) {
                        val reason = e.message ?: "Unknown exception during email send"
                        call.application.environment.log.error("❌ Unexpected email failure: $reason", e)
                        return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Unexpected email error: $reason"))
                    }
                }

            }
        } catch (e: IllegalArgumentException) {
            call.application.environment.log.error("❌ Invalid destination format: ${req.to}", e)
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid phone number or email format"))
        } catch (e: ApiException) {
            val reason = e.message ?: "Twilio API error"
            call.application.environment.log.error("❌ Twilio error for ${req.to}: $reason", e)
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "SMS delivery failed: $reason"))
        } catch (e: MessagingException) {
            val reason = e.message ?: "Email delivery error"
            call.application.environment.log.error("❌ Email sending failed for ${req.to}: $reason", e)
            return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Email delivery failed: $reason"))
        } catch (e: Exception) {
            val reason = e.message ?: "Unknown error"
            call.application.environment.log.error("❌ Unexpected failure sending code to ${req.to}: $reason", e)
            return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to send code: $reason"))
        }

        return@post call.respond(HttpStatusCode.OK, mapOf("result" to "code_sent"))
    }

    /**
     * Verify reset code without changing password
     * POST /verify-reset-code
     * Body: { "to": "<email or phone>", "code": "<6-digit code>" }
     */
    post("/verify-reset-code") {
        val req = call.receive<VerifyRequest>()
        call.application.environment.log.info("🔍 Received verify-reset-code for ${req.to} with code ${req.code}")
        val isValid = transaction {
            PasswordResetTokens.select {
                (PasswordResetTokens.destination eq req.to) and
                (PasswordResetTokens.code eq req.code) and
                (PasswordResetTokens.expiresAt greater LocalDateTime.now())
            }.any()
        }
        if (isValid) {
            call.respond(HttpStatusCode.OK, mapOf("result" to "code_valid"))
        } else {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or expired code"))
        }
    }

    post("/reset-password") {
        val req = call.receive<ResetRequest>()
        call.application.environment.log.info("🔄 Received reset-password request for ${req.to}")

        if (req.newPassword.length < 8) {
            call.application.environment.log.warn("⚠️ Weak password submitted by ${req.to}")
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Password must be at least 8 characters"))
        }

        val userInfo = transaction {
            Users.select { (Users.email eq req.to) or (Users.phone eq req.to) }
                .singleOrNull()
                ?.let { it[Users.id].value to if (req.to.contains("@")) "email" else "sms" }
        }

        if (userInfo == null) {
            call.application.environment.log.warn("🔍 User not found during password reset for ${req.to}")
            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
        }

        val (userId, channel) = userInfo

        val validToken = transaction {
            PasswordResetTokens.select {
                (PasswordResetTokens.userId eq userId) and
                        (PasswordResetTokens.channel eq channel) and
                        (PasswordResetTokens.code eq req.code) and
                        (PasswordResetTokens.expiresAt greater LocalDateTime.now())
            }.any()
        }

        if (!validToken) {
            call.application.environment.log.warn("⚠️ Invalid or expired code for ${req.to}")
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or expired code"))
        }

        try {
            val hashedPassword = BCrypt.withDefaults().hashToString(12, req.newPassword.toCharArray())

            transaction {
                Users.update({ Users.id eq userId }) {
                    it[Users.password] = hashedPassword
                }
                PasswordResetTokens.deleteWhere {
                    (PasswordResetTokens.userId eq userId) and (PasswordResetTokens.channel eq channel)
                }
            }

            call.application.environment.log.info("🔐 Password successfully reset for ${req.to}")
            return@post call.respond(HttpStatusCode.OK, mapOf("result" to "password_changed"))

        } catch (e: Exception) {
            val reason = e.message ?: "Unknown error during password update"
            call.application.environment.log.error("❌ Failed to update password for ${req.to}: $reason", e)
            return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Password update failed: $reason"))
        }
    }
}

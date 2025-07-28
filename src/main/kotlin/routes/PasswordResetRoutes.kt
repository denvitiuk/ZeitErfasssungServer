
package com.yourcompany.zeiterfassung.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import com.yourcompany.zeiterfassung.dto.ForgotRequest
import com.yourcompany.zeiterfassung.dto.ResetRequest
import com.yourcompany.zeiterfassung.db.Users
import com.yourcompany.zeiterfassung.db.PasswordResetTokens
import com.yourcompany.zeiterfassung.service.EmailService
import com.yourcompany.zeiterfassung.service.EmailTemplates
import com.yourcompany.zeiterfassung.service.PasswordResetRateLimiter
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

fun Route.passwordResetRoutes(twilioFrom: String) {

    post("/forgot-password") {
        val req = call.receive<ForgotRequest>()
        // Rate limit: no more than 1 per minute and 5 per day per destination
        if (!PasswordResetRateLimiter.allow(req.to)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many requests"))
        }
        val userRow = transaction {
            Users.select { (Users.email eq req.to) or (Users.phone eq req.to) }
                .singleOrNull()
        }
        if (userRow == null) {
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
                "sms" -> Message.creator(
                    PhoneNumber(req.to),
                    PhoneNumber(twilioFrom),
                    "<#> Dein Code ist $code\nDeineApp"
                ).create()
                "email" -> {
                    val htmlBody = EmailTemplates.buildResetPasswordHtml(code)
                    EmailService.send(
                        to = req.to,
                        subject = "Passwort zurücksetzen",
                        body = htmlBody
                    )
                }
            }
        } catch (e: Exception) {
            // Log and report failures in external service
            call.application.environment.log.error("Failed to send reset code via $channel to ${req.to}", e)
            return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to send code"))
        }

        call.respond(HttpStatusCode.OK, mapOf("result" to "code_sent"))
    }

    post("/reset-password") {
        val req = call.receive<ResetRequest>()

        // Check password complexity
        if (req.newPassword.length < 8) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Password must be at least 8 characters"))
        }

        val userInfo = transaction {
            Users.select { (Users.email eq req.to) or (Users.phone eq req.to) }
                .singleOrNull()
                ?.let { it[Users.id].value to if (req.to.contains("@")) "email" else "sms" }
        }

        if (userInfo == null) {
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
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or expired code"))
        }

        val hashedPassword = BCrypt.withDefaults().hashToString(12, req.newPassword.toCharArray())

        transaction {
            Users.update({ Users.id eq userId }) {
                it[Users.password] = hashedPassword
            }
            PasswordResetTokens.deleteWhere {
                (PasswordResetTokens.userId eq userId) and (PasswordResetTokens.channel eq channel)
            }
        }

        call.respond(HttpStatusCode.OK, mapOf("result" to "password_changed"))
    }
}

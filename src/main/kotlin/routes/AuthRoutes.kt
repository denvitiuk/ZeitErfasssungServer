package com.yourcompany.zeiterfassung.routes

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.post
import io.ktor.http.HttpStatusCode

import com.yourcompany.zeiterfassung.dto.SendCodeDTO
import com.yourcompany.zeiterfassung.dto.VerifyCodeDTO
import com.yourcompany.zeiterfassung.dto.RegisterUnverifiedDTO
import com.yourcompany.zeiterfassung.dto.RegisterUnverifiedResponse
import com.yourcompany.zeiterfassung.dto.CompleteRegistrationDTO
import com.yourcompany.zeiterfassung.dto.LoginDTO
import com.twilio.rest.verify.v2.service.Verification
import com.twilio.rest.verify.v2.service.VerificationCheck

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction

import com.yourcompany.zeiterfassung.db.Users
import at.favre.lib.crypto.bcrypt.BCrypt
import java.time.LocalDate
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

fun Route.authRoutes(fromNumber: String, verifyServiceSid: String) {

    // Phase 1: Register unverified user
    route("/register-unverified") {
        post {
            val dto = call.receive<RegisterUnverifiedDTO>()
            val phone = dto.phone.takeIf { it.isNotBlank() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Phone is required")

            transaction {
                Users.insert {
                    it[Users.phone] = phone
                    it[Users.phoneVerified] = false
                }
            }

            call.respond(HttpStatusCode.Created, RegisterUnverifiedResponse(phone))
        }
    }

    // Phase 2: Send verification code
    route("/send-code") {
        post {
            val dto = call.receive<SendCodeDTO>()
            val resp = Verification.creator(verifyServiceSid, dto.phone, "sms").create()
            call.respond(HttpStatusCode.OK, mapOf("sent" to true))
        }
    }

    // Phase 3: Verify code and mark phoneVerified = true
    route("/verify-code") {
        post {
            val dto = call.receive<VerifyCodeDTO>()
            val check = VerificationCheck.creator(verifyServiceSid)
                .setTo(dto.phone)
                .setCode(dto.code)
                .create()

            if (check.status == "approved") {
                transaction {
                    Users.update({ Users.phone eq dto.phone }) {
                        it[Users.phoneVerified] = true
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("verified" to true))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("verified" to false))
            }
        }
    }

    // Phase 4: Complete registration with full profile
    route("/complete-registration") {
        post {
            val dto = call.receive<CompleteRegistrationDTO>()

            // Check phone is verified
            val isVerified = transaction {
                Users.select { Users.phone eq dto.phone }
                    .map { it[Users.phoneVerified] }
                    .singleOrNull() ?: false
            }

            if (!isVerified) {
                return@post call.respond(HttpStatusCode.BadRequest, "Phone not verified")
            }

            // Validate birthDate
            val birthDate = try {
                LocalDate.parse(dto.birthDate)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid birthDate format, expected YYYY-MM-DD")
            }

            // Hash password
            val passwordHash = BCrypt.withDefaults()
                .hashToString(12, dto.password.toCharArray())

            // Update user record
            transaction {
                Users.update({ Users.phone eq dto.phone }) {
                    it[Users.firstName] = dto.firstName
                    it[Users.lastName] = dto.lastName
                    it[Users.email] = dto.email
                    it[Users.password] = passwordHash
                    it[Users.birthDate] = birthDate
                }
            }

            call.respond(HttpStatusCode.OK, mapOf("completed" to true))
        }
    }

    // Login route
    route("/login") {
        post {
            val dto = call.receive<LoginDTO>()
                // Server-seitige Validierung: Pflichtfelder dürfen nicht leer sein
                if (dto.email.isBlank() || dto.password.isBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "E-Mail und Passwort dürfen nicht leer sein")
                    )
                }
            val user = transaction {
                Users.select { Users.email eq dto.email }.firstOrNull()
            } ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "Ungültige E-Mail oder Passwort")
            )

            val result = BCrypt.verifyer().verify(dto.password.toCharArray(), user[Users.password])
            if (!result.verified) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Ungültige E-Mail oder Passwort")
                )
            }

            val jwtConfig = call.application.environment.config.config("ktor.jwt")
            val issuer = jwtConfig.property("issuer").getString()
            val audience = jwtConfig.property("audience").getString()
            val secret = jwtConfig.property("secret").getString()
            val expiresIn = jwtConfig.property("validityMs").getString().toLong()

            val token = JWT.create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withClaim("id", user[Users.id].toString())
                .withExpiresAt(Date(System.currentTimeMillis() + expiresIn))
                .sign(Algorithm.HMAC256(secret))

            call.respond(HttpStatusCode.OK, mapOf("token" to token))
        }
    }
}

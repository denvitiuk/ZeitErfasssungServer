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
import com.yourcompany.zeiterfassung.dto.RegisterDTO
import com.yourcompany.zeiterfassung.dto.LoginDTO
import com.twilio.rest.api.v2010.account.Message
import com.twilio.type.PhoneNumber
import com.twilio.rest.verify.v2.service.Verification
import com.twilio.rest.verify.v2.service.VerificationCheck

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction

import com.yourcompany.zeiterfassung.db.Users
import at.favre.lib.crypto.bcrypt.BCrypt
import java.time.LocalDate
import org.jetbrains.exposed.exceptions.ExposedSQLException

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date



/**
 * Handles user registration, phone verification, and login routes.
 */
fun Route.authRoutes(fromNumber: String, verifyServiceSid: String) {
    // 1) Send verification code
    route("/send-code") {
        post {
            val dto = call.receive<SendCodeDTO>()

            // Use Twilio Verify service to send SMS code
            Verification.creator(
                verifyServiceSid,
                dto.phone,
                "sms"
            ).create()

            call.respond(HttpStatusCode.OK, mapOf("sent" to true))
        }
    }

    // 2) Verify code via Twilio Verify API
    route("/verify-code") {
        post {
            val dto = call.receive<VerifyCodeDTO>()

            // Call Twilio Verify to check the code
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

    // 3) User registration
    route("/register") {
        post {
            val dto = call.receive<RegisterDTO>()

            // 3.1: Phone must be present
            val phone = dto.phone?.takeIf { it.isNotBlank() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Phone number is required")

            // 3.2: Phone must be verified
            val phoneVerified = transaction {
                Users.slice(Users.phoneVerified)
                    .select { Users.phone eq phone }
                    .firstOrNull()?.get(Users.phoneVerified) ?: false
            }
            if (!phoneVerified) {
                return@post call.respond(HttpStatusCode.Forbidden, "Phone not verified")
            }

            // 3.3: Email must be unique
            val emailTaken = transaction {
                Users.select { Users.email eq dto.email }.any()
            }
            if (emailTaken) {
                return@post call.respond(HttpStatusCode.Conflict, "Email already registered")
            }

            // 3.4: Parse and validate birthDate
            val birthDate = try {
                LocalDate.parse(dto.birthDate)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid birthDate format, expected YYYY-MM-DD")
            }

            // 3.5: Hash password
            val passwordHash = BCrypt.withDefaults()
                .hashToString(12, dto.password.toCharArray())

            // 3.6: Insert user
            val userId = try {
                transaction {
                    Users.insertAndGetId { stmt ->
                        stmt[Users.firstName]     = dto.firstName
                        stmt[Users.lastName]      = dto.lastName
                        stmt[Users.birthDate]     = birthDate
                        stmt[Users.email]         = dto.email
                        stmt[Users.password]      = passwordHash
                        stmt[Users.phone]         = phone
                        stmt[Users.phoneVerified] = true
                    }.value
                }
            } catch (ex: ExposedSQLException) {
                return@post call.respond(HttpStatusCode.Conflict, "Registration failed: ${ex.localizedMessage}")
            }

            call.respond(
                HttpStatusCode.Created,
                mapOf("id" to userId, "email" to dto.email)
            )
        }
    }

    // 4) Login (stub)
    route("/login") {
        post {
            val dto = call.receive<LoginDTO>()
            // 1) Найти пользователя по email
            val userRow = transaction {
                Users.select { Users.email eq dto.email }
                    .firstOrNull()
            }
            if (userRow == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid email or password")
                return@post
            }
            // 2) Проверить пароль
            val storedHash = userRow[Users.password]
            val result = BCrypt.verifyer().verify(dto.password.toCharArray(), storedHash)
            if (!result.verified) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid email or password")
                return@post
            }
            // 3) Сгенерировать JWT
            val jwtConfig = call.application.environment.config.config("ktor.jwt")
            val issuer     = jwtConfig.property("issuer").getString()
            val audience   = jwtConfig.property("audience").getString()
            val secret     = jwtConfig.property("secret").getString()
            val expiresIn  = jwtConfig.property("validityMs").getString().toLong()

            val token = JWT.create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withClaim("id", userRow[Users.id].toString())
                .withExpiresAt(Date(System.currentTimeMillis() + expiresIn))
                .sign(Algorithm.HMAC256(secret))

            call.respond(HttpStatusCode.OK, mapOf("token" to token))
        }
    }
}

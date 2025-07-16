package com.yourcompany.zeiterfassung.routes

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
import com.yourcompany.zeiterfassung.dto.ChangePasswordRequest
import com.yourcompany.zeiterfassung.dto.ChangePasswordResponse
import com.twilio.rest.verify.v2.service.Verification
import com.twilio.rest.verify.v2.service.VerificationCheck

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
import java.time.format.DateTimeFormatter
import java.util.Locale

// JWT authentication and JSON serialization
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart

import io.ktor.http.content.streamProvider
import io.ktor.server.request.receiveMultipart
import org.jetbrains.exposed.sql.Column
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class ProfileResponse(
    val first_name: String,
    val last_name: String,
    val email: String,
    val phone: String,
    val employee_number: String,
    // дополнительные поля
    val role: String? = null,
    val avatar_url: String? = null,
    val company: String? = null,
    val created_at: String

)

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

            // Generate JWT for newly registered user
            val jwtConfig = call.application.environment.config.config("ktor.jwt")
            val issuer = jwtConfig.property("issuer").getString()
            val audience = jwtConfig.property("audience").getString()
            val secret = jwtConfig.property("secret").getString()
            val expiresIn = jwtConfig.property("validityMs").getString().toLong()

            // Fetch user ID by phone
            val userId = transaction {
                Users.select { Users.phone eq dto.phone }
                    .map { it[Users.id].toString() }
                    .single()
            }

            val token = JWT.create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withClaim("id", userId)
                .withExpiresAt(Date(System.currentTimeMillis() + expiresIn))
                .sign(Algorithm.HMAC256(secret))

            call.respond(
                HttpStatusCode.OK,
                mapOf("completed" to true, "token" to token)
            )
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

    // Protected profile endpoint
    authenticate("bearerAuth") {
        route("/profile") {
            get {
                // Extract user ID from JWT token
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("id").asString().toInt()

                // Query user data
                val row = transaction {
                    Users.select { Users.id eq userId }.single()
                }

                // Format registration date in German month-year
                val createdAt = row[Users.createdAt]
                    .format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.GERMAN))

                // Build and send response
                val rawId = row[Users.id].value.toString()
                val employeeNumber = if (rawId.length > 12) rawId.takeLast(12) else rawId
                val profile = ProfileResponse(
                    first_name = row[Users.firstName],
                    last_name = row[Users.lastName],
                    email = row[Users.email],
                    phone = row[Users.phone] ?: "",
                    employee_number = employeeNumber,
                    avatar_url = row.getOrNull(Users.avatarUrl),
                    created_at = createdAt
                )
                call.respond(HttpStatusCode.OK, profile)
            }
        }

        // Upload user avatar
        route("/profile/avatar") {
            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("id").asString().toInt()

                // Ensure upload directory exists
                val uploadDir = File("uploads/avatars").apply { if (!exists()) mkdirs() }

                var savedFilename: String? = null
                call.receiveMultipart().forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val ext = File(part.originalFileName ?: "").extension
                        val filename = "${UUID.randomUUID()}${if (ext.isNotBlank()) ".$ext" else ""}"
                        val file = File(uploadDir, filename)
                        part.streamProvider().use { input -> file.outputStream().buffered().use { input.copyTo(it) } }
                        savedFilename = filename
                    }
                    part.dispose()
                }
                if (savedFilename == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file provided"))
                }
                val avatarUrl = "https://your.cdn.host/avatars/$savedFilename"
                transaction {
                    Users.update({ Users.id eq userId }) {
                        it[Users.avatarUrl] = avatarUrl
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("avatar_url" to avatarUrl))
            }
        }

        // Change password
        route("/change-password") {
            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("id").asString().toInt()
                val req = call.receive<ChangePasswordRequest>()

                // Perform password change inside a DB transaction and return a status code
                val response = transaction {
                    // Fetch the user
                    val row = Users.select { Users.id eq userId }.singleOrNull()
                        ?: return@transaction ChangePasswordResponse("user_not_found") to HttpStatusCode.NotFound

                    // Verify current password
                    val result = BCrypt.verifyer()
                        .verify(req.currentPassword.toCharArray(), row[Users.password].toCharArray())
                    if (!result.verified) {
                        return@transaction ChangePasswordResponse("invalid_current_password") to HttpStatusCode.Unauthorized
                    }

                    // Hash and update new password
                    val newHash = BCrypt.withDefaults()
                        .hashToString(12, req.newPassword.toCharArray())
                    Users.update({ Users.id eq userId }) {
                        it[Users.password] = newHash
                    }

                    // On success
                    ChangePasswordResponse("ok") to HttpStatusCode.OK
                }

                // Respond using the captured Pair
                call.respond(response.second, response.first)
            }
        }
    }
}

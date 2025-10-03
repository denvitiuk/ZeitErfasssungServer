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
import com.yourcompany.zeiterfassung.db.Users.phone
import com.yourcompany.zeiterfassung.db.Users.phoneVerified
import com.yourcompany.zeiterfassung.db.Companies
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
import io.ktor.server.request.receiveText
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.and
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import kotlin.collections.mapOf
import kotlin.collections.singleOrNull

@Serializable
data class SentCodeResponse(val sent: Boolean)

@Serializable
data class VerifyCodeResponse(val verified: Boolean)

@Serializable
data class CompleteRegistrationResponse(val completed: Boolean, val token: String)

@Serializable
data class ErrorResponse(val error: String)

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

@Serializable
data class LinkCompanyRequest(val code: String)

@Serializable
data class LinkCompanyResponse(
    val token: String,
    val companyId: Int,
    val companyName: String,
    val isCompanyAdmin: Boolean,
    val alreadyLinked: Boolean = false
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
            val phone = dto.phone
            if (phone == "+491234567890") {
                // Test stub: skip Twilio SMS
                call.respond(HttpStatusCode.OK, SentCodeResponse(sent = true))
                return@post
            }
            val resp = Verification.creator(verifyServiceSid, phone, "sms").create()
            call.respond(HttpStatusCode.OK, SentCodeResponse(sent = resp.status == "pending"))
        }
    }

    // Phase 3: Verify code and mark phoneVerified = true
    route("/verify-code") {
        post {
            val dto = call.receive<VerifyCodeDTO>()
            val phone = dto.phone
            val code = dto.code
            if (phone == "+491234567890" && code == "000000") {
                // Test stub: accept magic code without Twilio
                transaction {
                    Users.update({ Users.phone eq phone }) {
                        it[Users.phoneVerified] = true
                    }
                }
                call.respond(HttpStatusCode.OK, VerifyCodeResponse(verified = true))
                return@post
            }
            val check = VerificationCheck.creator(verifyServiceSid)
                .setTo(phone)
                .setCode(code)
                .create()

            if (check.status == "approved") {
                transaction {
                    Users.update({ Users.phone eq phone }) {
                        it[Users.phoneVerified] = true
                    }
                }
                call.respond(HttpStatusCode.OK, VerifyCodeResponse(verified = true))
            } else {
                call.respond(HttpStatusCode.BadRequest, VerifyCodeResponse(verified = false))
            }
        }
    }

    // Possible errors for complete-registration:
    //   400 Phone not verified
    //   400 Invalid birthDate format, expected YYYY-MM-DD
    //   400 Email already registered
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
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Phone not verified"))
            }

            // Validate birthDate
            val birthDate = try {
                LocalDate.parse(dto.birthDate)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid birthDate format, expected YYYY-MM-DD"))
            }

            // Hash password
            val passwordHash = BCrypt.withDefaults()
                .hashToString(12, dto.password.toCharArray())

            // Ensure email is not already taken by another user
            val emailTaken = transaction {
                Users.select { Users.email eq dto.email }
                    .map { it[Users.phone] }
                    .firstOrNull()
                    ?.let { existingPhone -> existingPhone != dto.phone }
                    ?: false
            }
            if (emailTaken) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Email already registered"))
            }

            val (companyId, isAdmin) = dto.inviteCode
                ?.takeIf { it.isNotBlank() }
                ?.let { code ->
                    transaction {
                        Companies.select { Companies.inviteCode eq code }
                            .map { it[Companies.id] }
                            .singleOrNull()
                    }
                }
                ?.let { foundId -> Pair(foundId, false) } // company invite_code should not grant admin
                ?: Pair(null, false)

            // Update user record
            transaction {
                Users.update({ Users.phone eq dto.phone }) {
                    it[Users.firstName]      = dto.firstName
                    it[Users.lastName]       = dto.lastName
                    it[Users.email]          = dto.email
                    it[Users.password]       = passwordHash
                    it[Users.birthDate]      = birthDate
                    it[Users.companyId]      = companyId
                    it[Users.isCompanyAdmin] = isAdmin
                    it[Users.isGlobalAdmin]  = false
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
                    .map { it[Users.id].value.toString() }
                    .single()
            }

            val token = JWT.create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withClaim("id", userId)
                .withClaim("companyId", companyId?.value ?: 0)
                .withClaim("isCompanyAdmin", isAdmin)
                .withClaim("isGlobalAdmin", false)
                .withExpiresAt(Date(System.currentTimeMillis() + expiresIn))
                .sign(Algorithm.HMAC256(secret))

            call.respond(
                HttpStatusCode.OK,
                CompleteRegistrationResponse(completed = true, token = token)
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

            val hashed = user[Users.password] ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "Ungültige E-Mail oder Passwort")
            )
            val result = BCrypt.verifyer().verify(dto.password.toCharArray(), hashed)
            if (!result.verified) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Ungültige E-Mail oder Passwort")
                )
            }

            // Extract companyId, isCompanyAdmin, isGlobalAdmin from user row
            val companyId = user[Users.companyId]?.value ?: 0
            val isCompanyAdmin = user[Users.isCompanyAdmin]
            val isGlobalAdmin = user[Users.isGlobalAdmin]

            val jwtConfig = call.application.environment.config.config("ktor.jwt")
            val issuer = jwtConfig.property("issuer").getString()
            val audience = jwtConfig.property("audience").getString()
            val secret = jwtConfig.property("secret").getString()
            val expiresIn = jwtConfig.property("validityMs").getString().toLong()

            val token = JWT.create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withClaim("id", user[Users.id].value.toString())
                .withClaim("companyId", companyId)
                .withClaim("isCompanyAdmin", isCompanyAdmin)
                .withClaim("isGlobalAdmin", isGlobalAdmin)
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

                // Fetch company name (unwrap nullable companyId and query by Int)
                val companyName = transaction {
                    row[Users.companyId]?.let { companyId ->
                        Companies.select { Companies.id eq companyId }
                            .map { it[Companies.name] }
                            .singleOrNull()
                    }
                }

                // Determine role string
                val role = when {
                    row[Users.isGlobalAdmin] -> "globalAdmin"
                    row[Users.isCompanyAdmin] -> "companyAdmin"
                    else -> "user"
                }

                // Format registration date in German month-year
                val createdAt = row[Users.createdAt]
                    .format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.GERMAN))

                // Build and send response
                val employeeNumber = row[Users.employeeNumber].toString()
                val profile = ProfileResponse(
                    first_name = row[Users.firstName] ?: "",
                    last_name  = row[Users.lastName]  ?: "",
                    email      = row[Users.email]     ?: "",
                    phone = row[Users.phone] ?: "",
                    employee_number = employeeNumber,
                    avatar_url = row.getOrNull(Users.avatarUrl),
                    created_at = createdAt,
                    role = role,
                    company = companyName
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
                // 1) Read and log raw JSON body
                val bodyText = call.receiveText()
                println("🔄 [ChangePasswordRoute] Received raw body: $bodyText")
                // Deserialize
                val req = kotlinx.serialization.json.Json
                    .decodeFromString<ChangePasswordRequest>(bodyText)

                // 2) Authenticate and log userId
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("id").asString().toInt()
                println("🔑 [ChangePasswordRoute] Authenticated userId: $userId")

                // 3) Perform transaction with detailed logs
                val response = transaction {
                    val row = Users.select { Users.id eq userId }.singleOrNull()
                    if (row == null) {
                        println("⚠️ [ChangePasswordRoute] user_not_found for id $userId")
                        return@transaction ChangePasswordResponse("user_not_found") to HttpStatusCode.NotFound
                    }

                    val hashed = row[Users.password] ?: return@transaction ChangePasswordResponse("user_not_found") to HttpStatusCode.NotFound
                    val result = BCrypt.verifyer()
                        .verify(req.currentPassword.toCharArray(), hashed.toCharArray())
                    if (!result.verified) {
                        println("⚠️ [ChangePasswordRoute] invalid_current_password for id $userId")
                        return@transaction ChangePasswordResponse("invalid_current_password") to HttpStatusCode.Unauthorized
                    }

                    val newHash = BCrypt.withDefaults()
                        .hashToString(12, req.newPassword.toCharArray())
                    Users.update({ Users.id eq userId }) {
                        it[Users.password] = newHash
                    }
                    println("✅ [ChangePasswordRoute] Password updated successfully for id $userId")
                    ChangePasswordResponse("ok") to HttpStatusCode.OK
                }

                // 4) Log and respond
                println("🔄 [ChangePasswordRoute] Responding with status ${response.second} and body ${response.first}")
                call.respond(response.second, response.first)
            }
        }

        // Link current user to a company by invite code and return updated JWT
        route("/link-company") {
            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("id").asString().toInt()

                val req = call.receive<LinkCompanyRequest>()
                val code = req.code.trim()
                if (code.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("code_required"))
                }

                // 1) Find company by companies.invite_code
                val companyId: EntityID<Int> = transaction {
                    Companies
                        .select { Companies.inviteCode eq code }
                        .map { it[Companies.id] }
                        .singleOrNull()
                } ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_code"))

                // Load company name and check current linkage
                val companyName: String = transaction {
                    Companies
                        .select { Companies.id eq companyId }
                        .map { it[Companies.name] }
                        .single()
                }
                val currentCompanyId: EntityID<Int>? = transaction {
                    Users
                        .select { Users.id eq userId }
                        .map { it[Users.companyId] }
                        .single()
                }
                // If already linked to this company, short-circuit and just issue a fresh token
                if (currentCompanyId != null && currentCompanyId == companyId) {
                    val jwtConfig = call.application.environment.config.config("ktor.jwt")
                    val issuer = jwtConfig.property("issuer").getString()
                    val audience = jwtConfig.property("audience").getString()
                    val secret = jwtConfig.property("secret").getString()
                    val expiresIn = jwtConfig.property("validityMs").getString().toLong()

                    val row = transaction { Users.select { Users.id eq userId }.single() }
                    val tokenSame = JWT.create()
                        .withIssuer(issuer)
                        .withAudience(audience)
                        .withClaim("id", row[Users.id].value.toString())
                        .withClaim("companyId", row[Users.companyId]?.value ?: 0)
                        .withClaim("isCompanyAdmin", row[Users.isCompanyAdmin])
                        .withClaim("isGlobalAdmin", row[Users.isGlobalAdmin])
                        .withExpiresAt(Date(System.currentTimeMillis() + expiresIn))
                        .sign(Algorithm.HMAC256(secret))

                    call.application.environment.log.info("link-company: user=$userId already linked to company=${companyId.value}, returning fresh token")
                    return@post call.respond(
                        HttpStatusCode.OK,
                        LinkCompanyResponse(
                            token = tokenSame,
                            companyId = companyId.value,
                            companyName = companyName,
                            isCompanyAdmin = row[Users.isCompanyAdmin],
                            alreadyLinked = true
                        )
                    )
                }
                // If linked to a different company, forbid switching here (safety)
                if (currentCompanyId != null && currentCompanyId != companyId) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("already_linked_to_another_company")
                    )
                }

                // 2) Attach user to company, make first user admin
                transaction {
                    // attach
                    Users.update({ Users.id eq userId }) {
                        it[Users.companyId] = companyId
                    }
                    // first in company becomes admin
                    val hasAdmin = Users
                        .select { (Users.companyId eq companyId) and (Users.isCompanyAdmin eq true) }
                        .any()
                    if (!hasAdmin) {
                        Users.update({ Users.id eq userId }) {
                            it[Users.isCompanyAdmin] = true
                        }
                    }
                    call.application.environment.log.info("link-company: user=$userId -> company=${companyId.value}, first_admin=${!hasAdmin}")
                }

                // 3) Issue updated JWT with fresh claims
                val jwtConfig = call.application.environment.config.config("ktor.jwt")
                val issuer = jwtConfig.property("issuer").getString()
                val audience = jwtConfig.property("audience").getString()
                val secret = jwtConfig.property("secret").getString()
                val expiresIn = jwtConfig.property("validityMs").getString().toLong()

                val row = transaction { Users.select { Users.id eq userId }.single() }
                val newToken = JWT.create()
                    .withIssuer(issuer)
                    .withAudience(audience)
                    .withClaim("id", row[Users.id].value.toString())
                    .withClaim("companyId", row[Users.companyId]?.value ?: 0)
                    .withClaim("isCompanyAdmin", row[Users.isCompanyAdmin])
                    .withClaim("isGlobalAdmin", row[Users.isGlobalAdmin])
                    .withExpiresAt(Date(System.currentTimeMillis() + expiresIn))
                    .sign(Algorithm.HMAC256(secret))

                val isAdminNow = row[Users.isCompanyAdmin]
                call.respond(
                    HttpStatusCode.OK,
                    LinkCompanyResponse(
                        token = newToken,
                        companyId = companyId.value,
                        companyName = companyName,
                        isCompanyAdmin = isAdminNow,
                        alreadyLinked = false
                    )
                )
            }
        }
    }
}

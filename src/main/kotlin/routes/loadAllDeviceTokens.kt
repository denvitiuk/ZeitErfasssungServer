package com.yourcompany.zeiterfassung.routes



import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.yourcompany.zeiterfassung.models.DeviceTokens
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import io.ktor.http.HttpStatusCode


@Serializable
data class RegisterDeviceTokenRequest(
    val platform: String,  // "ios" or "android"
    val token: String,
    val locale: String? = null
)

private fun normalizeDeviceLocale(raw: String?): String {
    val language = raw
        ?.trim()
        ?.replace('_', '-')
        ?.substringBefore('-')
        ?.lowercase()
        ?: "de"

    return when (language) {
        "de", "en", "ru", "bg", "tr", "uk" -> language
        else -> "de"
    }
}


fun Route.deviceTokenRoutes() {
    authenticate("bearerAuth") {
        route("/api/device-tokens") {

            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("id").asString().toInt()
                val req = call.receive<RegisterDeviceTokenRequest>()
                val platform = req.platform.trim().lowercase()
                val token = req.token.trim()
                val locale = normalizeDeviceLocale(req.locale)

                if (platform !in listOf("ios", "android")) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Unsupported platform: ${req.platform}")
                    )
                    return@post
                }

                if (token.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Device token is empty")
                    )
                    return@post
                }

                val now = Instant.now()




                transaction {
                    val updated = DeviceTokens.update({
                        (DeviceTokens.userId eq userId) and
                                (DeviceTokens.platform eq platform)
                    }) {
                        it[DeviceTokens.token] = token
                        it[DeviceTokens.locale] = locale
                        it[DeviceTokens.createdAt] = now
                    }

                    if (updated == 0) {
                        DeviceTokens.insert {
                            it[DeviceTokens.userId] = userId
                            it[DeviceTokens.platform] = platform
                            it[DeviceTokens.token] = token
                            it[DeviceTokens.locale] = locale
                            it[DeviceTokens.createdAt] = now
                        }
                    }
                }

                call.application.environment.log.info("📲 Device token saved userId=$userId platform=$platform locale=$locale tokenSuffix=${token.takeLast(8)}")
                call.respond(mapOf("status" to "ok"))
            }

            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("id").asString().toInt()

                val tokens = transaction {
                    DeviceTokens.select { DeviceTokens.userId eq userId }
                        .map {
                            mapOf(
                                "platform" to it[DeviceTokens.platform],
                                "locale" to it[DeviceTokens.locale],
                                "tokenSuffix" to it[DeviceTokens.token].takeLast(8),
                                "createdAt" to it[DeviceTokens.createdAt].toString()
                            )
                        }
                }

                call.respond(tokens)
            }
        }
    }
}

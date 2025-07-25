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
import java.time.LocalDateTime
import java.time.ZoneId
import org.jetbrains.exposed.sql.javatime.timestamp


@Serializable
data class RegisterDeviceTokenRequest(
    val platform: String,  // "ios" or "android"
    val token: String
)

fun Route.deviceTokenRoutes() {
    authenticate("bearerAuth") {
        route("/api/device-tokens") {
            /**
             * POST /api/device-tokens
             * Body: { "platform": "ios", "token": "<device-token>" }
             * Authentication: Bearer JWT
             * Action: Upsert device token for this user and platform
             */
            post {
                // Extract userId from JWT
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("id").asString().toInt()

                // Parse request body
                val req = call.receive<RegisterDeviceTokenRequest>()

                // Upsert token in database
                transaction {
                    // Delete old token for this user+platform
                    DeviceTokens.deleteWhere {
                        (DeviceTokens.userId eq userId) and
                                (DeviceTokens.platform eq req.platform)
                    }
                    DeviceTokens.insert {
                        it[DeviceTokens.userId] = userId // Int
                        it[DeviceTokens.platform] = req.platform // String
                        it[DeviceTokens.token] = req.token // String
                        it[DeviceTokens.createdAt] = Instant.now()

                    }

                }

                call.respond(mapOf("status" to "ok"))
            }

            /**
             * GET /api/device-tokens
             * Returns list of tokens for current user (optional)
             */
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("id").asString().toInt()
                val tokens = transaction {
                    DeviceTokens.select { DeviceTokens.userId eq userId }
                        .map {
                            mapOf(
                                "platform" to it[DeviceTokens.platform],
                                "token" to it[DeviceTokens.token]
                            )
                        }
                }
                call.respond(tokens)
            }
        }
    }
}
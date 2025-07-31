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
import kotlin.and


@Serializable
data class RegisterDeviceTokenRequest(
    val platform: String,  // "ios" or "android"
    val token: String
)


fun Route.deviceTokenRoutes() {
    authenticate("bearerAuth") {
        route("/api/device-tokens") {

            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("id").asString().toInt()
                val req = call.receive<RegisterDeviceTokenRequest>()

                if (req.platform !in listOf("ios", "android")) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Unsupported platform: ${req.platform}")
                    )
                    return@post
                }

                val now = Instant.now()




                transaction {
                    val updated = DeviceTokens.update({
                        (DeviceTokens.userId eq userId) and
                                (DeviceTokens.platform eq req.platform)
                    }) {
                        it[DeviceTokens.token] = req.token
                        it[DeviceTokens.createdAt] = now

                    }

                    if (updated == 0) {
                        DeviceTokens.insert {
                            it[DeviceTokens.userId] = userId
                            it[DeviceTokens.platform] = req.platform
                            it[DeviceTokens.token] = req.token
                            it[DeviceTokens.createdAt] = now

                        }
                    }
                }

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
                                "token" to it[DeviceTokens.token]
                            )
                        }
                }

                call.respond(tokens)
            }
        }
    }
}



// src/main/kotlin/com/yourcompany/zeiterfassung/routes/PauseRoutes.kt
package com.yourcompany.zeiterfassung.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.yourcompany.zeiterfassung.models.PauseSessions
import com.yourcompany.zeiterfassung.dto.PauseResponse
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.LocalTime

fun Route.pauseRoutes() {
    authenticate("bearerAuth") {
        route("/api/pause") {
            // POST /api/pause/start
            post("/start") {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("id").asString().toInt()
                val now = LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault())

                // Allow pause only between 12:00 and 13:00
                val nowTime = now.toLocalTime()
                val lunchStart = LocalTime.of(12, 0)
                val lunchEnd = LocalTime.of(13, 0)
                if (nowTime.isBefore(lunchStart) || nowTime.isAfter(lunchEnd)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "Pause only allowed between 12:00 and 13:00")
                    )
                    return@post
                }

                val sessionId = transaction {
                    PauseSessions.insertAndGetId { row ->
                        row[PauseSessions.userId] = userId
                        row[PauseSessions.startedAt] = now
                        row[PauseSessions.isActive] = true
                    }.value
                }

                call.respond(
                    PauseResponse(
                        status = "started",
                        sessionId = sessionId,
                        timestamp = Instant.now().toString()
                    )
                )
            }

            // POST /api/pause/stop
            post("/stop") {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("id").asString().toInt()
                val now = LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault())

                val updated = transaction {
                    PauseSessions.update({
                        (PauseSessions.userId eq userId) and (PauseSessions.isActive eq true)
                    }) { row ->
                        row[PauseSessions.endedAt] = now
                        row[PauseSessions.isActive] = false
                    }
                }

                if (updated > 0) {
                    call.respond(
                        PauseResponse(
                            status = "stopped",
                            sessionId = null,
                            timestamp = Instant.now().toString()
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "no_active_pause")
                    )
                }
            }
        }
    }
}
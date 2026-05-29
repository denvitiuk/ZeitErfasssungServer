// src/main/kotlin/com/yourcompany/zeiterfassung/routes/PauseRoutes.kt
package com.yourcompany.zeiterfassung.routes

import com.yourcompany.zeiterfassung.db.Users
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
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val DEFAULT_BREAK_START_TIME = "12:00"
private const val DEFAULT_BREAK_END_TIME = "13:00"
private const val DEFAULT_BREAK_DURATION_MINUTES = 60
private const val DEFAULT_BREAK_TIMEZONE = "Europe/Berlin"

private data class CompanyPauseSettings(
    val startTime: LocalTime = LocalTime.parse(DEFAULT_BREAK_START_TIME),
    val endTime: LocalTime = LocalTime.parse(DEFAULT_BREAK_END_TIME),
    val durationMinutes: Int = DEFAULT_BREAK_DURATION_MINUTES,
    val timezone: String = DEFAULT_BREAK_TIMEZONE
)

fun Route.pauseRoutes() {
    authenticate("bearerAuth") {
        route("/api/pause") {
            // POST /api/pause/start
            post("/start") {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("id").asString().toInt()
                val nowInstant = Instant.now()
                val nowUtc = LocalDateTime.ofInstant(nowInstant, ZoneOffset.UTC)

                val pauseSettings = loadCompanyPauseSettingsForUser(userId)
                val companyZoneId = parseZoneIdOrDefault(pauseSettings.timezone)
                val nowCompanyLocal = LocalDateTime.ofInstant(nowInstant, companyZoneId)

                // Allow pause only inside the company-configured pause window.
                // Defaults to 12:00-13:00 while the admin pause settings table is not configured yet.
                val nowTime = nowCompanyLocal.toLocalTime()
                if (nowTime.isBefore(pauseSettings.startTime) || nowTime.isAfter(pauseSettings.endTime)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf(
                            "error" to "pause_not_allowed_now",
                            "breakStartTime" to pauseSettings.startTime.toString(),
                            "breakEndTime" to pauseSettings.endTime.toString(),
                            "breakDurationMinutes" to pauseSettings.durationMinutes.toString(),
                            "timezone" to pauseSettings.timezone
                        )
                    )
                    return@post
                }

                val sessionId = transaction {
                    PauseSessions.insertAndGetId { row ->
                        row[PauseSessions.userId] = userId
                        row[PauseSessions.startedAt] = nowUtc
                        row[PauseSessions.isActive] = true
                    }.value
                }

                call.respond(
                    PauseResponse(
                        status = "started",
                        sessionId = sessionId,
                        timestamp = nowInstant.toString()
                    )
                )
            }

            // POST /api/pause/stop
            post("/stop") {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("id").asString().toInt()
                val nowInstant = Instant.now()
                val nowUtc = LocalDateTime.ofInstant(nowInstant, ZoneOffset.UTC)

                val updated = transaction {
                    PauseSessions.update({
                        (PauseSessions.userId eq userId) and (PauseSessions.isActive eq true)
                    }) { row ->
                        row[PauseSessions.endedAt] = nowUtc
                        row[PauseSessions.isActive] = false
                    }
                }

                if (updated > 0) {
                    call.respond(
                        PauseResponse(
                            status = "stopped",
                            sessionId = null,
                            timestamp = nowInstant.toString()
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

private fun loadCompanyPauseSettingsForUser(userId: Int): CompanyPauseSettings {
    return transaction {
        val companyId = Users
            .select { Users.id eq userId }
            .limit(1)
            .mapNotNull { row -> row[Users.companyId]?.value }
            .firstOrNull()

        if (companyId == null) {
            return@transaction CompanyPauseSettings()
        }

        exec(
            """
            SELECT
                COALESCE(pause_start_time, '$DEFAULT_BREAK_START_TIME') AS pause_start_time,
                COALESCE(pause_end_time, '$DEFAULT_BREAK_END_TIME') AS pause_end_time,
                COALESCE(pause_duration_minutes, $DEFAULT_BREAK_DURATION_MINUTES) AS pause_duration_minutes,
                COALESCE(timezone, '$DEFAULT_BREAK_TIMEZONE') AS timezone
            FROM company_pause_settings
            WHERE company_id = $companyId
            LIMIT 1
            """.trimIndent()
        ) { rs ->
            if (rs.next()) {
                CompanyPauseSettings(
                    startTime = parseBreakTimeOrDefault(
                        value = rs.getString("pause_start_time"),
                        fallback = DEFAULT_BREAK_START_TIME
                    ),
                    endTime = parseBreakTimeOrDefault(
                        value = rs.getString("pause_end_time"),
                        fallback = DEFAULT_BREAK_END_TIME
                    ),
                    durationMinutes = rs.getInt("pause_duration_minutes").takeIf { !rs.wasNull() }
                        ?: DEFAULT_BREAK_DURATION_MINUTES,
                    timezone = rs.getString("timezone") ?: DEFAULT_BREAK_TIMEZONE
                )
            } else {
                CompanyPauseSettings()
            }
        } ?: CompanyPauseSettings()
    }
}

private fun parseBreakTimeOrDefault(value: String?, fallback: String): LocalTime {
    return runCatching { LocalTime.parse(value ?: fallback) }
        .getOrElse { LocalTime.parse(fallback) }
}

private fun parseZoneIdOrDefault(value: String): ZoneId {
    return runCatching { ZoneId.of(value) }
        .getOrElse { ZoneId.of(DEFAULT_BREAK_TIMEZONE) }
}

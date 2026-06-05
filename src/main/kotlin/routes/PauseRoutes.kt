// src/main/kotlin/com/yourcompany/zeiterfassung/routes/PauseRoutes.kt
package com.yourcompany.zeiterfassung.routes

import com.yourcompany.zeiterfassung.db.Users
import com.yourcompany.zeiterfassung.dto.PauseResponse
import com.yourcompany.zeiterfassung.models.PauseSessions
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val DEFAULT_BREAK_START_TIME = "12:00"
private const val DEFAULT_BREAK_END_TIME = "13:00"
private const val DEFAULT_BREAK_DURATION_MINUTES = 60
private const val DEFAULT_BREAK_TIMEZONE = "Europe/Berlin"

private data class EffectivePauseSettings(
    val mode: String = "FIXED_WINDOW",
    val startTime: LocalTime = LocalTime.parse(DEFAULT_BREAK_START_TIME),
    val endTime: LocalTime = LocalTime.parse(DEFAULT_BREAK_END_TIME),
    val durationMinutes: Int = DEFAULT_BREAK_DURATION_MINUTES,
    val timezone: String = DEFAULT_BREAK_TIMEZONE,
    val availableAfterMinutes: Int? = null,
    val activeSessionStartedAt: Instant? = null
)

fun Route.pauseRoutes() {
    authenticate("bearerAuth") {
        route("/api/pause") {
            // POST /api/pause/start
            post("/start") {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("id").asString().toInt()
                val nowInstant = Instant.now()
                val nowUtc = LocalDateTime.ofInstant(nowInstant, ZoneOffset.UTC)

                val pauseSettings = loadEffectivePauseSettingsForUser(userId)
                val companyZoneId = parseZoneIdOrDefault(pauseSettings.timezone)
                val nowCompanyLocal = LocalDateTime.ofInstant(nowInstant, companyZoneId)
                val nowTime = nowCompanyLocal.toLocalTime()

                when (pauseSettings.mode) {
                    "NONE" -> {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf(
                                "error" to "pause_disabled",
                                "pauseMode" to pauseSettings.mode,
                                "breakDurationMinutes" to pauseSettings.durationMinutes.toString(),
                                "timezone" to pauseSettings.timezone
                            )
                        )
                        return@post
                    }

                    "FIXED_WINDOW", "COMPANY_DEFAULT" -> {
                        if (nowTime.isBefore(pauseSettings.startTime) || nowTime.isAfter(pauseSettings.endTime)) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                mapOf(
                                    "error" to "pause_not_allowed_now",
                                    "pauseMode" to pauseSettings.mode,
                                    "breakStartTime" to pauseSettings.startTime.toString(),
                                    "breakEndTime" to pauseSettings.endTime.toString(),
                                    "breakDurationMinutes" to pauseSettings.durationMinutes.toString(),
                                    "timezone" to pauseSettings.timezone
                                )
                            )
                            return@post
                        }
                    }

                    "AFTER_WORK_MINUTES" -> {
                        val requiredMinutes = pauseSettings.availableAfterMinutes ?: 0
                        val sessionStartedAt = pauseSettings.activeSessionStartedAt
                        val workedMinutes = if (sessionStartedAt != null) {
                            java.time.Duration.between(sessionStartedAt, nowInstant).toMinutes()
                        } else {
                            0
                        }

                        if (workedMinutes < requiredMinutes) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                mapOf(
                                    "error" to "pause_not_available_yet",
                                    "pauseMode" to pauseSettings.mode,
                                    "availableAfterMinutes" to requiredMinutes.toString(),
                                    "workedMinutes" to workedMinutes.toString(),
                                    "breakDurationMinutes" to pauseSettings.durationMinutes.toString(),
                                    "timezone" to pauseSettings.timezone
                                )
                            )
                            return@post
                        }
                    }

                    "FLEXIBLE_DURATION" -> {
                        // Allowed while a ZeitPlan-linked tracking session is active.
                        // Duration is still returned to the client so the app can enforce/display it.
                    }

                    else -> {
                        if (nowTime.isBefore(pauseSettings.startTime) || nowTime.isAfter(pauseSettings.endTime)) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                mapOf(
                                    "error" to "pause_not_allowed_now",
                                    "pauseMode" to pauseSettings.mode,
                                    "breakStartTime" to pauseSettings.startTime.toString(),
                                    "breakEndTime" to pauseSettings.endTime.toString(),
                                    "breakDurationMinutes" to pauseSettings.durationMinutes.toString(),
                                    "timezone" to pauseSettings.timezone
                                )
                            )
                            return@post
                        }
                    }
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

private fun loadEffectivePauseSettingsForUser(userId: Int): EffectivePauseSettings {
    return transaction {
        val companyId = Users
            .select { Users.id eq userId }
            .limit(1)
            .mapNotNull { row -> row[Users.companyId]?.value }
            .firstOrNull()

        if (companyId == null) {
            return@transaction EffectivePauseSettings()
        }

        var effectiveFromZeitPlan: EffectivePauseSettings? = null

        exec(
            """
            SELECT
                s.pause_mode,
                s.pause_start_time,
                s.pause_end_time,
                s.pause_duration_minutes,
                s.pause_available_after_minutes,
                s.timezone,
                ts.started_at
            FROM tracking_sessions ts
            JOIN zeitplan_shifts s ON s.id = ts.zeitplan_shift_id
            WHERE ts.user_id = $userId
              AND ts.company_id = $companyId
              AND ts.is_active = TRUE
              AND ts.zeitplan_shift_id IS NOT NULL
              AND ts.zeitplan_assignment_id IS NOT NULL
            ORDER BY ts.started_at DESC
            LIMIT 1
            """.trimIndent()
        ) { rs ->
            if (rs.next()) {
                val mode = rs.getString("pause_mode") ?: "COMPANY_DEFAULT"

                if (mode != "COMPANY_DEFAULT") {
                    effectiveFromZeitPlan = EffectivePauseSettings(
                        mode = mode,
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
                        timezone = rs.getString("timezone") ?: DEFAULT_BREAK_TIMEZONE,
                        availableAfterMinutes = rs.getInt("pause_available_after_minutes").takeIf { !rs.wasNull() },
                        activeSessionStartedAt = rs.getTimestamp("started_at")?.toInstant()
                    )
                }
            }
        }

        val zeitPlanSettings = effectiveFromZeitPlan
        if (zeitPlanSettings != null) {
            return@transaction zeitPlanSettings
        }

        var effectiveFromCompany = EffectivePauseSettings(mode = "COMPANY_DEFAULT")

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
                effectiveFromCompany = EffectivePauseSettings(
                    mode = "COMPANY_DEFAULT",
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
            }
        }

        return@transaction effectiveFromCompany
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

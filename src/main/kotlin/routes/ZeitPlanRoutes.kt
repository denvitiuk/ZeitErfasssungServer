package com.yourcompany.zeiterfassung.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.util.UUID

@Serializable
data class ZeitPlanPauseRuleDto(
    val mode: String,
    val durationMinutes: Int? = null,
    val pauseStartTime: String? = null,
    val pauseEndTime: String? = null,
    val pauseAvailableAfterMinutes: Int? = null
)

@Serializable
data class WorkerZeitPlanItemDto(
    val planId: String,
    val shiftId: String,
    val assignmentId: String,
    val title: String,
    val groupTitle: String,
    val shiftDate: String,
    val startTime: String,
    val endTime: String,
    val timezone: String,
    val pauseRule: ZeitPlanPauseRuleDto,
    val status: String,
    val notifyEmailEnabled: Boolean,
    val notifyPushEnabled: Boolean,
    val reminderMinutesBeforeStart: Int? = null,
    val lateAlertMinutesAfterStart: Int? = null,
    val adminLateAlertEnabled: Boolean
)

@Serializable
data class WorkerZeitPlanTodayResponseDto(
    val date: String,
    val items: List<WorkerZeitPlanItemDto>
)

@Serializable
data class ZeitPlanSeenResponseDto(
    val assignmentId: String,
    val status: String,
    val seenAt: String? = null
)

fun Route.zeitPlanRoutes() {
    route("/zeitplan") {

        // WORKER: today's planned shifts for the authenticated user.
        // Returns items because a worker can theoretically have more than one shift per day.
        get("/me/today") {
            val userId = call.routeRequireUserId()
            val ctx = routeLoadUserContext(userId)
            val dateStr = call.request.queryParameters["date"] ?: LocalDate.now().toString()

            try {
                LocalDate.parse(dateStr)
            } catch (_: Throwable) {
                throw BadRequestException("Invalid date format. Expected YYYY-MM-DD")
            }

            val rows = transaction {
                val stmt = connection.prepareStatement(
                    """
                    SELECT
                      a.id AS assignment_id,
                      a.status AS assignment_status,
                      p.id AS plan_id,
                      p.title AS plan_title,
                      g.title AS group_title,
                      s.id AS shift_id,
                      s.shift_date,
                      s.start_time,
                      s.end_time,
                      s.timezone,
                      s.pause_mode,
                      s.pause_duration_minutes,
                      s.pause_start_time,
                      s.pause_end_time,
                      s.pause_available_after_minutes,
                      s.notify_email_enabled,
                      s.notify_push_enabled,
                      s.reminder_minutes_before_start,
                      s.late_alert_minutes_after_start,
                      s.admin_late_alert_enabled
                    FROM zeitplan_shift_assignments a
                    JOIN zeitplan_shifts s ON s.id = a.shift_id
                    JOIN zeitplan_shift_groups g ON g.id = a.shift_group_id
                    JOIN zeitplan_plans p ON p.id = a.plan_id
                    WHERE a.company_id = ?
                      AND a.user_id = ?
                      AND s.shift_date = ?::date
                      AND a.status IN ('PLANNED', 'NOTIFIED', 'SEEN', 'STARTED')
                      AND s.status IN ('PLANNED', 'ACTIVE')
                      AND p.status = 'ACTIVE'
                    ORDER BY s.start_time ASC
                    """.trimIndent(),
                    false
                )
                try {
                    stmt.set(1, ctx.companyId)
                    stmt.set(2, userId)
                    stmt.set(3, dateStr)

                    val rs = stmt.executeQuery()
                    rs.use {
                        val out = mutableListOf<WorkerZeitPlanItemDto>()
                        while (it.next()) {
                            out.add(
                                WorkerZeitPlanItemDto(
                                    planId = it.getObject("plan_id", UUID::class.java).toString(),
                                    shiftId = it.getObject("shift_id", UUID::class.java).toString(),
                                    assignmentId = it.getObject("assignment_id", UUID::class.java).toString(),
                                    title = it.getString("plan_title"),
                                    groupTitle = it.getString("group_title"),
                                    shiftDate = it.getString("shift_date"),
                                    startTime = it.getString("start_time"),
                                    endTime = it.getString("end_time"),
                                    timezone = it.getString("timezone"),
                                    pauseRule = ZeitPlanPauseRuleDto(
                                        mode = it.getString("pause_mode"),
                                        durationMinutes = it.getObject("pause_duration_minutes")?.let { _ -> it.getInt("pause_duration_minutes") },
                                        pauseStartTime = it.getString("pause_start_time"),
                                        pauseEndTime = it.getString("pause_end_time"),
                                        pauseAvailableAfterMinutes = it.getObject("pause_available_after_minutes")?.let { _ -> it.getInt("pause_available_after_minutes") }
                                    ),
                                    status = it.getString("assignment_status"),
                                    notifyEmailEnabled = it.getBoolean("notify_email_enabled"),
                                    notifyPushEnabled = it.getBoolean("notify_push_enabled"),
                                    reminderMinutesBeforeStart = it.getObject("reminder_minutes_before_start")?.let { _ -> it.getInt("reminder_minutes_before_start") },
                                    lateAlertMinutesAfterStart = it.getObject("late_alert_minutes_after_start")?.let { _ -> it.getInt("late_alert_minutes_after_start") },
                                    adminLateAlertEnabled = it.getBoolean("admin_late_alert_enabled")
                                )
                            )
                        }
                        out
                    }
                } finally {
                    // Exposed manages this statement resource inside the transaction.
                }
            }

            call.respond(WorkerZeitPlanTodayResponseDto(date = dateStr, items = rows))
        }

        // WORKER: mark an assignment as seen after the worker app displays it.
        post("/me/assignments/{assignmentId}/seen") {
            val userId = call.routeRequireUserId()
            val ctx = routeLoadUserContext(userId)
            val assignmentId = routeParseUuid(call.parameters["assignmentId"], "assignmentId")

            val updated = transaction {
                routeQueryOne(
                    """
                    UPDATE zeitplan_shift_assignments
                    SET
                      status = CASE
                        WHEN status IN ('PLANNED', 'NOTIFIED') THEN 'SEEN'
                        ELSE status
                      END,
                      seen_at = CASE
                        WHEN seen_at IS NULL THEN now()
                        ELSE seen_at
                      END,
                      updated_at = now()
                    WHERE id = ?
                      AND user_id = ?
                      AND company_id = ?
                      AND status IN ('PLANNED', 'NOTIFIED', 'SEEN', 'STARTED')
                    RETURNING id, status, seen_at
                    """.trimIndent(),
                    listOf(assignmentId, userId, ctx.companyId)
                ) { rs ->
                    ZeitPlanSeenResponseDto(
                        assignmentId = rs.getObject("id", UUID::class.java).toString(),
                        status = rs.getString("status"),
                        seenAt = rs.getString("seen_at")
                    )
                }
            }

            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "ZeitPlan assignment not found"))
                return@post
            }

            call.respond(updated)
        }
    }
}

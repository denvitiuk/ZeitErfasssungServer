package com.yourcompany.zeiterfassung.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.request.receive
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.util.UUID

@Serializable
data class ZeitPlanPauseRuleDto(
    val mode: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val durationMinutes: Int? = null,
    val availableAfterMinutes: Int? = null
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

@Serializable
data class CreateZeitPlanGroupRequestDto(
    val title: String,
    val startTime: String,
    val endTime: String,
    val userIds: List<Long>,
    val pauseMode: String = "COMPANY_DEFAULT",
    val pauseDurationMinutes: Int? = null,
    val pauseStartTime: String? = null,
    val pauseEndTime: String? = null,
    val pauseAvailableAfterMinutes: Int? = null,
    val notifyEmailEnabled: Boolean = false,
    val notifyPushEnabled: Boolean = false,
    val reminderMinutesBeforeStart: Int? = null,
    val lateAlertMinutesAfterStart: Int? = null,
    val adminLateAlertEnabled: Boolean = false
)

@Serializable
data class CreateZeitPlanRequestDto(
    val title: String,
    val planType: String,
    val startDate: String,
    val endDate: String,
    val daysOfWeek: List<Int>,
    val timezone: String = "Europe/Berlin",
    val projectId: Int? = null,
    val groups: List<CreateZeitPlanGroupRequestDto>
)

@Serializable
data class CreateZeitPlanResponseDto(
    val planId: String,
    val groupsCreated: Int,
    val shiftsCreated: Int,
    val assignmentsCreated: Int
)

@Serializable
data class AdminZeitPlanListItemDto(
    val planId: String,
    val title: String,
    val planType: String,
    val startDate: String,
    val endDate: String,
    val timezone: String,
    val projectId: Int? = null,
    val status: String,
    val groupsCount: Int,
    val shiftsCount: Int,
    val assignmentsCount: Int,
    val createdByAdminId: Long? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class AdminZeitPlanListResponseDto(
    val from: String,
    val to: String,
    val items: List<AdminZeitPlanListItemDto>
)

@Serializable
data class AdminZeitPlanShiftItemDto(
    val planId: String,
    val planTitle: String,
    val shiftId: String,
    val groupId: String,
    val groupTitle: String,
    val shiftDate: String,
    val startTime: String,
    val endTime: String,
    val timezone: String,
    val status: String,
    val projectId: Int? = null,
    val pauseRule: ZeitPlanPauseRuleDto,
    val notifyEmailEnabled: Boolean,
    val notifyPushEnabled: Boolean,
    val reminderMinutesBeforeStart: Int? = null,
    val lateAlertMinutesAfterStart: Int? = null,
    val adminLateAlertEnabled: Boolean,
    val assignmentsCount: Int,
    val plannedCount: Int,
    val notifiedCount: Int,
    val seenCount: Int,
    val startedCount: Int,
    val completedCount: Int,
    val missedCount: Int,
    val cancelledCount: Int
)

@Serializable
data class AdminZeitPlanShiftsResponseDto(
    val from: String,
    val to: String,
    val items: List<AdminZeitPlanShiftItemDto>
)

@Serializable
data class ZeitPlanActionResponseDto(
    val ok: Boolean,
    val planId: String? = null,
    val shiftId: String? = null,
    val updatedPlans: Int = 0,
    val updatedShifts: Int = 0,
    val updatedAssignments: Int = 0,
    val notificationLogsCreated: Int = 0,
    val missedAssignments: Int = 0
)

fun Route.zeitPlanRoutes() {
    route("/admin/zeitplan") {
        get("/plans") {
            val adminUserId = call.routeRequireUserId()
            val ctx = routeLoadUserContext(adminUserId)
            val from = call.request.queryParameters["from"] ?: throw BadRequestException("from is required")
            val to = call.request.queryParameters["to"] ?: throw BadRequestException("to is required")

            try {
                LocalDate.parse(from)
                LocalDate.parse(to)
            } catch (_: Throwable) {
                throw BadRequestException("Invalid date format. Expected YYYY-MM-DD")
            }

            val items = transaction {
                val stmt = connection.prepareStatement(
                    """
                    SELECT
                      p.id AS plan_id,
                      p.title,
                      p.plan_type,
                      p.start_date,
                      p.end_date,
                      p.timezone,
                      p.project_id,
                      p.status,
                      p.created_by_admin_id,
                      p.created_at,
                      p.updated_at,
                      COUNT(DISTINCT g.id)::int AS groups_count,
                      COUNT(DISTINCT s.id)::int AS shifts_count,
                      COUNT(DISTINCT a.id)::int AS assignments_count
                    FROM zeitplan_plans p
                    LEFT JOIN zeitplan_shift_groups g ON g.plan_id = p.id
                    LEFT JOIN zeitplan_shifts s ON s.plan_id = p.id
                    LEFT JOIN zeitplan_shift_assignments a ON a.plan_id = p.id
                    WHERE p.company_id = ?
                      AND p.start_date <= ?::date
                      AND p.end_date >= ?::date
                    GROUP BY p.id
                    ORDER BY p.start_date ASC, p.created_at DESC
                    """.trimIndent(),
                    false
                )
                try {
                    stmt.set(1, ctx.companyId)
                    stmt.set(2, to)
                    stmt.set(3, from)

                    val rs = stmt.executeQuery()
                    rs.use {
                        val out = mutableListOf<AdminZeitPlanListItemDto>()
                        while (it.next()) {
                            out.add(
                                AdminZeitPlanListItemDto(
                                    planId = it.getObject("plan_id", UUID::class.java).toString(),
                                    title = it.getString("title"),
                                    planType = it.getString("plan_type"),
                                    startDate = it.getString("start_date"),
                                    endDate = it.getString("end_date"),
                                    timezone = it.getString("timezone"),
                                    projectId = it.getObject("project_id")?.let { value -> (value as Number).toInt() },
                                    status = it.getString("status"),
                                    groupsCount = it.getInt("groups_count"),
                                    shiftsCount = it.getInt("shifts_count"),
                                    assignmentsCount = it.getInt("assignments_count"),
                                    createdByAdminId = it.getObject("created_by_admin_id")?.let { value -> (value as Number).toLong() },
                                    createdAt = it.getString("created_at"),
                                    updatedAt = it.getString("updated_at")
                                )
                            )
                        }
                        out
                    }
                } finally {
                    // Exposed manages this statement resource inside the transaction.
                }
            }

            call.respond(AdminZeitPlanListResponseDto(from = from, to = to, items = items))
        }

        get("/shifts") {
            val adminUserId = call.routeRequireUserId()
            val ctx = routeLoadUserContext(adminUserId)
            val from = call.request.queryParameters["from"] ?: throw BadRequestException("from is required")
            val to = call.request.queryParameters["to"] ?: throw BadRequestException("to is required")

            try {
                LocalDate.parse(from)
                LocalDate.parse(to)
            } catch (_: Throwable) {
                throw BadRequestException("Invalid date format. Expected YYYY-MM-DD")
            }

            val items = transaction {
                val stmt = connection.prepareStatement(
                    """
                    SELECT
                      p.id AS plan_id,
                      p.title AS plan_title,
                      s.id AS shift_id,
                      g.id AS group_id,
                      g.title AS group_title,
                      s.shift_date,
                      s.start_time,
                      s.end_time,
                      s.timezone,
                      s.status,
                      s.project_id,
                      s.pause_mode,
                      s.pause_duration_minutes,
                      s.pause_start_time,
                      s.pause_end_time,
                      s.pause_available_after_minutes,
                      s.notify_email_enabled,
                      s.notify_push_enabled,
                      s.reminder_minutes_before_start,
                      s.late_alert_minutes_after_start,
                      s.admin_late_alert_enabled,
                      COUNT(a.id)::int AS assignments_count,
                      COUNT(*) FILTER (WHERE a.status = 'PLANNED')::int AS planned_count,
                      COUNT(*) FILTER (WHERE a.status = 'NOTIFIED')::int AS notified_count,
                      COUNT(*) FILTER (WHERE a.status = 'SEEN')::int AS seen_count,
                      COUNT(*) FILTER (WHERE a.status = 'STARTED')::int AS started_count,
                      COUNT(*) FILTER (WHERE a.status = 'COMPLETED')::int AS completed_count,
                      COUNT(*) FILTER (WHERE a.status = 'MISSED')::int AS missed_count,
                      COUNT(*) FILTER (WHERE a.status = 'CANCELLED')::int AS cancelled_count
                    FROM zeitplan_shifts s
                    JOIN zeitplan_plans p ON p.id = s.plan_id
                    JOIN zeitplan_shift_groups g ON g.id = s.shift_group_id
                    LEFT JOIN zeitplan_shift_assignments a ON a.shift_id = s.id
                    WHERE s.company_id = ?
                      AND s.shift_date BETWEEN ?::date AND ?::date
                    GROUP BY p.id, p.title, s.id, g.id, g.title
                    ORDER BY s.shift_date ASC, s.start_time ASC, g.title ASC
                    """.trimIndent(),
                    false
                )
                try {
                    stmt.set(1, ctx.companyId)
                    stmt.set(2, from)
                    stmt.set(3, to)

                    val rs = stmt.executeQuery()
                    rs.use {
                        val out = mutableListOf<AdminZeitPlanShiftItemDto>()
                        while (it.next()) {
                            out.add(
                                AdminZeitPlanShiftItemDto(
                                    planId = it.getObject("plan_id", UUID::class.java).toString(),
                                    planTitle = it.getString("plan_title"),
                                    shiftId = it.getObject("shift_id", UUID::class.java).toString(),
                                    groupId = it.getObject("group_id", UUID::class.java).toString(),
                                    groupTitle = it.getString("group_title"),
                                    shiftDate = it.getString("shift_date"),
                                    startTime = it.getString("start_time"),
                                    endTime = it.getString("end_time"),
                                    timezone = it.getString("timezone"),
                                    status = it.getString("status"),
                                    projectId = it.getObject("project_id")?.let { value -> (value as Number).toInt() },
                                    pauseRule = ZeitPlanPauseRuleDto(
                                        mode = it.getString("pause_mode"),
                                        startTime = it.getString("pause_start_time"),
                                        endTime = it.getString("pause_end_time"),
                                        durationMinutes = it.getObject("pause_duration_minutes")?.let { _ -> it.getInt("pause_duration_minutes") },
                                        availableAfterMinutes = it.getObject("pause_available_after_minutes")?.let { _ -> it.getInt("pause_available_after_minutes") }
                                    ),
                                    notifyEmailEnabled = it.getBoolean("notify_email_enabled"),
                                    notifyPushEnabled = it.getBoolean("notify_push_enabled"),
                                    reminderMinutesBeforeStart = it.getObject("reminder_minutes_before_start")?.let { _ -> it.getInt("reminder_minutes_before_start") },
                                    lateAlertMinutesAfterStart = it.getObject("late_alert_minutes_after_start")?.let { _ -> it.getInt("late_alert_minutes_after_start") },
                                    adminLateAlertEnabled = it.getBoolean("admin_late_alert_enabled"),
                                    assignmentsCount = it.getInt("assignments_count"),
                                    plannedCount = it.getInt("planned_count"),
                                    notifiedCount = it.getInt("notified_count"),
                                    seenCount = it.getInt("seen_count"),
                                    startedCount = it.getInt("started_count"),
                                    completedCount = it.getInt("completed_count"),
                                    missedCount = it.getInt("missed_count"),
                                    cancelledCount = it.getInt("cancelled_count")
                                )
                            )
                        }
                        out
                    }
                } finally {
                    // Exposed manages this statement resource inside the transaction.
                }
            }

            call.respond(AdminZeitPlanShiftsResponseDto(from = from, to = to, items = items))
        }

        post("/plans") {
            val adminUserId = call.routeRequireUserId()
            val ctx = routeLoadUserContext(adminUserId)
            val body = call.receive<CreateZeitPlanRequestDto>()

            val title = body.title.trim()
            if (title.isBlank()) throw BadRequestException("title is required")

            val allowedPlanTypes = setOf("DAY", "WEEK", "MONTH", "DATE_RANGE", "CUSTOM")
            if (body.planType !in allowedPlanTypes) {
                throw BadRequestException("Invalid planType")
            }

            val allowedPauseModes = setOf(
                "COMPANY_DEFAULT",
                "NONE",
                "FLEXIBLE_DURATION",
                "FIXED_WINDOW",
                "AFTER_WORK_MINUTES"
            )

            val startDate = try {
                LocalDate.parse(body.startDate)
            } catch (_: Throwable) {
                throw BadRequestException("Invalid startDate format. Expected YYYY-MM-DD")
            }

            val endDate = try {
                LocalDate.parse(body.endDate)
            } catch (_: Throwable) {
                throw BadRequestException("Invalid endDate format. Expected YYYY-MM-DD")
            }

            if (endDate.isBefore(startDate)) {
                throw BadRequestException("endDate must be greater than or equal to startDate")
            }

            val normalizedDays = body.daysOfWeek.distinct().sorted()
            if (normalizedDays.isEmpty() || normalizedDays.any { it !in 1..7 }) {
                throw BadRequestException("daysOfWeek must contain values from 1 to 7")
            }

            if (body.groups.isEmpty()) {
                throw BadRequestException("At least one shift group is required")
            }

            body.groups.forEach { group ->
                if (group.title.isBlank()) throw BadRequestException("group title is required")
                if (group.userIds.isEmpty()) throw BadRequestException("group userIds must not be empty")
                if (group.pauseMode !in allowedPauseModes) throw BadRequestException("Invalid pauseMode")
                if (group.pauseDurationMinutes != null && group.pauseDurationMinutes < 0) throw BadRequestException("pauseDurationMinutes must be >= 0")
                if (group.pauseAvailableAfterMinutes != null && group.pauseAvailableAfterMinutes < 0) throw BadRequestException("pauseAvailableAfterMinutes must be >= 0")
                if (group.reminderMinutesBeforeStart != null && group.reminderMinutesBeforeStart < 0) throw BadRequestException("reminderMinutesBeforeStart must be >= 0")
                if (group.lateAlertMinutesAfterStart != null && group.lateAlertMinutesAfterStart < 0) throw BadRequestException("lateAlertMinutesAfterStart must be >= 0")
            }

            val dates = generateSequence(startDate) { current ->
                val next = current.plusDays(1)
                if (next.isAfter(endDate)) null else next
            }.filter { it.dayOfWeek.value in normalizedDays }.toList()

            if (dates.isEmpty()) {
                throw BadRequestException("No shift dates match the selected date range and daysOfWeek")
            }

            val result = transaction {
                fun nullableIntSql(value: Int?): String = value?.toString() ?: "NULL"
                fun nullableTimeSql(value: String?): String = value?.let { "TIME '$it'" } ?: "NULL"

                val planId = routeQueryOne(
                    """
                    INSERT INTO zeitplan_plans (
                      company_id,
                      title,
                      plan_type,
                      start_date,
                      end_date,
                      timezone,
                      project_id,
                      status,
                      created_by_admin_id
                    )
                    VALUES (
                      ?,
                      ?,
                      ?,
                      ?::date,
                      ?::date,
                      ?,
                      ${nullableIntSql(body.projectId)},
                      'ACTIVE',
                      ?
                    )
                    RETURNING id
                    """.trimIndent(),
                    listOf(ctx.companyId, title, body.planType, body.startDate, body.endDate, body.timezone, adminUserId)
                ) { rs -> rs.getObject("id", UUID::class.java) }
                    ?: throw BadRequestException("Failed to create ZeitPlan")

                var groupsCreated = 0
                var shiftsCreated = 0
                var assignmentsCreated = 0

                body.groups.forEach { group ->
                    val groupId = routeQueryOne(
                        """
                        INSERT INTO zeitplan_shift_groups (
                          plan_id,
                          company_id,
                          title,
                          start_time,
                          end_time,
                          pause_mode,
                          pause_start_time,
                          pause_end_time,
                          pause_duration_minutes,
                          pause_available_after_minutes,
                          notify_email_enabled,
                          notify_push_enabled,
                          reminder_minutes_before_start,
                          late_alert_minutes_after_start,
                          admin_late_alert_enabled
                        )
                        VALUES (
                          ?,
                          ?,
                          ?,
                          ?::time,
                          ?::time,
                          ?,
                          ${nullableTimeSql(group.pauseStartTime)},
                          ${nullableTimeSql(group.pauseEndTime)},
                          ${nullableIntSql(group.pauseDurationMinutes)},
                          ${nullableIntSql(group.pauseAvailableAfterMinutes)},
                          ?,
                          ?,
                          ${nullableIntSql(group.reminderMinutesBeforeStart)},
                          ${nullableIntSql(group.lateAlertMinutesAfterStart)},
                          ?
                        )
                        RETURNING id
                        """.trimIndent(),
                        listOf(
                            planId,
                            ctx.companyId,
                            group.title.trim(),
                            group.startTime,
                            group.endTime,
                            group.pauseMode,
                            group.notifyEmailEnabled,
                            group.notifyPushEnabled,
                            group.adminLateAlertEnabled
                        )
                    ) { rs -> rs.getObject("id", UUID::class.java) }
                        ?: throw BadRequestException("Failed to create ZeitPlan group")

                    groupsCreated += 1

                    dates.forEach { shiftDate ->
                        val shiftId = routeQueryOne(
                            """
                            INSERT INTO zeitplan_shifts (
                              plan_id,
                              shift_group_id,
                              company_id,
                              project_id,
                              shift_date,
                              start_time,
                              end_time,
                              timezone,
                              pause_mode,
                              pause_start_time,
                              pause_end_time,
                              pause_duration_minutes,
                              pause_available_after_minutes,
                              notify_email_enabled,
                              notify_push_enabled,
                              reminder_minutes_before_start,
                              late_alert_minutes_after_start,
                              admin_late_alert_enabled,
                              status
                            )
                            VALUES (
                              ?,
                              ?,
                              ?,
                              ${nullableIntSql(body.projectId)},
                              ?::date,
                              ?::time,
                              ?::time,
                              ?,
                              ?,
                              ${nullableTimeSql(group.pauseStartTime)},
                              ${nullableTimeSql(group.pauseEndTime)},
                              ${nullableIntSql(group.pauseDurationMinutes)},
                              ${nullableIntSql(group.pauseAvailableAfterMinutes)},
                              ?,
                              ?,
                              ${nullableIntSql(group.reminderMinutesBeforeStart)},
                              ${nullableIntSql(group.lateAlertMinutesAfterStart)},
                              ?,
                              'PLANNED'
                            )
                            RETURNING id
                            """.trimIndent(),
                            listOf(
                                planId,
                                groupId,
                                ctx.companyId,
                                shiftDate.toString(),
                                group.startTime,
                                group.endTime,
                                body.timezone,
                                group.pauseMode,
                                group.notifyEmailEnabled,
                                group.notifyPushEnabled,
                                group.adminLateAlertEnabled
                            )
                        ) { rs -> rs.getObject("id", UUID::class.java) }
                            ?: throw BadRequestException("Failed to create ZeitPlan shift")

                        shiftsCreated += 1

                        group.userIds.distinct().forEach { workerUserId ->
                            routeQueryOne(
                                """
                                INSERT INTO zeitplan_shift_assignments (
                                  shift_id,
                                  shift_group_id,
                                  plan_id,
                                  company_id,
                                  user_id,
                                  status
                                )
                                VALUES (?, ?, ?, ?, ?, 'PLANNED')
                                ON CONFLICT (shift_id, user_id) DO NOTHING
                                RETURNING id
                                """.trimIndent(),
                                listOf(shiftId, groupId, planId, ctx.companyId, workerUserId)
                            ) { rs -> rs.getObject("id", UUID::class.java) }?.let {
                                assignmentsCreated += 1
                            }
                        }
                    }
                }

                CreateZeitPlanResponseDto(
                    planId = planId.toString(),
                    groupsCreated = groupsCreated,
                    shiftsCreated = shiftsCreated,
                    assignmentsCreated = assignmentsCreated
                )
            }

            call.respond(HttpStatusCode.Created, result)
        }

        post("/plans/{planId}/cancel") {
            val adminUserId = call.routeRequireUserId()
            val ctx = routeLoadUserContext(adminUserId)
            val planId = routeParseUuid(call.parameters["planId"], "planId")

            val result = transaction {
                routeQueryOne(
                    """
                    WITH target AS (
                      SELECT id
                      FROM zeitplan_plans
                      WHERE id = ?
                        AND company_id = ?
                    ),
                    updated_plan AS (
                      UPDATE zeitplan_plans
                      SET status = 'CANCELLED', updated_at = now()
                      WHERE id IN (SELECT id FROM target)
                      RETURNING id
                    ),
                    updated_shifts AS (
                      UPDATE zeitplan_shifts
                      SET status = 'CANCELLED', updated_at = now()
                      WHERE plan_id IN (SELECT id FROM target)
                        AND status <> 'CANCELLED'
                      RETURNING id
                    ),
                    updated_assignments AS (
                      UPDATE zeitplan_shift_assignments
                      SET status = 'CANCELLED', updated_at = now()
                      WHERE plan_id IN (SELECT id FROM target)
                        AND status <> 'CANCELLED'
                      RETURNING id
                    )
                    SELECT
                      (SELECT COUNT(*)::int FROM updated_plan) AS updated_plans,
                      (SELECT COUNT(*)::int FROM updated_shifts) AS updated_shifts,
                      (SELECT COUNT(*)::int FROM updated_assignments) AS updated_assignments
                    """.trimIndent(),
                    listOf(planId, ctx.companyId)
                ) { rs ->
                    ZeitPlanActionResponseDto(
                        ok = rs.getInt("updated_plans") > 0,
                        planId = planId.toString(),
                        updatedPlans = rs.getInt("updated_plans"),
                        updatedShifts = rs.getInt("updated_shifts"),
                        updatedAssignments = rs.getInt("updated_assignments")
                    )
                }
            } ?: throw BadRequestException("Failed to cancel ZeitPlan")

            if (!result.ok) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "ZeitPlan plan not found"))
                return@post
            }

            call.respond(result)
        }

        post("/plans/{planId}/notify") {
            val adminUserId = call.routeRequireUserId()
            val ctx = routeLoadUserContext(adminUserId)
            val planId = routeParseUuid(call.parameters["planId"], "planId")

            val result = transaction {
                routeQueryOne(
                    """
                    WITH target AS (
                      SELECT
                        a.id AS assignment_id,
                        a.user_id,
                        a.company_id,
                        s.id AS shift_id,
                        p.id AS plan_id,
                        s.notify_email_enabled,
                        s.notify_push_enabled
                      FROM zeitplan_shift_assignments a
                      JOIN zeitplan_shifts s ON s.id = a.shift_id
                      JOIN zeitplan_plans p ON p.id = a.plan_id
                      WHERE p.id = ?
                        AND p.company_id = ?
                        AND p.status = 'ACTIVE'
                        AND s.status IN ('PLANNED', 'ACTIVE')
                        AND a.status IN ('PLANNED', 'NOTIFIED', 'SEEN')
                    ),
                    email_logs AS (
                      INSERT INTO zeitplan_notification_logs (
                        plan_id,
                        shift_id,
                        assignment_id,
                        company_id,
                        user_id,
                        admin_user_id,
                        channel,
                        type,
                        status,
                        sent_at
                      )
                      SELECT
                        plan_id,
                        shift_id,
                        assignment_id,
                        company_id,
                        user_id,
                        ?,
                        'EMAIL',
                        'SHIFT_ASSIGNED',
                        'SENT',
                        now()
                      FROM target
                      WHERE notify_email_enabled = TRUE
                      RETURNING id
                    ),
                    push_logs AS (
                      INSERT INTO zeitplan_notification_logs (
                        plan_id,
                        shift_id,
                        assignment_id,
                        company_id,
                        user_id,
                        admin_user_id,
                        channel,
                        type,
                        status,
                        sent_at
                      )
                      SELECT
                        plan_id,
                        shift_id,
                        assignment_id,
                        company_id,
                        user_id,
                        ?,
                        'PUSH',
                        'SHIFT_ASSIGNED',
                        'SENT',
                        now()
                      FROM target
                      WHERE notify_push_enabled = TRUE
                      RETURNING id
                    ),
                    updated_assignments AS (
                      UPDATE zeitplan_shift_assignments a
                      SET
                        status = CASE WHEN a.status = 'PLANNED' THEN 'NOTIFIED' ELSE a.status END,
                        email_sent_at = CASE WHEN t.notify_email_enabled = TRUE THEN COALESCE(a.email_sent_at, now()) ELSE a.email_sent_at END,
                        push_sent_at = CASE WHEN t.notify_push_enabled = TRUE THEN COALESCE(a.push_sent_at, now()) ELSE a.push_sent_at END,
                        updated_at = now()
                      FROM target t
                      WHERE a.id = t.assignment_id
                      RETURNING a.id
                    )
                    SELECT
                      (SELECT COUNT(*)::int FROM target) AS updated_assignments,
                      ((SELECT COUNT(*)::int FROM email_logs) + (SELECT COUNT(*)::int FROM push_logs)) AS logs_created
                    """.trimIndent(),
                    listOf(planId, ctx.companyId, adminUserId, adminUserId)
                ) { rs ->
                    ZeitPlanActionResponseDto(
                        ok = true,
                        planId = planId.toString(),
                        updatedAssignments = rs.getInt("updated_assignments"),
                        notificationLogsCreated = rs.getInt("logs_created")
                    )
                }
            } ?: throw BadRequestException("Failed to notify ZeitPlan")

            call.respond(result)
        }

        post("/shifts/{shiftId}/notify") {
            val adminUserId = call.routeRequireUserId()
            val ctx = routeLoadUserContext(adminUserId)
            val shiftId = routeParseUuid(call.parameters["shiftId"], "shiftId")

            val result = transaction {
                routeQueryOne(
                    """
                    WITH target AS (
                      SELECT
                        a.id AS assignment_id,
                        a.user_id,
                        a.company_id,
                        s.id AS shift_id,
                        p.id AS plan_id,
                        s.notify_email_enabled,
                        s.notify_push_enabled
                      FROM zeitplan_shift_assignments a
                      JOIN zeitplan_shifts s ON s.id = a.shift_id
                      JOIN zeitplan_plans p ON p.id = a.plan_id
                      WHERE s.id = ?
                        AND s.company_id = ?
                        AND p.status = 'ACTIVE'
                        AND s.status IN ('PLANNED', 'ACTIVE')
                        AND a.status IN ('PLANNED', 'NOTIFIED', 'SEEN')
                    ),
                    email_logs AS (
                      INSERT INTO zeitplan_notification_logs (
                        plan_id,
                        shift_id,
                        assignment_id,
                        company_id,
                        user_id,
                        admin_user_id,
                        channel,
                        type,
                        status,
                        sent_at
                      )
                      SELECT
                        plan_id,
                        shift_id,
                        assignment_id,
                        company_id,
                        user_id,
                        ?,
                        'EMAIL',
                        'SHIFT_ASSIGNED',
                        'SENT',
                        now()
                      FROM target
                      WHERE notify_email_enabled = TRUE
                      RETURNING id
                    ),
                    push_logs AS (
                      INSERT INTO zeitplan_notification_logs (
                        plan_id,
                        shift_id,
                        assignment_id,
                        company_id,
                        user_id,
                        admin_user_id,
                        channel,
                        type,
                        status,
                        sent_at
                      )
                      SELECT
                        plan_id,
                        shift_id,
                        assignment_id,
                        company_id,
                        user_id,
                        ?,
                        'PUSH',
                        'SHIFT_ASSIGNED',
                        'SENT',
                        now()
                      FROM target
                      WHERE notify_push_enabled = TRUE
                      RETURNING id
                    ),
                    updated_assignments AS (
                      UPDATE zeitplan_shift_assignments a
                      SET
                        status = CASE WHEN a.status = 'PLANNED' THEN 'NOTIFIED' ELSE a.status END,
                        email_sent_at = CASE WHEN t.notify_email_enabled = TRUE THEN COALESCE(a.email_sent_at, now()) ELSE a.email_sent_at END,
                        push_sent_at = CASE WHEN t.notify_push_enabled = TRUE THEN COALESCE(a.push_sent_at, now()) ELSE a.push_sent_at END,
                        updated_at = now()
                      FROM target t
                      WHERE a.id = t.assignment_id
                      RETURNING a.id
                    )
                    SELECT
                      (SELECT COUNT(*)::int FROM target) AS updated_assignments,
                      ((SELECT COUNT(*)::int FROM email_logs) + (SELECT COUNT(*)::int FROM push_logs)) AS logs_created
                    """.trimIndent(),
                    listOf(shiftId, ctx.companyId, adminUserId, adminUserId)
                ) { rs ->
                    ZeitPlanActionResponseDto(
                        ok = true,
                        shiftId = shiftId.toString(),
                        updatedAssignments = rs.getInt("updated_assignments"),
                        notificationLogsCreated = rs.getInt("logs_created")
                    )
                }
            } ?: throw BadRequestException("Failed to notify ZeitPlan shift")

            call.respond(result)
        }

        post("/late-alerts/check") {
            val adminUserId = call.routeRequireUserId()
            val ctx = routeLoadUserContext(adminUserId)

            val result = transaction {
                routeQueryOne(
                    """
                    WITH target AS (
                      SELECT
                        a.id AS assignment_id,
                        a.user_id,
                        a.company_id,
                        s.id AS shift_id,
                        p.id AS plan_id
                      FROM zeitplan_shift_assignments a
                      JOIN zeitplan_shifts s ON s.id = a.shift_id
                      JOIN zeitplan_plans p ON p.id = a.plan_id
                      WHERE a.company_id = ?
                        AND p.status = 'ACTIVE'
                        AND s.status IN ('PLANNED', 'ACTIVE')
                        AND a.status IN ('PLANNED', 'NOTIFIED', 'SEEN')
                        AND s.admin_late_alert_enabled = TRUE
                        AND s.late_alert_minutes_after_start IS NOT NULL
                        AND a.started_at IS NULL
                        AND (((s.shift_date::timestamp + s.start_time) AT TIME ZONE s.timezone) + (s.late_alert_minutes_after_start * INTERVAL '1 minute')) < now()
                    ),
                    missed AS (
                      UPDATE zeitplan_shift_assignments a
                      SET
                        status = 'MISSED',
                        missed_at = now(),
                        updated_at = now()
                      FROM target t
                      WHERE a.id = t.assignment_id
                      RETURNING a.id, t.plan_id, t.shift_id, t.company_id, t.user_id
                    ),
                    logs AS (
                      INSERT INTO zeitplan_notification_logs (
                        plan_id,
                        shift_id,
                        assignment_id,
                        company_id,
                        user_id,
                        admin_user_id,
                        channel,
                        type,
                        status,
                        sent_at
                      )
                      SELECT
                        plan_id,
                        shift_id,
                        id,
                        company_id,
                        user_id,
                        ?,
                        'PUSH',
                        'ADMIN_WORKER_MISSED',
                        'SENT',
                        now()
                      FROM missed
                      RETURNING id
                    )
                    SELECT
                      (SELECT COUNT(*)::int FROM missed) AS missed_assignments,
                      (SELECT COUNT(*)::int FROM logs) AS logs_created
                    """.trimIndent(),
                    listOf(ctx.companyId, adminUserId)
                ) { rs ->
                    ZeitPlanActionResponseDto(
                        ok = true,
                        missedAssignments = rs.getInt("missed_assignments"),
                        notificationLogsCreated = rs.getInt("logs_created")
                    )
                }
            } ?: throw BadRequestException("Failed to check late alerts")

            call.respond(result)
        }
    }

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
                                        startTime = it.getString("pause_start_time"),
                                        endTime = it.getString("pause_end_time"),
                                        durationMinutes = it.getObject("pause_duration_minutes")?.let { _ -> it.getInt("pause_duration_minutes") },
                                        availableAfterMinutes = it.getObject("pause_available_after_minutes")?.let { _ -> it.getInt("pause_available_after_minutes") }
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


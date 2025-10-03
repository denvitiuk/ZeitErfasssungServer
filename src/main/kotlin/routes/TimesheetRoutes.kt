// src/main/kotlin/com/yourcompany/zeiterfassung/routes/TimesheetRoutes.kt
package com.yourcompany.zeiterfassung.routes

import com.yourcompany.zeiterfassung.dto.*
import com.yourcompany.zeiterfassung.models.Logs
import com.yourcompany.zeiterfassung.db.Users
import com.yourcompany.zeiterfassung.db.Companies
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.*

@Serializable
private data class ApiErrorTimeSheet(val error: String, val detail: String? = null)

@Serializable
private data class TimesheetMonthsDTO(val months: List<String>)

@Serializable
private data class MonthTotalDTO(val month: String, val totalMinutes: Int)

@Serializable
private data class EmployeeTimesheetEntryDTO(
    val day: Int,
    val start: String? = null,
    val end: String? = null,
    val minutes: Int = 0
)

@Serializable
private data class EmployeeTimesheetDTO(
    val id: Int,
    val name: String,
    val entries: List<EmployeeTimesheetEntryDTO>
)

@Serializable
private data class CompanyMonthsDTO(val months: List<String>)

// Allow only safe TZ literals to be interpolated into SQL
private fun String.isSafeForSqlLiteral(): Boolean =
    this.matches(Regex("""[A-Za-z0-9_./+\-]+"""))

private fun principalCompanyId(pr: JWTPrincipal) = pr.payload.getClaim("companyId").asInt() ?: 0
private fun isAdminForCompany(pr: JWTPrincipal, companyId: Int): Boolean {
    val isCompanyAdmin = pr.payload.getClaim("isCompanyAdmin").asBoolean() ?: false
    val isGlobalAdmin  = pr.payload.getClaim("isGlobalAdmin").asBoolean() ?: false
    val tokenCompanyId = principalCompanyId(pr)
    return isGlobalAdmin || (isCompanyAdmin && tokenCompanyId == companyId)
}

private fun principalUserId(pr: JWTPrincipal): Int? =
    pr.payload.getClaim("id").asInt()
        ?: pr.payload.getClaim("id").asString()?.toIntOrNull()

private fun fetchAvailableMonths(userId: Int, projectId: Int?): List<String> = transaction {
    val out = mutableListOf<String>()
    val sql = buildString {
        append(
            """
            SELECT to_char(date_trunc('month', "timestamp"), 'YYYY-MM') AS ym
            FROM logs
            WHERE user_id = $userId
            """.trimIndent()
        )
        if (projectId != null) append(" AND project_id = $projectId ")
        append(" GROUP BY ym ORDER BY ym DESC")
    }
    exec(sql) { rs ->
        while (rs.next()) out += rs.getString("ym")
    }
    out
}

private fun fetchCompanyMonths(companyId: Int, projectId: Int?): List<String> = transaction {
    val out = mutableListOf<String>()
    val sql = buildString {
        append(
            """
            SELECT to_char(date_trunc('month', l."timestamp"), 'YYYY-MM') AS ym
            FROM logs l
            JOIN users u ON u.id = l.user_id
            WHERE u.company_id = $companyId
            """.trimIndent()
        )
        if (projectId != null) append(" AND l.project_id = $projectId ")
        append(" GROUP BY ym ORDER BY ym DESC")
    }
    exec(sql) { rs ->
        while (rs.next()) out += rs.getString("ym")
    }
    out
}

fun Route.timesheetRoutes() {
    authenticate("bearerAuth") {
        route("/timesheet") {

            // SELF: list of months available for the current user, sorted DESC
            get("/self/months") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                val userId = principalUserId(principal)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("bad_token", "No id in token"))

                val projectId = call.request.queryParameters["projectId"]?.toIntOrNull()

                val months = fetchAvailableMonths(userId, projectId)
                call.respond(HttpStatusCode.OK, TimesheetMonthsDTO(months))
            }

            // ADMIN: list of months for any user of same company
            get("/users/{id}/months") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                val userIdParam = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("id_required"))
                val targetUserId = userIdParam.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("bad_id"))

                val projectId = call.request.queryParameters["projectId"]?.toIntOrNull()

                val companyId = principalCompanyId(principal)
                if (companyId <= 0) return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company"))
                if (!isAdminForCompany(principal, companyId)) return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden"))

                val sameCompany = transaction {
                    Users.slice(Users.companyId)
                        .select { Users.id eq EntityID(targetUserId, Users) }
                        .singleOrNull()?.get(Users.companyId)?.value == companyId
                }
                if (!sameCompany) return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Different company"))

                val months = fetchAvailableMonths(targetUserId, projectId)
                call.respond(HttpStatusCode.OK, TimesheetMonthsDTO(months))
            }

            // SELF
            get("/self") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                val userId = principalUserId(principal)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("bad_token", "No id in token"))

                val monthParam = call.request.queryParameters["month"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("month_required", "Use ?month=YYYY-MM"))

                val tz = ZoneId.of(call.request.queryParameters["tz"] ?: "Europe/Berlin")

                val projectId = call.request.queryParameters["projectId"]?.toIntOrNull()

                val dto = call.buildTimesheet(userId = userId, month = monthParam, tz = tz, projectId = projectId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("no_logs", "No data for month"))

                call.respond(HttpStatusCode.OK, dto)
            }

            // ADMIN: any user of same company
            get("/users/{id}") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                val userIdParam = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("id_required"))
                val targetUserId = userIdParam.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("bad_id"))

                val monthParam = call.request.queryParameters["month"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("month_required", "Use ?month=YYYY-MM"))
                val tz = ZoneId.of(call.request.queryParameters["tz"] ?: "Europe/Berlin")

                val projectId = call.request.queryParameters["projectId"]?.toIntOrNull()

                // проверка: этот юзер из той же компании и caller — админ
                val companyId = principalCompanyId(principal)
                if (companyId <= 0) return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company"))
                if (!isAdminForCompany(principal, companyId)) return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden"))

                val sameCompany = transaction {
                    Users.slice(Users.companyId)
                        .select { Users.id eq EntityID(targetUserId, Users) }
                        .singleOrNull()?.get(Users.companyId)?.value == companyId
                }
                if (!sameCompany) return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Different company"))

                val dto = call.buildTimesheet(userId = targetUserId, month = monthParam, tz = tz, projectId = projectId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("no_logs", "No data for month"))

                call.respond(HttpStatusCode.OK, dto)
            }
        }
    }
}

/**
 * Company-wide months aggregation for admins of the current company.
 * GET /companies/self/months[?projectId=N]
 */
fun Route.companyMonthsRoutes() {
    authenticate("bearerAuth") {
        get("/companies/self/months") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = principalCompanyId(principal)
            if (companyId <= 0) return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company"))
            if (!isAdminForCompany(principal, companyId)) return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden"))

            val projectId = call.request.queryParameters["projectId"]?.toIntOrNull()
            val months = fetchCompanyMonths(companyId, projectId)
            call.respond(HttpStatusCode.OK, CompanyMonthsDTO(months))
        }
    }
}

/**
 * Company employees' timesheets for a given month (admin view).
 * GET /companies/self/employees-timesheet?month=YYYY-MM[&projectId=N][&tz=Europe/Berlin][&includeEmpty=false]
 */
fun Route.companyTimesheetRoutes() {
    authenticate("bearerAuth") {
        get("/companies/self/employees-timesheet") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = principalCompanyId(principal)
            if (companyId <= 0) return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company"))
            if (!isAdminForCompany(principal, companyId)) return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden"))

            val month = call.request.queryParameters["month"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("month_required", "Use ?month=YYYY-MM"))
            val tz = ZoneId.of(call.request.queryParameters["tz"] ?: "Europe/Berlin")
            val projectId = call.request.queryParameters["projectId"]?.toIntOrNull()
            val includeEmpty = call.request.queryParameters["includeEmpty"]?.toBooleanStrictOrNull() ?: false

            // Fetch users of this company
            val users: List<Pair<Int, String>> = transaction {
                Users
                    .slice(Users.id, Users.firstName, Users.lastName)
                    .select { Users.companyId eq EntityID(companyId, Companies) }
                    .orderBy(Users.id to SortOrder.ASC)
                    .map { row ->
                        val id = row[Users.id].value
                        val fn = row[Users.firstName] ?: ""
                        val ln = row[Users.lastName]  ?: ""
                        val full = listOf(fn, ln).filter { it.isNotBlank() }.joinToString(" ")
                        id to (if (full.isNotEmpty()) full else "Mitarbeiter $id")
                    }
            }

            val result = mutableListOf<EmployeeTimesheetDTO>()
            for ((uid, name) in users) {
                val dto = call.buildTimesheet(userId = uid, month = month, tz = tz, projectId = projectId)
                    ?: continue
                val entries = dto.days.map { d ->
                    EmployeeTimesheetEntryDTO(
                        day = d.day,
                        start = d.firstStart,
                        end = d.lastEnd,
                        minutes = d.minutes
                    )
                }
                if (dto.totalMinutes > 0 || includeEmpty) {
                    result += EmployeeTimesheetDTO(id = uid, name = name, entries = entries)
                }
            }

            call.respond(HttpStatusCode.OK, result)
        }
    }
}

/** Основная сборка табеля за месяц. Работает с Logs.timestamp как LocalDateTime. */
private fun ApplicationCall.buildTimesheet(userId: Int, month: String, tz: ZoneId, projectId: Int? = null): TimesheetMonthDTO? {
    // month = "YYYY-MM"
    val parts = month.split("-")
    if (parts.size != 2) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null

    val startZdt = LocalDate.of(y, m, 1).atStartOfDay(tz)
    val endZdt   = startZdt.plusMonths(1)

    val startLocal = startZdt.toLocalDateTime()
    val endLocal   = endZdt.toLocalDateTime()

    // тянем логи в диапазоне
    data class Raw(val action: String, val zdt: ZonedDateTime)
    val rows: List<Raw> = transaction {
        val baseQuery = Logs
            .slice(Logs.action, Logs.timestamp)
            .select { (Logs.userId eq userId) and
                      (Logs.timestamp greaterEq startLocal) and
                      (Logs.timestamp less endLocal) }
        val query = if (projectId != null) {
            baseQuery.andWhere { Logs.projectId eq projectId }
        } else {
            baseQuery
        }
        query.orderBy(Logs.timestamp to SortOrder.ASC)
            .map { row ->
                val ldt = row[Logs.timestamp] // LocalDateTime
                Raw(
                    action = row[Logs.action],
                    zdt = ldt.atZone(tz)
                )
            }
    }

    if (rows.isEmpty()) {
        // сформируем «пустой» табель на 31 день
        val empty = (1..31).map { TimesheetDayDTO(day = it) }
        return TimesheetMonthDTO(userId = userId, month = month, tz = tz.id, days = empty, totalMinutes = 0)
    }

    // Парсинг интервалов in→out
    data class Interval(val start: ZonedDateTime, val end: ZonedDateTime)
    val intervals = mutableListOf<Interval>()
    var openIn: ZonedDateTime? = null
    for (r in rows) {
        when (r.action.lowercase()) {
            "in" -> if (openIn == null) openIn = r.zdt else {
                // два IN подряд — закрываем на момент второго IN
                intervals += Interval(openIn!!, r.zdt)
                openIn = r.zdt
            }
            "out" -> if (openIn != null) {
                if (!r.zdt.isBefore(openIn)) intervals += Interval(openIn!!, r.zdt)
                openIn = null
            }
        }
    }
    // «висячий» IN — обрежем концом месяца
    if (openIn != null) {
        val cut = endZdt.minusNanos(1)
        if (!cut.isBefore(openIn)) intervals += Interval(openIn!!, cut)
    }

    // Режем интервалы по границам суток и суммируем по дню
    val perDayMinutes = IntArray(31) { 0 }
    val firstStartPerDay = arrayOfNulls<LocalTime>(31)
    val lastEndPerDay    = arrayOfNulls<LocalTime>(31)

    fun addDayChunk(day: Int, start: LocalTime, end: LocalTime) {
        val idx = day - 1
        val mins = Duration.between(start, end).toMinutes().toInt().coerceAtLeast(0)
        perDayMinutes[idx] += mins
        if (firstStartPerDay[idx] == null || start.isBefore(firstStartPerDay[idx])) firstStartPerDay[idx] = start
        if (lastEndPerDay[idx] == null || end.isAfter(lastEndPerDay[idx]))     lastEndPerDay[idx] = end
    }

    for (iv in intervals) {
        var curStart = iv.start
        val end = iv.end
        while (curStart.toLocalDate().isBefore(end.toLocalDate())) {
            val endOfDay = curStart.toLocalDate().atTime(23, 59, 59).atZone(tz)
            val day = curStart.dayOfMonth
            addDayChunk(day, curStart.toLocalTime(), endOfDay.toLocalTime())
            curStart = endOfDay.plusSeconds(1)
        }
        val day = end.dayOfMonth
        addDayChunk(day, curStart.toLocalTime(), end.toLocalTime())
    }

    val df = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val days = (1..31).map { d ->
        val mins = perDayMinutes[d - 1]
        val s = firstStartPerDay[d - 1]?.let { df.format(it) }
        val e = lastEndPerDay[d - 1]?.let { df.format(it) }
        TimesheetDayDTO(day = d, firstStart = s, lastEnd = e, minutes = mins)
    }
    val total = perDayMinutes.sum()
    return TimesheetMonthDTO(
        userId = userId,
        month = month,
        tz = tz.id,
        days = days,
        totalMinutes = total
    )
}
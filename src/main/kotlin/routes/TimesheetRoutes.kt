// src/main/kotlin/com/yourcompany/zeiterfassung/routes/TimesheetRoutes.kt
package com.yourcompany.zeiterfassung.routes

import com.yourcompany.zeiterfassung.dto.*
import com.yourcompany.zeiterfassung.models.Logs
import com.yourcompany.zeiterfassung.db.Users
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

private fun principalCompanyId(pr: JWTPrincipal) = pr.payload.getClaim("companyId").asInt() ?: 0
private fun isAdminForCompany(pr: JWTPrincipal, companyId: Int): Boolean {
    val isCompanyAdmin = pr.payload.getClaim("isCompanyAdmin").asBoolean() ?: false
    val isGlobalAdmin  = pr.payload.getClaim("isGlobalAdmin").asBoolean() ?: false
    val tokenCompanyId = principalCompanyId(pr)
    return isGlobalAdmin || (isCompanyAdmin && tokenCompanyId == companyId)
}

fun Route.timesheetRoutes() {
    authenticate("bearerAuth") {
        route("/timesheet") {

            // SELF
            get("/self") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                val userId = principal.payload.getClaim("id").asInt()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("bad_token", "No id in token"))

                val monthParam = call.request.queryParameters["month"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("month_required", "Use ?month=YYYY-MM"))

                val tz = ZoneId.of(call.request.queryParameters["tz"] ?: "Europe/Berlin")

                val dto = call.buildTimesheet(userId = userId, month = monthParam, tz = tz)
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

                val dto = call.buildTimesheet(userId = targetUserId, month = monthParam, tz = tz)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("no_logs", "No data for month"))

                call.respond(HttpStatusCode.OK, dto)
            }
        }
    }
}

/** Основная сборка табеля за месяц. Работает с Logs.timestamp как LocalDateTime. */
private fun ApplicationCall.buildTimesheet(userId: Int, month: String, tz: ZoneId): TimesheetMonthDTO? {
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
        Logs
            .slice(Logs.action, Logs.timestamp)
            .select { (Logs.userId eq userId) and
                      (Logs.timestamp greaterEq startLocal) and
                      (Logs.timestamp less endLocal) }
            .orderBy(Logs.timestamp to SortOrder.ASC)
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
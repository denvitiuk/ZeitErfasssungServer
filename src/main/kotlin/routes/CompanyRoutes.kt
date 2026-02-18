package com.yourcompany.zeiterfassung.routes

import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.exceptions.ExposedSQLException

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

import com.yourcompany.zeiterfassung.models.Company
import com.yourcompany.zeiterfassung.models.CompanyRequest
import com.yourcompany.zeiterfassung.tables.Companies

import com.yourcompany.zeiterfassung.models.PauseSessions
import java.time.*

import com.yourcompany.zeiterfassung.db.Users
import io.ktor.server.application.*
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder

@Serializable
private data class VerifyCompanyCodeRequest(val code: String)

@Serializable
private data class InviteCodeResponse(val code: String)
@Serializable
private data class InviteCodeSetRequest(val code: String)

@Serializable
private data class CompanyNameUpdateRequest(val name: String)
@Serializable
data class ApiError(val error: String, val detail: String? = null)

@Serializable
private data class CompanyMetricsDTO(
    val employees: Int,
    val active_sessions: Int,
    val pause_requests: Int,
    val total_hours_today: Double
)

@Serializable
private data class CompanyUserDTO(
    val id: Int,
    val name: String,
    val birth_date: String? = null,
    val approved: Boolean
)

@Serializable
private data class HoursSeriesDTO(
    val series: List<Double>
)

@Serializable
private data class CompanyPauseDTO(
    val id: Int,
    val userId: Int,
    val name: String,
    val startedAt: String,
    val endedAt: String? = null,
    val isActive: Boolean,
    val minutes: Int
)

@Serializable
private data class CompanyCurrentSessionDTO(
    val userId: Int,
    val name: String,
    val startedAt: String,
    val projectId: Int? = null,
    val minutes: Int
)

@Serializable
data class SeatsStatusDTO(
    val companyId: Int,
    val limit: Int,
    val used: Int
)

// === Metrics helpers =========================================================
// Count open work sessions (last action == 'in'). If projectId is null/<=0 —
// count across all projects (including NULL project_id).
private fun countActiveSessions(companyId: Int, projectId: Int?): Int = transaction {
    val projectCond = if (projectId != null && projectId > 0) {
        " AND l.project_id = $projectId "
    } else {
        ""
    }
    var count = 0
    val sql = """
        SELECT COUNT(*) AS c FROM (
            SELECT DISTINCT ON (l.user_id) l.user_id, l.action
            FROM logs l
            JOIN users u ON u.id = l.user_id
            WHERE u.company_id = $companyId
              $projectCond
            ORDER BY l.user_id, l."timestamp" DESC
        ) t
        WHERE lower(t.action) = 'in'
    """.trimIndent()
    exec(sql) { rs -> if (rs.next()) count = rs.getInt("c") }
    count
}

// Count active (not finished) pause sessions for the company.
private fun countActivePauseRequests(companyId: Int): Int = transaction {
    (PauseSessions innerJoin Users)
        .slice(PauseSessions.id)
        .select {
            (Users.companyId eq EntityID(companyId, Companies)) and
            (PauseSessions.isActive eq true)
        }
        .count()
        .toInt()
}

private fun String.isValidInvite(): Boolean =
    this.length in 8..16 && this.all { it.isLetterOrDigit() }

private fun normalizeCode(raw: String): String = raw.trim().uppercase()

private fun normalizeCompanyName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

private fun isValidCompanyName(name: String): Boolean {
    if (name.length !in 2..80) return false
    // Allow Unicode letters/digits/space and a small set of punctuation used in company names
    val allowedPunct = setOf('-', '&', '.', '\'', '(', ')', '_', '/', ',', ':')
    return name.all { ch ->
        ch.isLetterOrDigit() || ch.isWhitespace() || allowedPunct.contains(ch)
    }
}

private fun isMemberOfCompany(principal: JWTPrincipal, companyId: Int): Boolean {
    val tokenCompanyId = principal.payload.getClaim("companyId").asInt() ?: 0
    return tokenCompanyId == companyId
}

private fun isAdminForCompany(principal: JWTPrincipal, companyId: Int): Boolean {
    val isCompanyAdmin = principal.payload.getClaim("isCompanyAdmin").asBoolean() ?: false
    val isGlobalAdmin  = principal.payload.getClaim("isGlobalAdmin").asBoolean() ?: false
    val tokenCompanyId = principal.payload.getClaim("companyId").asInt() ?: 0
    return isGlobalAdmin || (isCompanyAdmin && tokenCompanyId == companyId)
}

// Simple in-memory rate limiter for invite-code verification (per remote host)
private object InviteCodeRateLimiter {
    private val lock = Any()
    // store timestamps (ms) of attempts per key within a sliding window
    private val buckets = mutableMapOf<String, MutableList<Long>>()
    private const val WINDOW_MS = 60_000L
    private const val MAX_ATTEMPTS = 5

    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            val list = buckets.getOrPut(key) { mutableListOf() }
            // purge old
            val cutoff = now - WINDOW_MS
            list.removeAll { it < cutoff }
            if (list.size >= MAX_ATTEMPTS) return false
            list.add(now)
            return true
        }
    }
}

fun Route.companiesRoutes() {
    route("/companies") {

        // GET /companies?name=...
        get {
            try {
                val inviteFilterRaw = call.request.queryParameters["invite_code"]
                val nameFilter = call.request.queryParameters["name"]
                val inviteFilter = inviteFilterRaw?.let { normalizeCode(it) }
                val companies = transaction {
                    val base = Companies.slice(Companies.id, Companies.name, Companies.inviteCode, Companies.createdAt)
                    val query = when {
                        !inviteFilter.isNullOrBlank() -> base.select { Companies.inviteCode eq inviteFilter }
                        !nameFilter.isNullOrBlank() -> base.select { Companies.name like "%${nameFilter}%" }
                        else -> base.selectAll()
                    }
                    query.map {
                        Company(
                            it[Companies.id].value,
                            it[Companies.name],
                            it[Companies.inviteCode],
                            it[Companies.createdAt].toString()
                        )
                    }
                }
                call.respond(HttpStatusCode.OK, companies)
            } catch (e: Exception) {
                call.application.log.error("Failed to fetch companies", e)
                call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
            }
        }

        // POST /companies/verify-code — validate a company code and return company details
        post("/verify-code") {
            try {
                val clientKey =
                    // Respect common proxy headers first
                    call.request.headers["X-Forwarded-For"]?.split(',')?.firstOrNull()?.trim()
                        ?: call.request.headers["X-Real-IP"]
                        // Fallback to Host header (domain:port)
                        ?: call.request.headers[HttpHeaders.Host]
                        // Last resort
                        ?: "unknown"
                if (!InviteCodeRateLimiter.allow(clientKey)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited", "Too many attempts. Try again in a minute"))
                }

                val req = call.receive<VerifyCompanyCodeRequest>()
                val code = normalizeCode(req.code)
                if (code.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("code_required", "Provide non-empty company code")
                    )
                }

                val row = transaction {
                    Companies
                        .slice(Companies.id, Companies.name, Companies.inviteCode, Companies.createdAt)
                        .select { Companies.inviteCode eq code }
                        .singleOrNull()
                } ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_code", "No company found for the provided code")
                )

                val company = Company(
                    row[Companies.id].value,
                    row[Companies.name],
                    row[Companies.inviteCode],
                    row[Companies.createdAt].toString()
                )
                call.respond(HttpStatusCode.OK, company)
            } catch (e: ContentTransformationException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_request", "Body must be JSON: {\"code\": \"...\"}")
                )
            } catch (e: Exception) {
                call.application.log.error("verify-code failed", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiError("internal_error")
                )
            }
        }

        // GET /companies/by-code/{code} — lookup by invite code via path param
        get("/by-code/{code}") {
            val raw = call.parameters["code"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, ApiError("code_required")
            )
            val code = normalizeCode(raw)
            if (code.isEmpty()) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("code_required"))
            }

            val row = transaction {
                Companies
                    .slice(Companies.id, Companies.name, Companies.inviteCode, Companies.createdAt)
                    .select { Companies.inviteCode eq code }
                    .singleOrNull()
            } ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ApiError("invalid_code", "No company found for the provided code")
            )

            val company = Company(
                row[Companies.id].value,
                row[Companies.name],
                row[Companies.inviteCode],
                row[Companies.createdAt].toString()
            )
            call.respond(HttpStatusCode.OK, company)
        }

        authenticate("bearerAuth") {
            // ---- SELF scope: companyId taken from JWT ----
            route("/self") {
                // GET /companies/self/seats — current seat limit & used count (admin only)
                get("/seats") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            ApiError("unauthorized", "Missing token")
                        )

                    val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                    if (companyId <= 0) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("no_company", "Token has no companyId")
                        )
                    }
                    if (!isAdminForCompany(principal, companyId)) {
                        return@get call.respond(
                            HttpStatusCode.Forbidden,
                            ApiError("forbidden", "Admin rights required for this company")
                        )
                    }

                    try {
                        var limit = 5
                        var used  = 0
                        transaction {
                            // Limit from entitlements or fallback to companies.max_seats or 5
                            exec(
                                """
                                SELECT COALESCE(vc.seats_limit, c.max_seats, 5) AS limit
                                  FROM companies c
                             LEFT JOIN v_company_entitlements vc ON vc.company_id = c.id
                                 WHERE c.id = $companyId
                                """.trimIndent()
                            ) { rs ->
                                if (rs.next()) {
                                    limit = rs.getInt("limit")
                                }
                            }

                            // Used = count of distinct users present in any project of the company
                            exec(
                                """
                                SELECT COUNT(DISTINCT pm.user_id) AS used
                                  FROM project_members pm
                                  JOIN projects p ON p.id = pm.project_id
                                 WHERE p.company_id = $companyId
                                """.trimIndent()
                            ) { rs ->
                                if (rs.next()) {
                                    used = rs.getInt("used")
                                }
                            }
                        }

                        call.respond(HttpStatusCode.OK, SeatsStatusDTO(companyId, limit, used))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to fetch seats status for company $companyId", e)
                        call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
                    }
                }

                // GET /companies/self/metrics — company-scoped metrics (admin only)
                get("/metrics") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                    val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                    if (companyId <= 0) {
                        return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
                    }
                    if (!isAdminForCompany(principal, companyId)) {
                        return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
                    }

                    // optional project filter: ?projectId=8 ; 0 or missing = all
                    val projectId = call.request.queryParameters["projectId"]?.toIntOrNull()

                    val employeesCount = transaction {
                        Users.select { Users.companyId eq EntityID(companyId, Companies) }.count()
                    }.toInt()

                    val activeSessions = countActiveSessions(companyId, projectId)
                    val activePauses   = countActivePauseRequests(companyId)

                    val metrics = CompanyMetricsDTO(
                        employees = employeesCount,
                        active_sessions = activeSessions,
                        pause_requests = activePauses,
                        total_hours_today = 0.0 // TODO: implement simple same-day sum later
                    )
                    call.respond(HttpStatusCode.OK, metrics)
                }

                // GET /companies/self/users — list employees of this company (admin only)
                get("/users") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                    val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                    if (companyId <= 0) {
                        return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
                    }
                    if (!isAdminForCompany(principal, companyId)) {
                        return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
                    }

                    val list = transaction {
                        Users
                            .slice(Users.id, Users.firstName, Users.lastName, Users.birthDate, Users.phoneVerified)
                            .select { Users.companyId eq EntityID(companyId, Companies) }
                            .orderBy(Users.lastName to SortOrder.ASC)
                            .map {
                                CompanyUserDTO(
                                    id = it[Users.id].value,
                                    name = "${it[Users.firstName]} ${it[Users.lastName]}",
                                    birth_date = it[Users.birthDate].toString(),
                                    approved = it[Users.phoneVerified]
                                )
                            }
                    }
                    call.respond(HttpStatusCode.OK, list)
                }
                // GET /companies/self/hours?period=daily|weekly — sample time series for charts (admin only)
                get("/hours") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                    val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                    if (companyId <= 0) {
                        return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
                    }
                    if (!isAdminForCompany(principal, companyId)) {
                        return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
                    }

                    val period = call.request.queryParameters["period"]?.lowercase() ?: "daily"
                    val series = if (period == "weekly")
                        listOf(400.0, 450.0, 420.0, 500.0, 550.0, 530.0, 600.0)
                    else
                        listOf(60.0, 50.0, 30.0, 70.0, 80.0, 65.0, 100.0)

                    call.respond(HttpStatusCode.OK, HoursSeriesDTO(series))
                }
                // GET /companies/self/current-sessions — returns all open work sessions (last action == 'in') for the company
                get("/current-sessions") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                    val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                    if (companyId <= 0) {
                        return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
                    }
                    if (!isAdminForCompany(principal, companyId)) {
                        return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
                    }

                    val tz = try {
                        ZoneId.of(call.request.queryParameters["tz"] ?: "Europe/Berlin")
                    } catch (_: Exception) { ZoneId.of("Europe/Berlin") }

                    // optional project filter: ?projectId=8 ; 0 or missing = all
                    val projectIdParam = call.request.queryParameters["projectId"]?.toIntOrNull()
                    val projectCond = if (projectIdParam != null && projectIdParam > 0) {
                        " AND l.project_id = $projectIdParam "
                    } else {
                        ""
                    }

                    data class Row(
                        val userId: Int,
                        val first: String,
                        val last: String,
                        val started: LocalDateTime,
                        val projectId: Int?
                    )

                    val rows: List<Row> = transaction {
                        val out = mutableListOf<Row>()
                        val sql = """
                            SELECT DISTINCT ON (l.user_id)
                                   l.user_id,
                                   l.action,
                                   l.timestamp,
                                   l.project_id,
                                   u.first_name,
                                   u.last_name
                            FROM logs l
                            JOIN users u ON u.id = l.user_id
                            WHERE u.company_id = $companyId
                              $projectCond
                            ORDER BY l.user_id, l.timestamp DESC
                        """.trimIndent()
                        exec(sql) { rs ->
                            while (rs.next()) {
                                val action = rs.getString("action")?.lowercase()
                                if (action == "in") {
                                    val uid = rs.getInt("user_id")
                                    val ts  = rs.getTimestamp("timestamp").toLocalDateTime()
                                    val pidObj = rs.getObject("project_id")
                                    val pid = when (pidObj) {
                                        null -> null
                                        is Number -> pidObj.toInt()
                                        else -> null
                                    }
                                    val fn = rs.getString("first_name") ?: ""
                                    val ln = rs.getString("last_name") ?: ""
                                    out += Row(uid, fn, ln, ts, pid)
                                }
                            }
                        }
                        out
                    }

                    val now = ZonedDateTime.now(tz)
                    val payload = rows.map { r ->
                        val startZ = r.started.atZone(tz)
                        val mins = Duration.between(startZ, now).toMinutes().toInt().coerceAtLeast(0)
                        CompanyCurrentSessionDTO(
                            userId = r.userId,
                            name = "${r.first} ${r.last}".trim(),
                            startedAt = startZ.toOffsetDateTime().toString(),
                            projectId = r.projectId,
                            minutes = mins
                        )
                    }

                    call.respond(HttpStatusCode.OK, payload)
                }

                // GET /companies/self/pause-sessions — returns all inactive pause sessions for the company
                get("/pause-sessions") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                    val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                    if (companyId <= 0) {
                        return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
                    }
                    if (!isAdminForCompany(principal, companyId)) {
                        return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
                    }

                    val tz = try {
                        ZoneId.of(call.request.queryParameters["tz"] ?: "Europe/Berlin")
                    } catch (_: Exception) { ZoneId.of("Europe/Berlin") }

                    data class Row(
                        val id: Int,
                        val uid: Int,
                        val first: String,
                        val last: String,
                        val started: LocalDateTime,
                        val ended: LocalDateTime?,
                        val active: Boolean
                    )

                    val rows: List<Row> = transaction {
                        val q = (PauseSessions innerJoin Users)
                            .slice(
                                PauseSessions.id,
                                PauseSessions.userId,
                                Users.firstName,
                                Users.lastName,
                                PauseSessions.startedAt,
                                PauseSessions.endedAt,
                                PauseSessions.isActive
                            )
                            .select {
                                (Users.companyId eq EntityID(companyId, Companies)) and
                                (PauseSessions.isActive eq false)
                            }
                            .orderBy(PauseSessions.startedAt to SortOrder.DESC)

                        q.map { r ->
                            Row(
                                id = r[PauseSessions.id].value,
                                uid = r[PauseSessions.userId],
                                first = r[Users.firstName] ?: "",
                                last  = r[Users.lastName]  ?: "",
                                started = r[PauseSessions.startedAt],
                                ended = r[PauseSessions.endedAt],
                                active = r[PauseSessions.isActive]
                            )
                        }
                    }

                    val now = ZonedDateTime.now(tz)
                    val payload = rows.map { r ->
                        val startZ = r.started.atZone(tz)
                        val endZ   = r.ended?.atZone(tz)
                        val endForCalc = endZ ?: now
                        val mins = Duration.between(startZ, endForCalc).toMinutes().toInt().coerceAtLeast(0)
                        CompanyPauseDTO(
                            id = r.id,
                            userId = r.uid,
                            name = "${r.first} ${r.last}".trim(),
                            startedAt = startZ.toOffsetDateTime().toString(),
                            endedAt = endZ?.toOffsetDateTime()?.toString(),
                            isActive = r.active,
                            minutes = mins
                        )
                    }

                    call.respond(HttpStatusCode.OK, payload)
                }
                // PUT /companies/self/name — update company name (admin only)
                put("/name") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@put call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                    val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                    if (companyId <= 0) {
                        return@put call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
                    }
                    if (!isAdminForCompany(principal, companyId)) {
                        return@put call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
                    }

                    val body = try {
                        call.receive<CompanyNameUpdateRequest>()
                    } catch (e: Exception) {
                        return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Body must be {\"name\":\"...\"}"))
                    }

                    val desiredRaw = body.name
                    val desired = normalizeCompanyName(desiredRaw)
                    if (!isValidCompanyName(desired)) {
                        return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid_name", "Name must be 2–80 chars; letters/digits/spaces and - & . ' ( ) _ / , : allowed"))
                    }

                    // Read current name and bail out early if unchanged (idempotent behavior)
                    val currentName: String = transaction {
                        Companies
                            .slice(Companies.name)
                            .select { Companies.id eq org.jetbrains.exposed.dao.id.EntityID(companyId, Companies) }
                            .map { it[Companies.name] }
                            .singleOrNull()
                    } ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))

                    if (currentName == desired) {
                        // No-op update, still return current company payload
                        val company = transaction {
                            Companies
                                .slice(Companies.id, Companies.name, Companies.inviteCode, Companies.createdAt)
                                .select { Companies.id eq org.jetbrains.exposed.dao.id.EntityID(companyId, Companies) }
                                .map {
                                    Company(
                                        it[Companies.id].value,
                                        it[Companies.name],
                                        it[Companies.inviteCode],
                                        it[Companies.createdAt].toString()
                                    )
                                }
                                .single()
                        }
                        return@put call.respond(HttpStatusCode.OK, company)
                    }

                    try {
                        val updated = transaction {
                            Companies.update({ Companies.id eq org.jetbrains.exposed.dao.id.EntityID(companyId, Companies) }) {
                                it[name] = desired
                            }
                        }
                        if (updated == 0) {
                            return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))
                        }

                        val company = transaction {
                            Companies
                                .slice(Companies.id, Companies.name, Companies.inviteCode, Companies.createdAt)
                                .select { Companies.id eq org.jetbrains.exposed.dao.id.EntityID(companyId, Companies) }
                                .map {
                                    Company(
                                        it[Companies.id].value,
                                        it[Companies.name],
                                        it[Companies.inviteCode],
                                        it[Companies.createdAt].toString()
                                    )
                                }
                                .single()
                        }
                        call.respond(HttpStatusCode.OK, company)
                    } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                        // 23505: unique violation (only if a unique index exists on name)
                        if (e.sqlState == "23505") {
                            return@put call.respond(HttpStatusCode.Conflict, ApiError("name_taken", "Company name already in use"))
                        }
                        return@put call.respond(HttpStatusCode.InternalServerError, ApiError("update_failed", e.message))
                    } catch (e: Exception) {
                        call.application.log.error("Error updating company name for self company $companyId", e)
                        return@put call.respond(HttpStatusCode.InternalServerError, ApiError("update_failed", e.message))
                    }
                }

                // GET /companies/self/invite-code — return current code
                get("/invite-code") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            ApiError("unauthorized", "Missing token")
                        )

                    val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                    if (companyId <= 0) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("no_company", "Token has no companyId")
                        )
                    }

                    val code = transaction {
                        Companies
                            .slice(Companies.inviteCode)
                            .select { Companies.id eq org.jetbrains.exposed.dao.id.EntityID(companyId, Companies) }
                            .map { it[Companies.inviteCode] }
                            .singleOrNull()
                    } ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))

                    call.respond(HttpStatusCode.OK, InviteCodeResponse(code))
                }

                // POST /companies/self/invite-code/rotate — generate new random code (admin only)
                post("/invite-code/rotate") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            ApiError("unauthorized", "Missing token")
                        )

                    val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                    if (companyId <= 0) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("no_company", "Token has no companyId")
                        )
                    }
                    if (!isAdminForCompany(principal, companyId)) {
                        return@post call.respond(
                            HttpStatusCode.Forbidden,
                            ApiError("forbidden", "Admin rights required for this company")
                        )
                    }

                    val newCode = normalizeCode(java.util.UUID.randomUUID().toString().replace("-", "").take(16))
                    val updated = transaction {
                        Companies.update({
                            Companies.id eq org.jetbrains.exposed.dao.id.EntityID(
                                companyId,
                                Companies
                            )
                        }) {
                            it[inviteCode] = newCode
                        }
                    }
                    if (updated == 0) {
                        return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))
                    }
                    call.respond(HttpStatusCode.OK, InviteCodeResponse(newCode))
                }

                // PUT /companies/self/invite-code — set custom code (admin only)
                put("/invite-code") {
                    val principal = call.principal<JWTPrincipal>()
                        ?: return@put call.respond(
                            HttpStatusCode.Unauthorized,
                            ApiError("unauthorized", "Missing token")
                        )

                    val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                    if (companyId <= 0) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("no_company", "Token has no companyId")
                        )
                    }
                    if (!isAdminForCompany(principal, companyId)) {
                        return@put call.respond(
                            HttpStatusCode.Forbidden,
                            ApiError("forbidden", "Admin rights required for this company")
                        )
                    }

                    val body = try {
                        call.receive<InviteCodeSetRequest>()
                    } catch (e: Exception) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("invalid_request", "Body must be {\"code\":\"...\"}")
                        )
                    }
                    val desired = normalizeCode(body.code)
                    if (!desired.isValidInvite()) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("invalid_code_format", "Use 8–16 alphanumeric characters")
                        )
                    }

                try {
                    val updated = transaction {
                        Companies.update({
                            Companies.id eq org.jetbrains.exposed.dao.id.EntityID(
                                companyId,
                                Companies
                            )
                        }) {
                            it[inviteCode] = desired
                        }
                    }
                    if (updated == 0) {
                        return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))
                    }
                    call.respond(HttpStatusCode.OK, InviteCodeResponse(desired))
                } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                    if (e.sqlState == "23505") {
                        return@put call.respond(
                            HttpStatusCode.Conflict,
                            ApiError("code_taken", "Invite code already in use")
                        )
                    }
                    return@put call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiError("update_failed", e.message)
                    )
                } catch (e: Exception) {
                    call.application.log.error("Error updating invite code for self company $companyId", e)
                    return@put call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiError("update_failed", e.message)
                    )
                }
                }
            }

            // GET /companies/{id}/invite-code — return current code
            get("/{id}/invite-code") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                val idParam = call.parameters["id"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_request", "Missing company id")
                    )
                val companyId = idParam.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))

                if (!(isMemberOfCompany(principal, companyId) || isAdminForCompany(principal, companyId))) {
                    return@get call.respond(
                        HttpStatusCode.Forbidden,
                        ApiError(
                            "forbidden",
                            "You must belong to this company (or be an admin) to view its invite code"
                        )
                    )
                }

                val code = transaction {
                    Companies
                        .slice(Companies.inviteCode)
                        .select { Companies.id eq org.jetbrains.exposed.dao.id.EntityID(companyId, Companies) }
                        .map { it[Companies.inviteCode] }
                        .singleOrNull()
                } ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))

                call.respond(HttpStatusCode.OK, InviteCodeResponse(code))
            }

            // POST /companies/{id}/invite-code/rotate — generate new random code
            post("/{id}/invite-code/rotate") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                val idParam = call.parameters["id"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_request", "Missing company id")
                    )
                val companyId = idParam.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))

                if (!isAdminForCompany(principal, companyId)) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        ApiError("forbidden", "Admin rights required for this company")
                    )
                }

                val newCode = normalizeCode(java.util.UUID.randomUUID().toString().replace("-", "").take(16))
                val updated = transaction {
                    Companies.update({ Companies.id eq org.jetbrains.exposed.dao.id.EntityID(companyId, Companies) }) {
                        it[inviteCode] = newCode
                    }
                }
                if (updated == 0) {
                    return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))
                }
                call.respond(HttpStatusCode.OK, InviteCodeResponse(newCode))
            }

            // PUT /companies/{id}/invite-code — set custom code
            put("/{id}/invite-code") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                val idParam = call.parameters["id"]
                    ?: return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_request", "Missing company id")
                    )
                val companyId = idParam.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))

                if (!isAdminForCompany(principal, companyId)) {
                    return@put call.respond(
                        HttpStatusCode.Forbidden,
                        ApiError("forbidden", "Admin rights required for this company")
                    )
                }

                val body = try {
                    call.receive<InviteCodeSetRequest>()
                } catch (e: Exception) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_request", "Body must be {\"code\":\"...\"}")
                    )
                }
                val desired = normalizeCode(body.code)
                if (!desired.isValidInvite()) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_code_format", "Use 8–16 alphanumeric characters")
                    )
                }

                try {
                    val updated = transaction {
                        Companies.update({
                            Companies.id eq org.jetbrains.exposed.dao.id.EntityID(
                                companyId,
                                Companies
                            )
                        }) {
                            it[inviteCode] = desired
                        }
                    }
                    if (updated == 0) {
                        return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Company not found"))
                    }
                    call.respond(HttpStatusCode.OK, InviteCodeResponse(desired))
                } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
                    if (e.sqlState == "23505") {
                        return@put call.respond(
                            HttpStatusCode.Conflict,
                            ApiError("code_taken", "Invite code already in use")
                        )
                    }
                    return@put call.respond(HttpStatusCode.InternalServerError, ApiError("update_failed", e.message))
                } catch (e: Exception) {
                    call.application.log.error("Error updating invite code for company $companyId", e)
                    return@put call.respond(HttpStatusCode.InternalServerError, ApiError("update_failed", e.message))
                }
            }
        }

        // GET /companies/{id}
        get("/{id}") {
            try {
                val idParam = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("id_required")
                )
                val id = idParam.toIntOrNull() ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("id_must_be_int")
                )
                val company = transaction {
                    Companies
                        .slice(Companies.id, Companies.name, Companies.inviteCode, Companies.createdAt)
                        .select { Companies.id eq org.jetbrains.exposed.dao.id.EntityID(id, Companies) }
                        .map {
                            Company(
                                it[Companies.id].value,
                                it[Companies.name],
                                it[Companies.inviteCode],
                                it[Companies.createdAt].toString()
                            )
                        }
                        .singleOrNull()
                } ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("company_not_found"))

                call.respond(HttpStatusCode.OK, company)
            } catch (e: Exception) {
                call.application.log.error("Failed to fetch company by id", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiError("internal_error")
                )
            }
        }

        // POST /companies — create company.
        // Allowed for:
        //  - global admin
        //  - a user who is not yet attached to any company (companyId==0/null) => becomes company admin
        authenticate("bearerAuth") {
            // Insert after /self block, before invite-code endpoints
            post {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

                val isGlobalAdmin = principal.payload.getClaim("isGlobalAdmin").asBoolean() ?: false

                // user id is stored in token as "id" (sometimes string)
                val userId: Int = runCatching { principal.payload.getClaim("id")?.asString()?.toInt() }.getOrNull()
                    ?: runCatching { principal.payload.getClaim("id")?.asInt() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Token has no user id"))

                // companyId from token (0 means not in company yet)
                val tokenCompanyId = principal.payload.getClaim("companyId").asInt() ?: 0

                if (!isGlobalAdmin && tokenCompanyId > 0) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        ApiError("forbidden", "You already belong to a company")
                    )
                }

                try {
                    val req = call.receive<CompanyRequest>()
                    val normalizedName = normalizeCompanyName(req.name)
                    if (!isValidCompanyName(normalizedName)) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(
                                "invalid_name",
                                "Name must be 2–80 chars; letters/digits/spaces and - & . ' ( ) _ / , : allowed"
                            )
                        )
                    }

                    val newCompany = transaction {
                        // For non-global-admin: double-check DB state (prevents capture if token is stale)
                        if (!isGlobalAdmin) {
                            val dbCompanyId = Users
                                .slice(Users.companyId)
                                .select { Users.id eq EntityID(userId, Users) }
                                .map { it[Users.companyId]?.value ?: 0 }
                                .singleOrNull() ?: 0

                            if (dbCompanyId > 0) {
                                throw IllegalStateException("user_already_in_company")
                            }
                        }

                        val newCode = normalizeCode(java.util.UUID.randomUUID().toString().replace("-", "").take(16))

                        val id = Companies.insert {
                            it[Companies.name] = normalizedName
                            it[Companies.inviteCode] = newCode
                        } get Companies.id

                        // Attach creator as company admin (only for the self-registration flow)
                        if (!isGlobalAdmin) {
                            Users.update({ Users.id eq EntityID(userId, Users) }) {
                                it[companyId] = EntityID(id.value, Companies)
                                it[isCompanyAdmin] = true
                            }
                        }

                        Companies.select { Companies.id eq id }
                            .map {
                                Company(
                                    it[Companies.id].value,
                                    it[Companies.name],
                                    it[Companies.inviteCode],
                                    it[Companies.createdAt].toString()
                                )
                            }
                            .singleOrNull() ?: throw IllegalStateException("Company not found after insert")
                    }

                    call.respond(HttpStatusCode.Created, newCompany)

                } catch (e: IllegalStateException) {
                    if (e.message == "user_already_in_company") {
                        return@post call.respond(
                            HttpStatusCode.Forbidden,
                            ApiError("forbidden", "You already belong to a company")
                        )
                    }
                    call.application.log.error("Unexpected state on company create", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
                } catch (e: ContentTransformationException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", "Invalid request format"))
                } catch (e: ExposedSQLException) {
                    if (e.sqlState == "23505") {
                        return@post call.respond(HttpStatusCode.Conflict, ApiError("company_name_already_exists"))
                    }
                    call.application.log.error("Database error on company create", e)
                    call.respond(HttpStatusCode.BadRequest, ApiError("db_error"))
                } catch (e: Exception) {
                    call.application.log.error("Unexpected error on company create", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
                }
            }
        }
    }
}

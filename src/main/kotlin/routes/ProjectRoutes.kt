
package com.yourcompany.zeiterfassung.routes

import com.yourcompany.zeiterfassung.db.ProjectMembers
import com.yourcompany.zeiterfassung.db.Projects
import com.yourcompany.zeiterfassung.db.Users
import com.yourcompany.zeiterfassung.tables.Companies
import com.yourcompany.zeiterfassung.dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

// ---------------------------------------------------------------------------
// Helpers (JWT claims & error format)
// ---------------------------------------------------------------------------

@Serializable
data class ApiErrorProject(val error: String, val detail: String? = null)

@Serializable
data class StandortDTO(
    val projectId: Int,
    val lat: Double,
    val lng: Double,
    val radius: Int = 300,
    val location: String? = null
)

@Serializable
data class SeatLimitExceededError(
    val error: String = "seat_limit_reached",
    val used: Int,
    val limit: Int
)

private fun principalCompanyId(principal: JWTPrincipal): Int =
    principal.payload.getClaim("companyId").asInt() ?: 0

private fun isAdminForCompany(principal: JWTPrincipal, companyId: Int): Boolean {
    val isCompanyAdmin = principal.payload.getClaim("isCompanyAdmin").asBoolean() ?: false
    val isGlobalAdmin  = principal.payload.getClaim("isGlobalAdmin").asBoolean() ?: false
    val tokenCompanyId = principalCompanyId(principal)
    return isGlobalAdmin || (isCompanyAdmin && tokenCompanyId == companyId)
}

private fun requireSameCompanyOr404(projectId: Int, expectedCompanyId: Int): ResultRow? = transaction {
    Projects
        .slice(Projects.id, Projects.companyId, Projects.title, Projects.description, Projects.location,
               Projects.lat, Projects.lng, Projects.createdAt, Projects.updatedAt)
        .select { Projects.id eq EntityID(projectId, Projects) }
        .singleOrNull()
        ?.takeIf { it[Projects.companyId].value == expectedCompanyId }
}

private fun ResultRow.toProjectDTO(membersCount: Int? = null): ProjectDTO = ProjectDTO(
    id          = this[Projects.id].value,
    companyId   = this[Projects.companyId].value,
    title       = this[Projects.title],
    description = this[Projects.description],
    location    = this[Projects.location],
    lat         = this[Projects.lat],
    lng         = this[Projects.lng],
    createdAt   = this[Projects.createdAt].toString(),
    updatedAt   = this[Projects.updatedAt].toString(),
    membersCount= membersCount
)

private fun ResultRow.toProjectListItemDTO(membersCount: Int? = null): ProjectListItemDTO = ProjectListItemDTO(
    id          = this[Projects.id].value,
    companyId   = this[Projects.companyId].value,
    title       = this[Projects.title],
    location    = this[Projects.location],
    membersCount= membersCount,
    createdAt   = this[Projects.createdAt].toString(),
    updatedAt   = this[Projects.updatedAt].toString()
)

private fun userName(first: String?, last: String?): String =
    listOfNotNull(first?.takeIf { it.isNotBlank() }, last?.takeIf { it.isNotBlank() }).joinToString(" ").trim()

private fun membersForProject(projectEntityId: EntityID<Int>): List<ProjectMemberDTO> = transaction {
    (ProjectMembers innerJoin Users)
        .slice(
            ProjectMembers.userId,
            ProjectMembers.role,
            ProjectMembers.joinedAt,
            Users.firstName,
            Users.lastName
        )
        .select { ProjectMembers.projectId eq projectEntityId }
        .orderBy(Users.lastName to SortOrder.ASC, Users.firstName to SortOrder.ASC)
        .map {
            ProjectMemberDTO(
                userId   = it[ProjectMembers.userId].value,
                name     = userName(it[Users.firstName], it[Users.lastName]),
                role     = it[ProjectMembers.role].toInt(),
                joinedAt = it[ProjectMembers.joinedAt].toString()
            )
        }
}

private fun parseUsedLimitFromThrowable(t: Throwable): Pair<Int, Int>? {
    var cur: Throwable? = t
    val re = Regex("used=(\\d+)\\s+limit=(\\d+)", RegexOption.IGNORE_CASE)
    while (cur != null) {
        val msg = cur.message ?: ""
        val m = re.find(msg)
        if (m != null) {
            val used = m.groupValues[1].toIntOrNull()
            val limit = m.groupValues[2].toIntOrNull()
            if (used != null && limit != null) return used to limit
        }
        cur = cur.cause
    }
    return null
}

private fun companySeatsStatus(companyId: Int): Pair<Int, Int> = transaction {
    var limit: Int? = null
    exec("SELECT seats_limit FROM v_company_entitlements WHERE company_id = $companyId LIMIT 1") { rs ->
        if (rs.next()) {
            limit = rs.getInt(1)
            if (rs.wasNull()) limit = null
        }
    }
    if (limit == null) {
        exec("SELECT max_seats FROM companies WHERE id = $companyId") { rs ->
            if (rs.next()) {
                limit = rs.getInt(1)
                if (rs.wasNull()) limit = null
            }
        }
    }
    if (limit == null) limit = 5

    var used = 0
    exec(
        """
        SELECT COUNT(DISTINCT pm.user_id) AS c
        FROM project_members pm
        JOIN projects p ON p.id = pm.project_id
        WHERE p.company_id = $companyId
        """.trimIndent()
    ) { rs ->
        if (rs.next()) used = rs.getInt("c")
    }
    used to (limit ?: 5)
}

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

fun Route.projectsRoutes() {
    authenticate("bearerAuth") {
        route("/projects") {

            // GET /projects/self — list projects of current company
            get("/self") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiErrorProject("unauthorized", "Missing token"))
                val companyId = principalCompanyId(principal)
                if (companyId <= 0)
                    return@get call.respond(HttpStatusCode.BadRequest, ApiErrorProject("no_company", "Token has no companyId"))

                val q = call.request.queryParameters["q"]?.trim()?.lowercase()

                val items = transaction {
                    val base = Projects
                        .slice(Projects.id, Projects.companyId, Projects.title, Projects.location, Projects.createdAt, Projects.updatedAt)
                        .select { Projects.companyId eq EntityID(companyId, Companies) }
                        .let { query ->
                            if (!q.isNullOrBlank()) query.andWhere { Projects.title.lowerCase() like "%${q}%" } else query
                        }
                        .orderBy(Projects.createdAt, SortOrder.DESC)
                        .toList()

                    val baseIds = base.map { it[Projects.id] }
                    val countsByProject: Map<Int, Int> = if (baseIds.isEmpty()) {
                        emptyMap()
                    } else {
                        ProjectMembers
                            .slice(ProjectMembers.projectId, ProjectMembers.userId.count())
                            .select { ProjectMembers.projectId inList baseIds }
                            .groupBy(ProjectMembers.projectId)
                            .associate { row ->
                                row[ProjectMembers.projectId].value to row[ProjectMembers.userId.count()].toInt()
                            }
                    }

                    base.map { row ->
                        val count = countsByProject[row[Projects.id].value] ?: 0
                        row.toProjectListItemDTO(membersCount = count)
                    }
                }

                call.respond(HttpStatusCode.OK, items)
            }

            // POST /projects/self — create project in current company
            post("/self") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiErrorProject("unauthorized", "Missing token"))
                val companyId = principalCompanyId(principal)
                if (companyId <= 0)
                    return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("no_company", "Token has no companyId"))
                if (!isAdminForCompany(principal, companyId))
                    return@post call.respond(HttpStatusCode.Forbidden, ApiErrorProject("forbidden", "Admin rights required"))

                val body = try { call.receive<ProjectCreateRequest>() } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Body must be JSON"))
                }
                if (body.title.isBlank())
                    return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("title_required"))

                try {
                    val created = transaction {
                        val newId = Projects.insertAndGetId {
                            it[Projects.companyId] = EntityID(companyId, Companies)
                            it[title]       = body.title.trim()
                            it[description] = body.description
                            it[location]    = body.location
                            it[lat]         = body.lat
                            it[lng]         = body.lng
                        }
                        Projects
                            .select { Projects.id eq newId }
                            .single()
                    }
                    // fresh count = 0
                    call.respond(HttpStatusCode.Created, created.toProjectDTO(membersCount = 0))
                } catch (e: ExposedSQLException) {
                    if (e.sqlState == "23505") {
                        return@post call.respond(HttpStatusCode.Conflict, ApiErrorProject("project_title_taken", "Title must be unique within company"))
                    }
                    call.application.log.error("Create project failed", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiErrorProject("create_failed", e.message))
                } catch (e: Exception) {
                    call.application.log.error("Create project failed", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiErrorProject("create_failed", e.message))
                }
            }
            // GET /projects/{id}/standort — coordinates for active geofence
            get("/{id}/standort") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiErrorProject("unauthorized", "Missing token"))
                val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Missing id"))
                val projectId = idParam.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_id"))

                val companyId = principalCompanyId(principal)
                if (companyId <= 0)
                    return@get call.respond(HttpStatusCode.BadRequest, ApiErrorProject("no_company", "Token has no companyId"))

                val row = requireSameCompanyOr404(projectId, companyId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiErrorProject("not_found", "Project not found"))

                val lat = row[Projects.lat] ?: return@get call.respond(HttpStatusCode.NotFound)
                val lng = row[Projects.lng] ?: return@get call.respond(HttpStatusCode.NotFound)
                val location = row[Projects.location]

                val dto = StandortDTO(
                    projectId = projectId,
                    lat = lat,
                    lng = lng,
                    // If Projects has no radius column, we use default = 300 in DTO
                    location = location
                )
                call.respond(HttpStatusCode.OK, dto)
            }

            // GET /projects/{id} — details with optional ?include=members
            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiErrorProject("unauthorized", "Missing token"))
                val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Missing id"))
                val projectId = idParam.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_id"))

                val includeMembers = call.request.queryParameters["include"]?.contains("members") == true
                val companyId = principalCompanyId(principal)
                if (companyId <= 0)
                    return@get call.respond(HttpStatusCode.BadRequest, ApiErrorProject("no_company", "Token has no companyId"))

                val row = requireSameCompanyOr404(projectId, companyId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiErrorProject("not_found", "Project not found"))

                val membersCount = transaction {
                    ProjectMembers.select { ProjectMembers.projectId eq EntityID(projectId, Projects) }.count().toInt()
                }

                if (!includeMembers) {
                    return@get call.respond(HttpStatusCode.OK, row.toProjectDTO(membersCount))
                }

                val members = transaction {
                    (ProjectMembers innerJoin Users)
                        .slice(ProjectMembers.userId, ProjectMembers.role, ProjectMembers.joinedAt, Users.firstName, Users.lastName)
                        .select { ProjectMembers.projectId eq EntityID(projectId, Projects) }
                        .orderBy(Users.lastName to SortOrder.ASC, Users.firstName to SortOrder.ASC)
                        .map {
                            ProjectMemberDTO(
                                userId = it[ProjectMembers.userId].value,
                                name   = userName(it[Users.firstName], it[Users.lastName]),
                                role   = it[ProjectMembers.role].toInt(),
                                joinedAt = it[ProjectMembers.joinedAt].toString()
                            )
                        }
                }
                call.respond(HttpStatusCode.OK, ProjectDetailDTO(project = row.toProjectDTO(membersCount), members = members))
            }

            // PATCH /projects/{id}
            patch("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, ApiErrorProject("unauthorized", "Missing token"))
                val idParam = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Missing id"))
                val projectId = idParam.toIntOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_id"))

                val companyId = principalCompanyId(principal)
                if (companyId <= 0)
                    return@patch call.respond(HttpStatusCode.BadRequest, ApiErrorProject("no_company", "Token has no companyId"))

                val row = requireSameCompanyOr404(projectId, companyId)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ApiErrorProject("not_found", "Project not found"))

                if (!isAdminForCompany(principal, companyId))
                    return@patch call.respond(HttpStatusCode.Forbidden, ApiErrorProject("forbidden", "Admin rights required"))

                val body = try { call.receive<ProjectUpdateRequest>() } catch (e: Exception) {
                    return@patch call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Body must be JSON"))
                }

                try {
                    transaction {
                        Projects.update({ Projects.id eq row[Projects.id] }) {
                            body.title?.let { t -> it[title] = t.trim() }
                            if (body.description != null) it[description] = body.description
                            if (body.location    != null) it[location]    = body.location
                            if (body.lat         != null) it[lat]         = body.lat
                            if (body.lng         != null) it[lng]         = body.lng
                        }
                    }
                    val updated = transaction { Projects.select { Projects.id eq row[Projects.id] }.single() }
                    val membersCount = transaction { ProjectMembers.select { ProjectMembers.projectId eq EntityID(projectId, Projects) }.count().toInt() }
                    call.respond(HttpStatusCode.OK, updated.toProjectDTO(membersCount))
                } catch (e: ExposedSQLException) {
                    if (e.sqlState == "23505") {
                        return@patch call.respond(HttpStatusCode.Conflict, ApiErrorProject("project_title_taken", "Title must be unique within company"))
                    }
                    call.application.log.error("Update project failed", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiErrorProject("update_failed", e.message))
                } catch (e: Exception) {
                    call.application.log.error("Update project failed", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiErrorProject("update_failed", e.message))
                }
            }

            // DELETE /projects/{id}
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, ApiErrorProject("unauthorized", "Missing token"))
                val idParam = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Missing id"))
                val projectId = idParam.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_id"))

                val companyId = principalCompanyId(principal)
                if (companyId <= 0)
                    return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorProject("no_company", "Token has no companyId"))

                val row = requireSameCompanyOr404(projectId, companyId)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ApiErrorProject("not_found", "Project not found"))

                if (!isAdminForCompany(principal, companyId))
                    return@delete call.respond(HttpStatusCode.Forbidden, ApiErrorProject("forbidden", "Admin rights required"))

                val deleted = transaction {
                    Projects.deleteWhere { Projects.id eq row[Projects.id] }
                }
                if (deleted == 0) return@delete call.respond(HttpStatusCode.NotFound, ApiErrorProject("not_found"))
                call.respond(HttpStatusCode.OK, OkResponse(true))
            }

            // --- Membership management --------------------------------------

            // GET /projects/{id}/members
            get("/{id}/members") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiErrorProject("unauthorized", "Missing token"))
                val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Missing id"))
                val projectId = idParam.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_id"))

                val companyId = principalCompanyId(principal)
                if (companyId <= 0)
                    return@get call.respond(HttpStatusCode.BadRequest, ApiErrorProject("no_company", "Token has no companyId"))

                val row = requireSameCompanyOr404(projectId, companyId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiErrorProject("not_found", "Project not found"))

                val members = membersForProject(row[Projects.id])
                call.respond(HttpStatusCode.OK, members)
            }

            // POST /projects/{id}/members
            post("/{id}/members") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiErrorProject("unauthorized", "Missing token"))
                val idParam = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Missing id"))
                val projectId = idParam.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_id"))

                val companyId = principalCompanyId(principal)
                if (companyId <= 0)
                    return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("no_company", "Token has no companyId"))

                val row = requireSameCompanyOr404(projectId, companyId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ApiErrorProject("not_found", "Project not found"))

                if (!isAdminForCompany(principal, companyId))
                    return@post call.respond(HttpStatusCode.Forbidden, ApiErrorProject("forbidden", "Admin rights required"))

                val body = try { call.receive<ProjectMemberAddRequest>() } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Body must be JSON"))
                }

                try {
                    transaction {
                        ProjectMembers.insertIgnore {
                            it[ProjectMembers.projectId] = row[Projects.id]
                            it[ProjectMembers.userId]    = EntityID(body.userId, Users)
                            body.role?.let { r -> it[role] = r.toShort() }
                        }
                    }
                    val members = membersForProject(row[Projects.id])
                    call.respond(HttpStatusCode.OK, members)
                } catch (e: ExposedSQLException) {
                    // Trigger from DB when company seats limit is reached
                    val isSeatLimit = e.sqlState == "P0001" || (e.cause?.message?.contains("company_project_seat_limit_reached") == true)
                    if (isSeatLimit) {
                        val (used, limit) = parseUsedLimitFromThrowable(e) ?: companySeatsStatus(companyId)
                        return@post call.respond(HttpStatusCode.Conflict, SeatLimitExceededError(used = used, limit = limit))
                    }
                    call.application.log.error("Add member failed", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiErrorProject("add_member_failed", e.message))
                } catch (e: Exception) {
                    // Fallback: try to recognize seat-limit via message chain
                    val isSeatLimit = e.message?.contains("company_project_seat_limit_reached") == true || e.cause?.message?.contains("company_project_seat_limit_reached") == true
                    if (isSeatLimit) {
                        val (used, limit) = parseUsedLimitFromThrowable(e) ?: companySeatsStatus(companyId)
                        return@post call.respond(HttpStatusCode.Conflict, SeatLimitExceededError(used = used, limit = limit))
                    }
                    call.application.log.error("Add member failed", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiErrorProject("add_member_failed", e.message))
                }
            }

            // DELETE /projects/{id}/members/{userId}
            delete("/{id}/members/{userId}") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, ApiErrorProject("unauthorized", "Missing token"))
                val idParam = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Missing id"))
                val projectId = idParam.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_id"))
                val userIdParam = call.parameters["userId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Missing userId"))
                val userId = userIdParam.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_user_id"))

                val companyId = principalCompanyId(principal)
                if (companyId <= 0)
                    return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorProject("no_company", "Token has no companyId"))

                val row = requireSameCompanyOr404(projectId, companyId)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ApiErrorProject("not_found", "Project not found"))

                if (!isAdminForCompany(principal, companyId))
                    return@delete call.respond(HttpStatusCode.Forbidden, ApiErrorProject("forbidden", "Admin rights required"))

                val deleted = transaction {
                    ProjectMembers.deleteWhere {
                        (ProjectMembers.projectId eq row[Projects.id]) and (ProjectMembers.userId eq EntityID(userId, Users))
                    }
                }
                if (deleted == 0) return@delete call.respond(HttpStatusCode.NotFound, ApiErrorProject("not_found", "Member not found"))
                val members = membersForProject(row[Projects.id])
                call.respond(HttpStatusCode.OK, members)
            }

            // POST /projects/{id}/members/bulk-set  — replace membership
            post("/{id}/members/bulk-set") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiErrorProject("unauthorized", "Missing token"))
                val idParam = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Missing id"))
                val projectId = idParam.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_id"))
                val companyId = principalCompanyId(principal)
                if (companyId <= 0)
                    return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("no_company", "Token has no companyId"))

                val row = requireSameCompanyOr404(projectId, companyId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ApiErrorProject("not_found", "Project not found"))

                if (!isAdminForCompany(principal, companyId))
                    return@post call.respond(HttpStatusCode.Forbidden, ApiErrorProject("forbidden", "Admin rights required"))

                val body = try { call.receive<ProjectMembersBulkSetRequest>() } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiErrorProject("invalid_request", "Body must be JSON"))
                }

                try {
                    transaction {
                        val targetIds = body.userIds.distinct().map { EntityID(it, Users) }.toSet()
                        val currentIds = ProjectMembers
                            .slice(ProjectMembers.userId)
                            .select { ProjectMembers.projectId eq row[Projects.id] }
                            .map { it[ProjectMembers.userId] }
                            .toSet()

                        // delete removed
                        (currentIds - targetIds).forEach { toDelete ->
                            ProjectMembers.deleteWhere { (ProjectMembers.projectId eq row[Projects.id]) and (ProjectMembers.userId eq toDelete) }
                        }
                        // insert new
                        (targetIds - currentIds).forEach { toAdd ->
                            ProjectMembers.insertIgnore {
                                it[ProjectMembers.projectId] = row[Projects.id]
                                it[ProjectMembers.userId]    = toAdd
                            }
                        }
                    }
                    val members = membersForProject(row[Projects.id])
                    call.respond(HttpStatusCode.OK, members)
                } catch (e: ExposedSQLException) {
                    val isSeatLimit = e.sqlState == "P0001" || (e.cause?.message?.contains("company_project_seat_limit_reached") == true)
                    if (isSeatLimit) {
                        val (used, limit) = parseUsedLimitFromThrowable(e) ?: companySeatsStatus(companyId)
                        return@post call.respond(HttpStatusCode.Conflict, SeatLimitExceededError(used = used, limit = limit))
                    }
                    call.application.log.error("Bulk set members failed", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiErrorProject("bulk_set_failed", e.message))
                } catch (e: Exception) {
                    val isSeatLimit = e.message?.contains("company_project_seat_limit_reached") == true || e.cause?.message?.contains("company_project_seat_limit_reached") == true
                    if (isSeatLimit) {
                        val (used, limit) = parseUsedLimitFromThrowable(e) ?: companySeatsStatus(companyId)
                        return@post call.respond(HttpStatusCode.Conflict, SeatLimitExceededError(used = used, limit = limit))
                    }
                    call.application.log.error("Bulk set members failed", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiErrorProject("bulk_set_failed", e.message))
                }
            }
        }
    }
}


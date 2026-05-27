package com.yourcompany.zeiterfassung.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom

// Response for company worker-join settings.
// These flags control how employees can join a company.
@Serializable
private data class JoinSettingsResponse(
    val companyId: Int,
    val simpleWorkerJoinEnabled: Boolean,
    val requiresAdminApproval: Boolean,
    val autoApproveExpectedEmployees: Boolean,
    val companySearchEnabled: Boolean
)

// Request body for updating worker-join settings from the admin panel.
@Serializable
private data class UpdateJoinSettingsRequest(
    val simpleWorkerJoinEnabled: Boolean,
    val requiresAdminApproval: Boolean,
    val autoApproveExpectedEmployees: Boolean,
    val companySearchEnabled: Boolean
)

// Request body for adding an expected employee.
// Expected employees are workers the admin has prepared in advance.
// Later we use email/phone/personnelNumber to match incoming join requests.
@Serializable
private data class CreateExpectedEmployeeRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val personnelNumber: String? = null,
    val defaultRole: String = "worker",
    val defaultProjectId: Int? = null
)

// Request body for editing an expected employee.
// Null means "leave unchanged" for PATCH-like updates.
@Serializable
private data class UpdateExpectedEmployeeRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val personnelNumber: String? = null,
    val defaultRole: String? = null,
    val defaultProjectId: Int? = null,
    val status: String? = null,
    val inviteStatus: String? = null
)

// Response returned after creating or listing expected employees.
@Serializable
private data class ExpectedEmployeeResponse(
    val id: Int,
    val companyId: Int,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phone: String?,
    val personnelNumber: String?,
    val defaultRole: String,
    val defaultProjectId: Int?,
    val status: String,
    val inviteStatus: String,
    val matchedUserId: Int?,
    val createdAt: String,
    val updatedAt: String
)

// Request body for creating a company/project join link.
// The token can later be rendered as a QR code or shared as an invite link.
@Serializable
private data class CreateJoinLinkRequest(
    val projectId: Int? = null,
    val type: String = "company_join",
    val requiresApproval: Boolean = true,
    val defaultRole: String = "worker",
    val maxUses: Int? = null,
    val expiresAt: String? = null
)

// Request body for editing join-link admin settings.
// Null means "leave unchanged".
@Serializable
private data class UpdateJoinLinkRequest(
    val isActive: Boolean? = null,
    val requiresApproval: Boolean? = null,
    val defaultRole: String? = null,
    val maxUses: Int? = null,
    val expiresAt: String? = null
)

// Full admin response for a created/listed join link.
// This includes the secret token and internal usage counters.
@Serializable
private data class JoinLinkResponse(
    val id: Int,
    val companyId: Int,
    val projectId: Int?,
    val token: String,
    val type: String,
    val isActive: Boolean,
    val requiresApproval: Boolean,
    val defaultRole: String,
    val maxUses: Int?,
    val usedCount: Int,
    val expiresAt: String?,
    val createdByUserId: Int?,
    val createdAt: String,
    val updatedAt: String
)

// Public/safe preview response for a join link.
// This is what the worker sees before submitting a join request.
// It does not expose internal admin data.
@Serializable
private data class JoinLinkPreviewResponse(
    val companyId: Int,
    val companyName: String,
    val projectId: Int?,
    val projectTitle: String?,
    val type: String,
    val requiresApproval: Boolean,
    val active: Boolean,
    val expired: Boolean
)

// Request body for a worker asking to join a company.
// The worker can join via token/link/QR or via a company code.
@Serializable
private data class CreateJoinRequestRequest(
    val token: String? = null,
    val companyCode: String? = null,
    val companyId: Int? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val personnelNumber: String? = null,
    val source: String = "join_link"
)

// Response for a created/listed join request.
// status = pending means the admin still has to approve/reject the worker.
// matchStatus shows whether the request matched an expected employee.
@Serializable
private data class JoinRequestResponse(
    val id: Int,
    val companyId: Int,
    val projectId: Int?,
    val userId: Int?,
    val expectedEmployeeId: Int?,
    val joinLinkId: Int?,
    val source: String,
    val submittedFirstName: String?,
    val submittedLastName: String?,
    val submittedEmail: String?,
    val submittedPhone: String?,
    val submittedPersonnelNumber: String?,
    val matchStatus: String,
    val status: String,
    val reviewedByUserId: Int?,
    val reviewedAt: String?,
    val reviewNote: String?,
    val createdAt: String,
    val updatedAt: String
)

// Optional body for rejecting a join request.
@Serializable
private data class RejectJoinRequestRequest(
    val reviewNote: String? = null
)

// Request body for approving several join requests at once.
@Serializable
private data class BulkApproveJoinRequestsRequest(
    val ids: List<Int>
)

// Response for bulk approve action.
@Serializable
private data class BulkApproveJoinRequestsResponse(
    val approvedCount: Int,
    val approvedIds: List<Int>,
    val skippedIds: List<Int>
)

// Request body for rejecting several join requests at once.
@Serializable
private data class BulkRejectJoinRequestsRequest(
    val ids: List<Int>,
    val reviewNote: String? = null
)

// Response for bulk reject action.
@Serializable
private data class BulkRejectJoinRequestsResponse(
    val rejectedCount: Int,
    val rejectedIds: List<Int>,
    val skippedIds: List<Int>
)

// Reads companyId from the JWT.
// Existing auth routes already put companyId into the token during login.
private fun jwtCompanyId(principal: JWTPrincipal): Int =
    principal.payload.getClaim("companyId").asInt() ?: 0

private fun jwtUserId(principal: JWTPrincipal): Int? =
    principal.payload.getClaim("id").asString()?.toIntOrNull()
        ?: principal.payload.getClaim("id").asInt()

// Checks whether the current JWT belongs to a company admin for this company.
// Global admins are also allowed.
private fun isAdminForJwtCompany(principal: JWTPrincipal, companyId: Int): Boolean {
    val isCompanyAdmin = principal.payload.getClaim("isCompanyAdmin").asBoolean() ?: false
    val isGlobalAdmin = principal.payload.getClaim("isGlobalAdmin").asBoolean() ?: false
    val tokenCompanyId = principal.payload.getClaim("companyId").asInt() ?: 0
    return isGlobalAdmin || (isCompanyAdmin && tokenCompanyId == companyId)
}

// Small SQL helper for this raw-SQL MVP route file.
// Converts blank strings to NULL and escapes single quotes.
// Later this should be replaced with prepared statements / Exposed DSL.
private fun sqlStringOrNull(value: String?): String =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace("'", "''")
        ?.let { "'$it'" }
        ?: "NULL"

// Converts nullable Int values to SQL NULL or a numeric string.
private fun sqlIntOrNull(value: Int?): String = value?.toString() ?: "NULL"

private fun sqlBooleanOrNull(value: Boolean?): String = value?.toString() ?: "NULL"

private fun resultSetIntOrNull(value: Any?): Int? = when (value) {
    is Int -> value
    is Number -> value.toInt()
    else -> null
}

// Allows only known employee roles used by the DB CHECK constraint.
private fun normalizeExpectedEmployeeRole(role: String): String? {
    val normalized = role.trim().lowercase()
    return if (normalized in setOf("worker", "manager", "admin")) normalized else null
}

// Allows only expected employee statuses used by the DB CHECK constraint.
private fun normalizeExpectedEmployeeStatus(status: String): String? {
    val normalized = status.trim().lowercase()
    return if (normalized in setOf("expected", "invited", "joined", "blocked", "failed")) normalized else null
}

// Allows only invite statuses used by the DB CHECK constraint.
private fun normalizeExpectedEmployeeInviteStatus(status: String): String? {
    val normalized = status.trim().lowercase()
    return if (normalized in setOf("not_sent", "sent", "opened", "joined", "failed", "expired")) normalized else null
}

// Secure random generator for join-link tokens.
private val joinLinkRandom = SecureRandom()

// Generates a non-guessable token for invite links / QR codes.
// Example: jn_xxx
private fun generateJoinToken(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val body = buildString {
        repeat(40) {
            append(alphabet[joinLinkRandom.nextInt(alphabet.length)])
        }
    }
    return "jn_$body"
}

// Allows only join-link types supported by the DB CHECK constraint.
private fun normalizeJoinLinkType(type: String): String? {
    val normalized = type.trim().lowercase()
    return if (normalized in setOf("company_join", "project_join", "checkin")) normalized else null
}

// Allows only request sources supported by the DB CHECK constraint.
private fun normalizeJoinRequestSource(source: String): String? {
    val normalized = source.trim().lowercase()
    return if (normalized in setOf("code", "qr", "join_link", "company_search", "admin_invite")) normalized else null
}

private fun readExpectedEmployee(rs: java.sql.ResultSet): ExpectedEmployeeResponse = ExpectedEmployeeResponse(
    id = rs.getInt("id"),
    companyId = rs.getInt("company_id"),
    firstName = rs.getString("first_name"),
    lastName = rs.getString("last_name"),
    email = rs.getString("email"),
    phone = rs.getString("phone"),
    personnelNumber = rs.getString("personnel_number"),
    defaultRole = rs.getString("default_role"),
    defaultProjectId = resultSetIntOrNull(rs.getObject("default_project_id")),
    status = rs.getString("status"),
    inviteStatus = rs.getString("invite_status"),
    matchedUserId = resultSetIntOrNull(rs.getObject("matched_user_id")),
    createdAt = rs.getTimestamp("created_at").toInstant().toString(),
    updatedAt = rs.getTimestamp("updated_at").toInstant().toString()
)

private fun readJoinLink(rs: java.sql.ResultSet): JoinLinkResponse = JoinLinkResponse(
    id = rs.getInt("id"),
    companyId = rs.getInt("company_id"),
    projectId = resultSetIntOrNull(rs.getObject("project_id")),
    token = rs.getString("token"),
    type = rs.getString("type"),
    isActive = rs.getBoolean("is_active"),
    requiresApproval = rs.getBoolean("requires_approval"),
    defaultRole = rs.getString("default_role"),
    maxUses = resultSetIntOrNull(rs.getObject("max_uses")),
    usedCount = rs.getInt("used_count"),
    expiresAt = rs.getTimestamp("expires_at")?.toInstant()?.toString(),
    createdByUserId = resultSetIntOrNull(rs.getObject("created_by_user_id")),
    createdAt = rs.getTimestamp("created_at").toInstant().toString(),
    updatedAt = rs.getTimestamp("updated_at").toInstant().toString()
)

private fun readJoinRequest(rs: java.sql.ResultSet): JoinRequestResponse = JoinRequestResponse(
    id = rs.getInt("id"),
    companyId = rs.getInt("company_id"),
    projectId = resultSetIntOrNull(rs.getObject("project_id")),
    userId = resultSetIntOrNull(rs.getObject("user_id")),
    expectedEmployeeId = resultSetIntOrNull(rs.getObject("expected_employee_id")),
    joinLinkId = resultSetIntOrNull(rs.getObject("join_link_id")),
    source = rs.getString("source"),
    submittedFirstName = rs.getString("submitted_first_name"),
    submittedLastName = rs.getString("submitted_last_name"),
    submittedEmail = rs.getString("submitted_email"),
    submittedPhone = rs.getString("submitted_phone"),
    submittedPersonnelNumber = rs.getString("submitted_personnel_number"),
    matchStatus = rs.getString("match_status"),
    status = rs.getString("status"),
    reviewedByUserId = resultSetIntOrNull(rs.getObject("reviewed_by_user_id")),
    reviewedAt = rs.getTimestamp("reviewed_at")?.toInstant()?.toString(),
    reviewNote = rs.getString("review_note"),
    createdAt = rs.getTimestamp("created_at").toInstant().toString(),
    updatedAt = rs.getTimestamp("updated_at").toInstant().toString()
)

fun Route.workerJoinRoutes() {
    // Tiny protected health-check used only to verify that this route file is mounted.
    route("/worker-join") {
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "ok",
                    "module" to "worker-join"
                )
            )
        }
    }

    // Admin/company-owner endpoints.
    // These routes operate on the company from the current JWT.
    route("/companies/self") {
        // GET current worker-join settings.
        // If the settings row does not exist yet, we create a default one.
        get("/join-settings") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            try {
                val settings = transaction {
                    exec(
                        """
                        INSERT INTO company_join_settings (
                            company_id,
                            simple_worker_join_enabled,
                            requires_admin_approval,
                            auto_approve_expected_employees,
                            company_search_enabled,
                            created_at,
                            updated_at
                        )
                        VALUES ($companyId, false, true, true, false, now(), now())
                        ON CONFLICT (company_id) DO NOTHING
                        """.trimIndent()
                    )

                    var response: JoinSettingsResponse? = null
                    exec(
                        """
                        SELECT
                            company_id,
                            simple_worker_join_enabled,
                            requires_admin_approval,
                            auto_approve_expected_employees,
                            company_search_enabled
                        FROM company_join_settings
                        WHERE company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            response = JoinSettingsResponse(
                                companyId = rs.getInt("company_id"),
                                simpleWorkerJoinEnabled = rs.getBoolean("simple_worker_join_enabled"),
                                requiresAdminApproval = rs.getBoolean("requires_admin_approval"),
                                autoApproveExpectedEmployees = rs.getBoolean("auto_approve_expected_employees"),
                                companySearchEnabled = rs.getBoolean("company_search_enabled")
                            )
                        }
                    }
                    response ?: error("join_settings_not_found")
                }

                call.respond(HttpStatusCode.OK, settings)
            } catch (e: Exception) {
                call.application.log.error("Failed to fetch join settings for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Update worker-join settings from the admin panel.
        put("/join-settings") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@put call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@put call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val request = try {
                call.receive<UpdateJoinSettingsRequest>()
            } catch (e: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Invalid join settings payload"))
            }

            try {
                val settings = transaction {
                    exec(
                        """
                        INSERT INTO company_join_settings (
                            company_id,
                            simple_worker_join_enabled,
                            requires_admin_approval,
                            auto_approve_expected_employees,
                            company_search_enabled,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            $companyId,
                            ${request.simpleWorkerJoinEnabled},
                            ${request.requiresAdminApproval},
                            ${request.autoApproveExpectedEmployees},
                            ${request.companySearchEnabled},
                            now(),
                            now()
                        )
                        ON CONFLICT (company_id) DO UPDATE SET
                            simple_worker_join_enabled = EXCLUDED.simple_worker_join_enabled,
                            requires_admin_approval = EXCLUDED.requires_admin_approval,
                            auto_approve_expected_employees = EXCLUDED.auto_approve_expected_employees,
                            company_search_enabled = EXCLUDED.company_search_enabled,
                            updated_at = now()
                        """.trimIndent()
                    )

                    var response: JoinSettingsResponse? = null
                    exec(
                        """
                        SELECT
                            company_id,
                            simple_worker_join_enabled,
                            requires_admin_approval,
                            auto_approve_expected_employees,
                            company_search_enabled
                        FROM company_join_settings
                        WHERE company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            response = JoinSettingsResponse(
                                companyId = rs.getInt("company_id"),
                                simpleWorkerJoinEnabled = rs.getBoolean("simple_worker_join_enabled"),
                                requiresAdminApproval = rs.getBoolean("requires_admin_approval"),
                                autoApproveExpectedEmployees = rs.getBoolean("auto_approve_expected_employees"),
                                companySearchEnabled = rs.getBoolean("company_search_enabled")
                            )
                        }
                    }
                    response ?: error("join_settings_not_found")
                }

                call.respond(HttpStatusCode.OK, settings)
            } catch (e: Exception) {
                call.application.log.error("Failed to update join settings for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Admin manually adds one expected employee.
        post("/expected-employees") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val request = try {
                call.receive<CreateExpectedEmployeeRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Invalid expected employee payload"))
            }

            val normalizedRole = normalizeExpectedEmployeeRole(request.defaultRole)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_role", "defaultRole must be worker, manager or admin"))

            val hasIdentity = listOf(request.firstName, request.lastName, request.email, request.phone, request.personnelNumber)
                .any { !it.isNullOrBlank() }
            if (!hasIdentity) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("missing_identity", "At least one employee field is required"))
            }

            try {
                val employee = transaction {
                    exec(
                        """
                        INSERT INTO expected_employees (
                            company_id,
                            first_name,
                            last_name,
                            email,
                            phone,
                            personnel_number,
                            default_role,
                            default_project_id,
                            status,
                            invite_status,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            $companyId,
                            ${sqlStringOrNull(request.firstName)},
                            ${sqlStringOrNull(request.lastName)},
                            ${sqlStringOrNull(request.email)},
                            ${sqlStringOrNull(request.phone)},
                            ${sqlStringOrNull(request.personnelNumber)},
                            '$normalizedRole',
                            ${sqlIntOrNull(request.defaultProjectId)},
                            'expected',
                            'not_sent',
                            now(),
                            now()
                        )
                        """.trimIndent()
                    )

                    var response: ExpectedEmployeeResponse? = null
                    exec(
                        """
                        SELECT
                            id,
                            company_id,
                            first_name,
                            last_name,
                            email,
                            phone,
                            personnel_number,
                            default_role,
                            default_project_id,
                            status,
                            invite_status,
                            matched_user_id,
                            created_at,
                            updated_at
                        FROM expected_employees
                        WHERE company_id = $companyId
                        ORDER BY id DESC
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) response = readExpectedEmployee(rs)
                    }
                    response ?: error("expected_employee_not_created")
                }

                call.respond(HttpStatusCode.Created, employee)
            } catch (e: Exception) {
                call.application.log.error("Failed to create expected employee for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Admin fetches the latest expected employees for this company.
        get("/expected-employees") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            try {
                val employees = transaction {
                    val rows = mutableListOf<ExpectedEmployeeResponse>()
                    exec(
                        """
                        SELECT
                            id,
                            company_id,
                            first_name,
                            last_name,
                            email,
                            phone,
                            personnel_number,
                            default_role,
                            default_project_id,
                            status,
                            invite_status,
                            matched_user_id,
                            created_at,
                            updated_at
                        FROM expected_employees
                        WHERE company_id = $companyId
                        ORDER BY created_at DESC, id DESC
                        LIMIT 200
                        """.trimIndent()
                    ) { rs ->
                        while (rs.next()) rows.add(readExpectedEmployee(rs))
                    }
                    rows
                }

                call.respond(HttpStatusCode.OK, employees)
            } catch (e: Exception) {
                call.application.log.error("Failed to fetch expected employees for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Admin edits one expected employee.
        patch("/expected-employees/{id}") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@patch call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@patch call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val employeeId = call.parameters["id"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "Invalid expected employee id"))

            val request = try {
                call.receive<UpdateExpectedEmployeeRequest>()
            } catch (e: Exception) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Invalid expected employee payload"))
            }

            val normalizedRole = request.defaultRole?.let {
                normalizeExpectedEmployeeRole(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("invalid_role", "defaultRole must be worker, manager or admin"))
            }
            val normalizedStatus = request.status?.let {
                normalizeExpectedEmployeeStatus(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("invalid_status", "status is invalid"))
            }
            val normalizedInviteStatus = request.inviteStatus?.let {
                normalizeExpectedEmployeeInviteStatus(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("invalid_invite_status", "inviteStatus is invalid"))
            }

            try {
                val updated = transaction {
                    exec(
                        """
                        UPDATE expected_employees
                        SET
                            first_name = COALESCE(${sqlStringOrNull(request.firstName)}, first_name),
                            last_name = COALESCE(${sqlStringOrNull(request.lastName)}, last_name),
                            email = COALESCE(${sqlStringOrNull(request.email)}, email),
                            phone = COALESCE(${sqlStringOrNull(request.phone)}, phone),
                            personnel_number = COALESCE(${sqlStringOrNull(request.personnelNumber)}, personnel_number),
                            default_role = COALESCE(${sqlStringOrNull(normalizedRole)}, default_role),
                            default_project_id = COALESCE(${sqlIntOrNull(request.defaultProjectId)}, default_project_id),
                            status = COALESCE(${sqlStringOrNull(normalizedStatus)}, status),
                            invite_status = COALESCE(${sqlStringOrNull(normalizedInviteStatus)}, invite_status),
                            updated_at = now()
                        WHERE id = $employeeId
                          AND company_id = $companyId
                        """.trimIndent()
                    )

                    var response: ExpectedEmployeeResponse? = null
                    exec(
                        """
                        SELECT
                            id,
                            company_id,
                            first_name,
                            last_name,
                            email,
                            phone,
                            personnel_number,
                            default_role,
                            default_project_id,
                            status,
                            invite_status,
                            matched_user_id,
                            created_at,
                            updated_at
                        FROM expected_employees
                        WHERE id = $employeeId
                          AND company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) response = readExpectedEmployee(rs)
                    }
                    response
                }

                if (updated == null) {
                    return@patch call.respond(HttpStatusCode.NotFound, ApiError("expected_employee_not_found", "Expected employee was not found"))
                }

                call.respond(HttpStatusCode.OK, updated)
            } catch (e: Exception) {
                call.application.log.error("Failed to update expected employee $employeeId for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Admin deletes one expected employee.
        delete("/expected-employees/{id}") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@delete call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@delete call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val employeeId = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "Invalid expected employee id"))

            try {
                val exists = transaction {
                    var found = false
                    exec(
                        """
                        SELECT id
                        FROM expected_employees
                        WHERE id = $employeeId
                          AND company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs -> found = rs.next() }

                    if (found) {
                        exec(
                            """
                            DELETE FROM expected_employees
                            WHERE id = $employeeId
                              AND company_id = $companyId
                            """.trimIndent()
                        )
                    }
                    found
                }

                if (!exists) {
                    return@delete call.respond(HttpStatusCode.NotFound, ApiError("expected_employee_not_found", "Expected employee was not found"))
                }

                call.respond(HttpStatusCode.OK, mapOf("deleted" to true, "id" to employeeId))
            } catch (e: Exception) {
                call.application.log.error("Failed to delete expected employee $employeeId for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Admin creates a shareable join link / QR token.
        post("/join-links") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val adminUserId = jwtUserId(principal)
            val request = try {
                call.receive<CreateJoinLinkRequest>()
            } catch (e: Exception) {
                CreateJoinLinkRequest()
            }

            val normalizedType = normalizeJoinLinkType(request.type)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_type", "type must be company_join, project_join or checkin"))
            val normalizedRole = normalizeExpectedEmployeeRole(request.defaultRole)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_role", "defaultRole must be worker, manager or admin"))

            if (request.maxUses != null && request.maxUses <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_max_uses", "maxUses must be greater than 0"))
            }

            try {
                val link = transaction {
                    var response: JoinLinkResponse? = null
                    var token = generateJoinToken()
                    var attempts = 0

                    while (attempts < 5) {
                        try {
                            exec(
                                """
                                INSERT INTO join_links (
                                    company_id,
                                    project_id,
                                    token,
                                    type,
                                    is_active,
                                    requires_approval,
                                    default_role,
                                    max_uses,
                                    used_count,
                                    expires_at,
                                    created_by_user_id,
                                    created_at,
                                    updated_at
                                )
                                VALUES (
                                    $companyId,
                                    ${sqlIntOrNull(request.projectId)},
                                    '$token',
                                    '$normalizedType',
                                    true,
                                    ${request.requiresApproval},
                                    '$normalizedRole',
                                    ${sqlIntOrNull(request.maxUses)},
                                    0,
                                    ${sqlStringOrNull(request.expiresAt)}::timestamptz,
                                    ${sqlIntOrNull(adminUserId)},
                                    now(),
                                    now()
                                )
                                """.trimIndent()
                            )
                            break
                        } catch (e: Exception) {
                            attempts += 1
                            if (attempts >= 5) throw e
                            token = generateJoinToken()
                        }
                    }

                    exec(
                        """
                        SELECT
                            id,
                            company_id,
                            project_id,
                            token,
                            type,
                            is_active,
                            requires_approval,
                            default_role,
                            max_uses,
                            used_count,
                            expires_at,
                            created_by_user_id,
                            created_at,
                            updated_at
                        FROM join_links
                        WHERE token = '$token'
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) response = readJoinLink(rs)
                    }
                    response ?: error("join_link_not_created")
                }

                call.respond(HttpStatusCode.Created, link)
            } catch (e: Exception) {
                call.application.log.error("Failed to create join link for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Admin fetches join links for this company.
        get("/join-links") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            try {
                val links = transaction {
                    val rows = mutableListOf<JoinLinkResponse>()
                    exec(
                        """
                        SELECT
                            id,
                            company_id,
                            project_id,
                            token,
                            type,
                            is_active,
                            requires_approval,
                            default_role,
                            max_uses,
                            used_count,
                            expires_at,
                            created_by_user_id,
                            created_at,
                            updated_at
                        FROM join_links
                        WHERE company_id = $companyId
                        ORDER BY created_at DESC, id DESC
                        LIMIT 200
                        """.trimIndent()
                    ) { rs ->
                        while (rs.next()) rows.add(readJoinLink(rs))
                    }
                    rows
                }

                call.respond(HttpStatusCode.OK, links)
            } catch (e: Exception) {
                call.application.log.error("Failed to fetch join links for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Admin edits one join link.
        patch("/join-links/{id}") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@patch call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@patch call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val linkId = call.parameters["id"]?.toIntOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "Invalid join link id"))

            val request = try {
                call.receive<UpdateJoinLinkRequest>()
            } catch (e: Exception) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Invalid join link payload"))
            }

            val normalizedRole = request.defaultRole?.let {
                normalizeExpectedEmployeeRole(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("invalid_role", "defaultRole must be worker, manager or admin"))
            }
            if (request.maxUses != null && request.maxUses <= 0) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("invalid_max_uses", "maxUses must be greater than 0"))
            }

            try {
                val updated = transaction {
                    exec(
                        """
                        UPDATE join_links
                        SET
                            is_active = COALESCE(${sqlBooleanOrNull(request.isActive)}, is_active),
                            requires_approval = COALESCE(${sqlBooleanOrNull(request.requiresApproval)}, requires_approval),
                            default_role = COALESCE(${sqlStringOrNull(normalizedRole)}, default_role),
                            max_uses = COALESCE(${sqlIntOrNull(request.maxUses)}, max_uses),
                            expires_at = COALESCE(${sqlStringOrNull(request.expiresAt)}::timestamptz, expires_at),
                            updated_at = now()
                        WHERE id = $linkId
                          AND company_id = $companyId
                        """.trimIndent()
                    )

                    var response: JoinLinkResponse? = null
                    exec(
                        """
                        SELECT
                            id,
                            company_id,
                            project_id,
                            token,
                            type,
                            is_active,
                            requires_approval,
                            default_role,
                            max_uses,
                            used_count,
                            expires_at,
                            created_by_user_id,
                            created_at,
                            updated_at
                        FROM join_links
                        WHERE id = $linkId
                          AND company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) response = readJoinLink(rs)
                    }
                    response
                }

                if (updated == null) {
                    return@patch call.respond(HttpStatusCode.NotFound, ApiError("join_link_not_found", "Join link was not found"))
                }

                call.respond(HttpStatusCode.OK, updated)
            } catch (e: Exception) {
                call.application.log.error("Failed to update join link $linkId for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Admin deactivates one join link without deleting history.
        post("/join-links/{id}/deactivate") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val linkId = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "Invalid join link id"))

            try {
                val updated = transaction {
                    var found = false
                    exec(
                        """
                        SELECT id
                        FROM join_links
                        WHERE id = $linkId
                          AND company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs -> found = rs.next() }

                    if (found) {
                        exec(
                            """
                            UPDATE join_links
                            SET is_active = false,
                                updated_at = now()
                            WHERE id = $linkId
                              AND company_id = $companyId
                            """.trimIndent()
                        )
                    }
                    found
                }

                if (!updated) {
                    return@post call.respond(HttpStatusCode.NotFound, ApiError("join_link_not_found", "Join link was not found"))
                }

                call.respond(HttpStatusCode.OK, mapOf("id" to linkId, "isActive" to false))
            } catch (e: Exception) {
                call.application.log.error("Failed to deactivate join link $linkId for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Admin fetches join requests for this company.
        get("/join-requests") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            try {
                val requests = transaction {
                    val rows = mutableListOf<JoinRequestResponse>()
                    exec(
                        """
                        SELECT
                            id,
                            company_id,
                            project_id,
                            user_id,
                            expected_employee_id,
                            join_link_id,
                            source,
                            submitted_first_name,
                            submitted_last_name,
                            submitted_email,
                            submitted_phone,
                            submitted_personnel_number,
                            match_status,
                            status,
                            reviewed_by_user_id,
                            reviewed_at,
                            review_note,
                            created_at,
                            updated_at
                        FROM join_requests
                        WHERE company_id = $companyId
                        ORDER BY created_at DESC, id DESC
                        LIMIT 200
                        """.trimIndent()
                    ) { rs ->
                        while (rs.next()) rows.add(readJoinRequest(rs))
                    }
                    rows
                }

                call.respond(HttpStatusCode.OK, requests)
            } catch (e: Exception) {
                call.application.log.error("Failed to fetch join requests for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }

        // Admin approves a pending join request.
        // This links the worker user to the company using the existing users.company_id field.
        post("/join-requests/{id}/approve") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val adminUserId = jwtUserId(principal)
            val requestId = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "Invalid join request id"))

            try {
                val approved = transaction {
                    var workerUserId: Int? = null
                    var expectedEmployeeId: Int? = null
                    var currentStatus: String? = null

                    exec(
                        """
                        SELECT user_id, expected_employee_id, status
                        FROM join_requests
                        WHERE id = $requestId
                          AND company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            workerUserId = resultSetIntOrNull(rs.getObject("user_id"))
                            expectedEmployeeId = resultSetIntOrNull(rs.getObject("expected_employee_id"))
                            currentStatus = rs.getString("status")
                        }
                    }

                    if (workerUserId == null) error("join_request_not_found")
                    if (currentStatus != "pending") error("join_request_not_pending")

                    exec(
                        """
                        UPDATE users
                        SET company_id = $companyId,
                            is_company_admin = false,
                            status = 'active'
                        WHERE id = ${sqlIntOrNull(workerUserId)}
                        """.trimIndent()
                    )

                    exec(
                        """
                        UPDATE join_requests
                        SET status = 'approved',
                            reviewed_by_user_id = ${sqlIntOrNull(adminUserId)},
                            reviewed_at = now(),
                            updated_at = now()
                        WHERE id = $requestId
                          AND company_id = $companyId
                        """.trimIndent()
                    )

                    if (expectedEmployeeId != null) {
                        exec(
                            """
                            UPDATE expected_employees
                            SET status = 'joined',
                                invite_status = 'joined',
                                joined_at = now(),
                                matched_user_id = ${sqlIntOrNull(workerUserId)},
                                updated_at = now()
                            WHERE id = ${sqlIntOrNull(expectedEmployeeId)}
                              AND company_id = $companyId
                            """.trimIndent()
                        )
                    }

                    var response: JoinRequestResponse? = null
                    exec(
                        """
                        SELECT
                            id,
                            company_id,
                            project_id,
                            user_id,
                            expected_employee_id,
                            join_link_id,
                            source,
                            submitted_first_name,
                            submitted_last_name,
                            submitted_email,
                            submitted_phone,
                            submitted_personnel_number,
                            match_status,
                            status,
                            reviewed_by_user_id,
                            reviewed_at,
                            review_note,
                            created_at,
                            updated_at
                        FROM join_requests
                        WHERE id = $requestId
                          AND company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) response = readJoinRequest(rs)
                    }
                    response ?: error("join_request_not_found")
                }

                call.respond(HttpStatusCode.OK, approved)
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                when {
                    message.contains("join_request_not_found") -> call.respond(HttpStatusCode.NotFound, ApiError("join_request_not_found", "Join request was not found"))
                    message.contains("join_request_not_pending") -> call.respond(HttpStatusCode.BadRequest, ApiError("join_request_not_pending", "Only pending join requests can be approved"))
                    else -> {
                        call.application.log.error("Failed to approve join request $requestId for company $companyId", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
                    }
                }
            }
        }

        // Admin rejects a pending join request.
        post("/join-requests/{id}/reject") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val adminUserId = jwtUserId(principal)
            val requestId = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "Invalid join request id"))

            val body = try {
                call.receive<RejectJoinRequestRequest>()
            } catch (e: Exception) {
                RejectJoinRequestRequest()
            }

            try {
                val rejected = transaction {
                    var currentStatus: String? = null
                    exec(
                        """
                        SELECT status
                        FROM join_requests
                        WHERE id = $requestId
                          AND company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) currentStatus = rs.getString("status")
                    }

                    if (currentStatus == null) error("join_request_not_found")
                    if (currentStatus != "pending") error("join_request_not_pending")

                    exec(
                        """
                        UPDATE join_requests
                        SET status = 'rejected',
                            reviewed_by_user_id = ${sqlIntOrNull(adminUserId)},
                            reviewed_at = now(),
                            review_note = ${sqlStringOrNull(body.reviewNote)},
                            updated_at = now()
                        WHERE id = $requestId
                          AND company_id = $companyId
                        """.trimIndent()
                    )

                    var response: JoinRequestResponse? = null
                    exec(
                        """
                        SELECT
                            id,
                            company_id,
                            project_id,
                            user_id,
                            expected_employee_id,
                            join_link_id,
                            source,
                            submitted_first_name,
                            submitted_last_name,
                            submitted_email,
                            submitted_phone,
                            submitted_personnel_number,
                            match_status,
                            status,
                            reviewed_by_user_id,
                            reviewed_at,
                            review_note,
                            created_at,
                            updated_at
                        FROM join_requests
                        WHERE id = $requestId
                          AND company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) response = readJoinRequest(rs)
                    }
                    response ?: error("join_request_not_found")
                }

                call.respond(HttpStatusCode.OK, rejected)
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                when {
                    message.contains("join_request_not_found") -> call.respond(HttpStatusCode.NotFound, ApiError("join_request_not_found", "Join request was not found"))
                    message.contains("join_request_not_pending") -> call.respond(HttpStatusCode.BadRequest, ApiError("join_request_not_pending", "Only pending join requests can be rejected"))
                    else -> {
                        call.application.log.error("Failed to reject join request $requestId for company $companyId", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
                    }
                }
            }
        }

        // Admin approves several pending join requests at once.
        // This powers the "Alle bestätigen" action in the admin UI.
        post("/join-requests/bulk-approve") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val adminUserId = jwtUserId(principal)
            val body = try {
                call.receive<BulkApproveJoinRequestsRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Invalid bulk approve payload"))
            }

            val requestedIds = body.ids.distinct().filter { it > 0 }
            if (requestedIds.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("empty_ids", "ids must contain at least one valid join request id"))
            }

            try {
                val result = transaction {
                    val requestedIdSql = requestedIds.joinToString(",")
                    val pendingRows = mutableListOf<Triple<Int, Int, Int?>>()

                    exec(
                        """
                        SELECT id, user_id, expected_employee_id
                        FROM join_requests
                        WHERE company_id = $companyId
                          AND status = 'pending'
                          AND id IN ($requestedIdSql)
                          AND user_id IS NOT NULL
                        ORDER BY id ASC
                        """.trimIndent()
                    ) { rs ->
                        while (rs.next()) {
                            pendingRows.add(
                                Triple(
                                    rs.getInt("id"),
                                    rs.getInt("user_id"),
                                    resultSetIntOrNull(rs.getObject("expected_employee_id"))
                                )
                            )
                        }
                    }

                    val approvedIds = pendingRows.map { it.first }
                    val skippedIds = requestedIds.filterNot { it in approvedIds }

                    pendingRows.forEach { (_, workerUserId, expectedEmployeeId) ->
                        exec(
                            """
                            UPDATE users
                            SET company_id = $companyId,
                                is_company_admin = false,
                                status = 'active'
                            WHERE id = $workerUserId
                            """.trimIndent()
                        )

                        if (expectedEmployeeId != null) {
                            exec(
                                """
                                UPDATE expected_employees
                                SET status = 'joined',
                                    invite_status = 'joined',
                                    joined_at = now(),
                                    matched_user_id = $workerUserId,
                                    updated_at = now()
                                WHERE id = $expectedEmployeeId
                                  AND company_id = $companyId
                                """.trimIndent()
                            )
                        }
                    }

                    if (approvedIds.isNotEmpty()) {
                        exec(
                            """
                            UPDATE join_requests
                            SET status = 'approved',
                                reviewed_by_user_id = ${sqlIntOrNull(adminUserId)},
                                reviewed_at = now(),
                                updated_at = now()
                            WHERE company_id = $companyId
                              AND id IN (${approvedIds.joinToString(",")})
                            """.trimIndent()
                        )
                    }

                    BulkApproveJoinRequestsResponse(
                        approvedCount = approvedIds.size,
                        approvedIds = approvedIds,
                        skippedIds = skippedIds
                    )
                }

                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                call.application.log.error("Failed to bulk approve join requests for company $companyId", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty()))
                )
            }
        }

        // Admin rejects several pending join requests at once.
        // This powers the "Alle ablehnen" action in the admin UI.
        post("/join-requests/bulk-reject") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = jwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!isAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val adminUserId = jwtUserId(principal)
            val body = try {
                call.receive<BulkRejectJoinRequestsRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Invalid bulk reject payload"))
            }

            val requestedIds = body.ids.distinct().filter { it > 0 }
            if (requestedIds.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("empty_ids", "ids must contain at least one valid join request id"))
            }

            try {
                val result = transaction {
                    val requestedIdSql = requestedIds.joinToString(",")
                    val pendingIds = mutableListOf<Int>()

                    exec(
                        """
                        SELECT id
                        FROM join_requests
                        WHERE company_id = $companyId
                          AND status = 'pending'
                          AND id IN ($requestedIdSql)
                        ORDER BY id ASC
                        """.trimIndent()
                    ) { rs ->
                        while (rs.next()) {
                            pendingIds.add(rs.getInt("id"))
                        }
                    }

                    val skippedIds = requestedIds.filterNot { it in pendingIds }

                    if (pendingIds.isNotEmpty()) {
                        exec(
                            """
                            UPDATE join_requests
                            SET status = 'rejected',
                                reviewed_by_user_id = ${sqlIntOrNull(adminUserId)},
                                reviewed_at = now(),
                                review_note = ${sqlStringOrNull(body.reviewNote)},
                                updated_at = now()
                            WHERE company_id = $companyId
                              AND id IN (${pendingIds.joinToString(",")})
                            """.trimIndent()
                        )
                    }

                    BulkRejectJoinRequestsResponse(
                        rejectedCount = pendingIds.size,
                        rejectedIds = pendingIds,
                        skippedIds = skippedIds
                    )
                }

                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                call.application.log.error("Failed to bulk reject join requests for company $companyId", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty()))
                )
            }
        }
    }


    // Worker-side join request endpoint.
    // A logged-in worker asks to join a company using a link token or company code.
    route("/join-requests") {
        // Creates a pending join request.
        // The request is matched against expected_employees by email/phone/personnelNumber.
        post {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val userId = jwtUserId(principal)
            if (userId == null || userId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_user", "Token has no user id"))
            }

            val request = try {
                call.receive<CreateJoinRequestRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Invalid join request payload"))
            }

            val normalizedSource = normalizeJoinRequestSource(request.source)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_source", "source must be code, qr, join_link, company_search or admin_invite"))

            val hasToken = !request.token.isNullOrBlank()
            val hasCompanyCode = !request.companyCode.isNullOrBlank()
            val hasCompanyId = request.companyId != null && request.companyId > 0
            if (!hasToken && !hasCompanyCode && !hasCompanyId) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("missing_join_target", "token, companyCode or companyId is required"))
            }

            val hasIdentity = listOf(request.firstName, request.lastName, request.email, request.phone, request.personnelNumber)
                .any { !it.isNullOrBlank() }
            if (!hasIdentity) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("missing_identity", "At least one submitted employee field is required"))
            }

            try {
                val joinRequest = transaction {
                    var companyId: Int? = null
                    var projectId: Int? = null
                    var joinLinkId: Int? = null
                    var linkActive = true
                    var autoApproveExpectedEmployees = false

                    if (hasToken) {
                        exec(
                            """
                            SELECT
                                id,
                                company_id,
                                project_id,
                                is_active,
                                (expires_at IS NOT NULL AND expires_at < now()) AS expired,
                                max_uses,
                                used_count
                            FROM join_links
                            WHERE token = ${sqlStringOrNull(request.token)}
                            LIMIT 1
                            """.trimIndent()
                        ) { rs ->
                            if (rs.next()) {
                                val maxUses = resultSetIntOrNull(rs.getObject("max_uses"))
                                val usedCount = rs.getInt("used_count")
                                val maxUsesReached = maxUses != null && usedCount >= maxUses
                                val expired = rs.getBoolean("expired")

                                joinLinkId = rs.getInt("id")
                                companyId = rs.getInt("company_id")
                                projectId = resultSetIntOrNull(rs.getObject("project_id"))
                                linkActive = rs.getBoolean("is_active") && !expired && !maxUsesReached
                            }
                        }

                        if (companyId == null) error("join_link_not_found")
                        if (!linkActive) error("join_link_inactive")
                    } else if (hasCompanyId) {
                        exec(
                            """
                            SELECT c.id
                            FROM companies c
                            JOIN company_join_settings cjs ON cjs.company_id = c.id
                            WHERE c.id = ${request.companyId}
                              AND cjs.company_search_enabled = true
                            LIMIT 1
                            """.trimIndent()
                        ) { rs ->
                            if (rs.next()) companyId = rs.getInt("id")
                        }

                        if (companyId == null) error("company_not_found_or_search_disabled")
                    } else {
                        exec(
                            """
                            SELECT id
                            FROM companies
                            WHERE invite_code = ${sqlStringOrNull(request.companyCode)}
                            LIMIT 1
                            """.trimIndent()
                        ) { rs ->
                            if (rs.next()) companyId = rs.getInt("id")
                        }

                        if (companyId == null) error("company_not_found")
                    }

                    exec(
                        """
                        SELECT auto_approve_expected_employees
                        FROM company_join_settings
                        WHERE company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            autoApproveExpectedEmployees = rs.getBoolean("auto_approve_expected_employees")
                        }
                    }

                    var expectedEmployeeId: Int? = null
                    var matchStatus = "no_match"
                    exec(
                        """
                        SELECT id
                        FROM expected_employees
                        WHERE company_id = $companyId
                          AND (
                            (${sqlStringOrNull(request.email)} IS NOT NULL AND email IS NOT NULL AND lower(email) = lower(${sqlStringOrNull(request.email)}))
                            OR (${sqlStringOrNull(request.phone)} IS NOT NULL AND phone IS NOT NULL AND phone = ${sqlStringOrNull(request.phone)})
                            OR (${sqlStringOrNull(request.personnelNumber)} IS NOT NULL AND personnel_number IS NOT NULL AND personnel_number = ${sqlStringOrNull(request.personnelNumber)})
                          )
                        ORDER BY id DESC
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            expectedEmployeeId = rs.getInt("id")
                            matchStatus = "matched"
                        }
                    }

                    if (expectedEmployeeId == null && (!request.firstName.isNullOrBlank() || !request.lastName.isNullOrBlank())) {
                        matchStatus = "possible_match"
                    }

                    val shouldAutoApprove = expectedEmployeeId != null && autoApproveExpectedEmployees
                    val initialRequestStatus = if (shouldAutoApprove) "approved" else "pending"

                    exec(
                        """
                        INSERT INTO join_requests (
                            company_id,
                            project_id,
                            user_id,
                            expected_employee_id,
                            join_link_id,
                            source,
                            submitted_first_name,
                            submitted_last_name,
                            submitted_email,
                            submitted_phone,
                            submitted_personnel_number,
                            match_status,
                            status,
                            reviewed_at,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            $companyId,
                            ${sqlIntOrNull(projectId)},
                            $userId,
                            ${sqlIntOrNull(expectedEmployeeId)},
                            ${sqlIntOrNull(joinLinkId)},
                            '$normalizedSource',
                            ${sqlStringOrNull(request.firstName)},
                            ${sqlStringOrNull(request.lastName)},
                            ${sqlStringOrNull(request.email)},
                            ${sqlStringOrNull(request.phone)},
                            ${sqlStringOrNull(request.personnelNumber)},
                            '$matchStatus',
                            '$initialRequestStatus',
                            CASE WHEN $shouldAutoApprove THEN now() ELSE NULL END,
                            now(),
                            now()
                        )
                        """.trimIndent()
                    )

                    if (joinLinkId != null) {
                        exec(
                            """
                            UPDATE join_links
                            SET used_count = used_count + 1,
                                updated_at = now()
                            WHERE id = ${sqlIntOrNull(joinLinkId)}
                            """.trimIndent()
                        )
                    }

                    if (shouldAutoApprove) {
                        exec(
                            """
                            UPDATE users
                            SET company_id = $companyId,
                                is_company_admin = false,
                                status = 'active'
                            WHERE id = $userId
                            """.trimIndent()
                        )

                        exec(
                            """
                            UPDATE expected_employees
                            SET status = 'joined',
                                invite_status = 'joined',
                                joined_at = now(),
                                matched_user_id = $userId,
                                updated_at = now()
                            WHERE id = ${sqlIntOrNull(expectedEmployeeId)}
                              AND company_id = $companyId
                            """.trimIndent()
                        )
                    }

                    var response: JoinRequestResponse? = null
                    exec(
                        """
                        SELECT
                            id,
                            company_id,
                            project_id,
                            user_id,
                            expected_employee_id,
                            join_link_id,
                            source,
                            submitted_first_name,
                            submitted_last_name,
                            submitted_email,
                            submitted_phone,
                            submitted_personnel_number,
                            match_status,
                            status,
                            reviewed_by_user_id,
                            reviewed_at,
                            review_note,
                            created_at,
                            updated_at
                        FROM join_requests
                        WHERE user_id = $userId
                          AND company_id = $companyId
                        ORDER BY id DESC
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) response = readJoinRequest(rs)
                    }
                    response ?: error("join_request_not_created")
                }

                call.respond(HttpStatusCode.Created, joinRequest)
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                when {
                    message.contains("join_link_not_found") -> call.respond(HttpStatusCode.NotFound, ApiError("join_link_not_found", "Join link was not found"))
                    message.contains("join_link_inactive") -> call.respond(HttpStatusCode.BadRequest, ApiError("join_link_inactive", "Join link is inactive, expired or fully used"))
                    message.contains("company_not_found_or_search_disabled") -> call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("company_not_found_or_search_disabled", "Company was not found or company search is disabled")
                    )
                    message.contains("company_not_found") -> call.respond(HttpStatusCode.NotFound, ApiError("company_not_found", "Company was not found"))
                    else -> {
                        call.application.log.error("Failed to create join request", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
                    }
                }
            }
        }
    }
}

fun Route.publicWorkerJoinRoutes() {
    // Public join-link preview endpoint.
    // This must stay outside authenticate("bearerAuth") so workers can open invite links before login.
    route("/join-links") {
        // Returns safe company/project preview data for a token.
        get("/{token}") {
            val token = call.parameters["token"]?.trim().orEmpty()
            if (token.isBlank()) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("missing_token", "Join link token is required"))
            }

            try {
                val preview = transaction {
                    var response: JoinLinkPreviewResponse? = null
                    exec(
                        """
                        SELECT
                            jl.company_id,
                            c.name AS company_name,
                            jl.project_id,
                            p.title AS project_title,
                            jl.type,
                            jl.requires_approval,
                            jl.is_active,
                            (jl.expires_at IS NOT NULL AND jl.expires_at < now()) AS expired,
                            jl.max_uses,
                            jl.used_count
                        FROM join_links jl
                        JOIN companies c ON c.id = jl.company_id
                        LEFT JOIN projects p ON p.id = jl.project_id
                        WHERE jl.token = ${sqlStringOrNull(token)}
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            val maxUses = resultSetIntOrNull(rs.getObject("max_uses"))
                            val usedCount = rs.getInt("used_count")
                            val maxUsesReached = maxUses != null && usedCount >= maxUses
                            val expired = rs.getBoolean("expired")
                            val isActive = rs.getBoolean("is_active") && !expired && !maxUsesReached

                            response = JoinLinkPreviewResponse(
                                companyId = rs.getInt("company_id"),
                                companyName = rs.getString("company_name"),
                                projectId = resultSetIntOrNull(rs.getObject("project_id")),
                                projectTitle = rs.getString("project_title"),
                                type = rs.getString("type"),
                                requiresApproval = rs.getBoolean("requires_approval"),
                                active = isActive,
                                expired = expired
                            )
                        }
                    }
                    response
                }

                if (preview == null) {
                    return@get call.respond(HttpStatusCode.NotFound, ApiError("join_link_not_found", "Join link was not found"))
                }

                call.respond(HttpStatusCode.OK, preview)
            } catch (e: Exception) {
                call.application.log.error("Failed to preview join link", e)
                call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
            }
        }
    }
}
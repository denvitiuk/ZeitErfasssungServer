package com.yourcompany.zeiterfassung.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom

@Serializable
private data class CompanyInvitePackageResponse(
    val companyId: Int,
    val companyName: String,
    val companyCode: String?,
    val joinLinkId: Int,
    val joinLinkToken: String,
    val joinUrl: String,
    val inviteText: String
)

@Serializable
private data class CompanyOnboardingSummaryResponse(
    val companyId: Int,
    val companyName: String,
    val companyCode: String?,
    val simpleWorkerJoinEnabled: Boolean,
    val requiresAdminApproval: Boolean,
    val autoApproveExpectedEmployees: Boolean,
    val companySearchEnabled: Boolean,
    val activeJoinLinkCount: Int,
    val expectedEmployeesTotal: Int,
    val expectedEmployeesJoined: Int,
    val expectedEmployeesOpen: Int,
    val pendingJoinRequests: Int,
    val approvedJoinRequests: Int,
    val rejectedJoinRequests: Int,
    val hasInvitePackage: Boolean
)

private val inviteTokenRandom = SecureRandom()

private fun generateInviteJoinToken(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val body = buildString {
        repeat(40) {
            append(alphabet[inviteTokenRandom.nextInt(alphabet.length)])
        }
    }
    return "jn_$body"
}

private fun inviteJwtCompanyId(principal: JWTPrincipal): Int =
    principal.payload.getClaim("companyId").asInt() ?: 0

private fun inviteJwtUserId(principal: JWTPrincipal): Int? =
    principal.payload.getClaim("id").asString()?.toIntOrNull()
        ?: principal.payload.getClaim("id").asInt()

private fun inviteIsAdminForJwtCompany(principal: JWTPrincipal, companyId: Int): Boolean {
    val isCompanyAdmin = principal.payload.getClaim("isCompanyAdmin").asBoolean() ?: false
    val isGlobalAdmin = principal.payload.getClaim("isGlobalAdmin").asBoolean() ?: false
    val tokenCompanyId = principal.payload.getClaim("companyId").asInt() ?: 0
    return isGlobalAdmin || (isCompanyAdmin && tokenCompanyId == companyId)
}

private fun buildJoinUrl(protocol: String, host: String, token: String): String =
    "$protocol://$host/join/$token"

private fun buildGermanInviteText(companyName: String, joinUrl: String, companyCode: String?): String {
    val parts = mutableListOf(
        "Hallo,",
        "du wurdest eingeladen, der Firma $companyName in der Zeiterfassung-App beizutreten.",
        "Öffne diesen Link, um dich zu verbinden:\n$joinUrl"
    )

    companyCode
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { code ->
            parts.add("Falls der Link nicht funktioniert, kannst du diesen Firmen-Code eingeben: $code")
        }

    parts.add("Nach deiner Anfrage kann ein Admin deinen Zugang bestätigen.")

    return parts.joinToString("\n\n")
}

fun Route.companyInviteRoutes() {
    route("/companies/self") {
        // Returns everything the app needs for the screen:
        // Firma erstellt -> Mitarbeiter einladen.
        // If the company has no active company_join link yet, one is created automatically.
        get("/invite-package") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = inviteJwtCompanyId(principal)
            if (companyId <= 0) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }

            if (!inviteIsAdminForJwtCompany(principal, companyId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val adminUserId = inviteJwtUserId(principal)
            val protocol = call.request.header("X-Forwarded-Proto") ?: "http"
            val host = call.request.header("X-Forwarded-Host")
                ?: call.request.header("Host")
                ?: "localhost:8080"

            try {
                val invitePackage = transaction {
                    var companyName: String? = null
                    var companyCode: String? = null

                    exec(
                        """
                        SELECT id, name, invite_code
                        FROM companies
                        WHERE id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            companyName = rs.getString("name")
                            companyCode = rs.getString("invite_code")
                        }
                    }

                    if (companyName == null) {
                        error("company_not_found")
                    }

                    var joinLinkId: Int? = null
                    var joinLinkToken: String? = null

                    exec(
                        """
                        SELECT id, token
                        FROM join_links
                        WHERE company_id = $companyId
                          AND type = 'company_join'
                          AND is_active = true
                          AND (expires_at IS NULL OR expires_at > now())
                          AND (max_uses IS NULL OR used_count < max_uses)
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            joinLinkId = rs.getInt("id")
                            joinLinkToken = rs.getString("token")
                        }
                    }

                    if (joinLinkId == null || joinLinkToken == null) {
                        var token = generateInviteJoinToken()
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
                                        NULL,
                                        '$token',
                                        'company_join',
                                        true,
                                        true,
                                        'worker',
                                        NULL,
                                        0,
                                        NULL,
                                        ${adminUserId ?: "NULL"},
                                        now(),
                                        now()
                                    )
                                    """.trimIndent()
                                )
                                break
                            } catch (e: Exception) {
                                attempts += 1
                                if (attempts >= 5) throw e
                                token = generateInviteJoinToken()
                            }
                        }

                        exec(
                            """
                            SELECT id, token
                            FROM join_links
                            WHERE company_id = $companyId
                              AND token = '$token'
                            LIMIT 1
                            """.trimIndent()
                        ) { rs ->
                            if (rs.next()) {
                                joinLinkId = rs.getInt("id")
                                joinLinkToken = rs.getString("token")
                            }
                        }
                    }

                    val finalCompanyName = companyName ?: error("company_not_found")
                    val finalJoinLinkId = joinLinkId ?: error("join_link_not_created")
                    val finalJoinLinkToken = joinLinkToken ?: error("join_link_not_created")
                    val joinUrl = buildJoinUrl(protocol, host, finalJoinLinkToken)
                    val inviteText = buildGermanInviteText(finalCompanyName, joinUrl, companyCode)

                    CompanyInvitePackageResponse(
                        companyId = companyId,
                        companyName = finalCompanyName,
                        companyCode = companyCode,
                        joinLinkId = finalJoinLinkId,
                        joinLinkToken = finalJoinLinkToken,
                        joinUrl = joinUrl,
                        inviteText = inviteText
                    )
                }

                call.respond(HttpStatusCode.OK, invitePackage)
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                when {
                    message.contains("company_not_found") -> call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("company_not_found", "Company was not found")
                    )
                    else -> {
                        call.application.log.error("Failed to build invite package for company $companyId", e)
                        call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
                    }
                }
            }
        }

        // Admin dashboard summary for the worker onboarding setup.
        get("/onboarding-summary") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = inviteJwtCompanyId(principal)
            if (companyId <= 0) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }

            if (!inviteIsAdminForJwtCompany(principal, companyId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            try {
                val summary = transaction {
                    var companyName: String? = null
                    var companyCode: String? = null
                    var simpleWorkerJoinEnabled = false
                    var requiresAdminApproval = true
                    var autoApproveExpectedEmployees = true
                    var companySearchEnabled = false
                    var activeJoinLinkCount = 0
                    var expectedEmployeesTotal = 0
                    var expectedEmployeesJoined = 0
                    var expectedEmployeesOpen = 0
                    var pendingJoinRequests = 0
                    var approvedJoinRequests = 0
                    var rejectedJoinRequests = 0

                    exec(
                        """
                        SELECT
                            c.name,
                            c.invite_code,
                            COALESCE(cjs.simple_worker_join_enabled, false) AS simple_worker_join_enabled,
                            COALESCE(cjs.requires_admin_approval, true) AS requires_admin_approval,
                            COALESCE(cjs.auto_approve_expected_employees, true) AS auto_approve_expected_employees,
                            COALESCE(cjs.company_search_enabled, false) AS company_search_enabled
                        FROM companies c
                        LEFT JOIN company_join_settings cjs ON cjs.company_id = c.id
                        WHERE c.id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            companyName = rs.getString("name")
                            companyCode = rs.getString("invite_code")
                            simpleWorkerJoinEnabled = rs.getBoolean("simple_worker_join_enabled")
                            requiresAdminApproval = rs.getBoolean("requires_admin_approval")
                            autoApproveExpectedEmployees = rs.getBoolean("auto_approve_expected_employees")
                            companySearchEnabled = rs.getBoolean("company_search_enabled")
                        }
                    }

                    if (companyName == null) {
                        error("company_not_found")
                    }

                    exec(
                        """
                        SELECT COUNT(*) AS total
                        FROM join_links
                        WHERE company_id = $companyId
                          AND type = 'company_join'
                          AND is_active = true
                          AND (expires_at IS NULL OR expires_at > now())
                          AND (max_uses IS NULL OR used_count < max_uses)
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) activeJoinLinkCount = rs.getInt("total")
                    }

                    exec(
                        """
                        SELECT
                            COUNT(*) AS total,
                            COUNT(*) FILTER (WHERE status = 'joined') AS joined,
                            COUNT(*) FILTER (WHERE status IN ('expected', 'invited')) AS open
                        FROM expected_employees
                        WHERE company_id = $companyId
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            expectedEmployeesTotal = rs.getInt("total")
                            expectedEmployeesJoined = rs.getInt("joined")
                            expectedEmployeesOpen = rs.getInt("open")
                        }
                    }

                    exec(
                        """
                        SELECT
                            COUNT(*) FILTER (WHERE status = 'pending') AS pending,
                            COUNT(*) FILTER (WHERE status = 'approved') AS approved,
                            COUNT(*) FILTER (WHERE status = 'rejected') AS rejected
                        FROM join_requests
                        WHERE company_id = $companyId
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            pendingJoinRequests = rs.getInt("pending")
                            approvedJoinRequests = rs.getInt("approved")
                            rejectedJoinRequests = rs.getInt("rejected")
                        }
                    }

                    CompanyOnboardingSummaryResponse(
                        companyId = companyId,
                        companyName = companyName ?: error("company_not_found"),
                        companyCode = companyCode,
                        simpleWorkerJoinEnabled = simpleWorkerJoinEnabled,
                        requiresAdminApproval = requiresAdminApproval,
                        autoApproveExpectedEmployees = autoApproveExpectedEmployees,
                        companySearchEnabled = companySearchEnabled,
                        activeJoinLinkCount = activeJoinLinkCount,
                        expectedEmployeesTotal = expectedEmployeesTotal,
                        expectedEmployeesJoined = expectedEmployeesJoined,
                        expectedEmployeesOpen = expectedEmployeesOpen,
                        pendingJoinRequests = pendingJoinRequests,
                        approvedJoinRequests = approvedJoinRequests,
                        rejectedJoinRequests = rejectedJoinRequests,
                        hasInvitePackage = activeJoinLinkCount > 0 || !companyCode.isNullOrBlank()
                    )
                }

                call.respond(HttpStatusCode.OK, summary)
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                when {
                    message.contains("company_not_found") -> call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("company_not_found", "Company was not found")
                    )
                    else -> {
                        call.application.log.error("Failed to load onboarding summary for company $companyId", e)
                        call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
                    }
                }
            }
        }
    }
}

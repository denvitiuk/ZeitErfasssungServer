
package com.yourcompany.zeiterfassung.routes

import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class EasyWorkerFlowResponse(
    val companyId: Int,
    val simplifiedWorkerFlowEnabled: Boolean
)

@Serializable
data class UpdateEasyWorkerFlowRequest(
    val simplifiedWorkerFlowEnabled: Boolean
)

@Serializable
data class CompanySettingsError(
    val error: String
)

fun Route.companySettingsRoutes(dataSource: DataSource) {
    route("/admin/company/easy-worker-flow") {
        get {
            val adminUserId = call.currentUserIdOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    CompanySettingsError("unauthorized")
                )

            dataSource.connection.use { connection ->
                val admin = connection.prepareStatement(
                    """
                    SELECT id, company_id, is_company_admin, is_global_admin
                    FROM users
                    WHERE id = ?
                      AND is_active = true
                      AND deleted_at IS NULL
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, adminUserId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@use null

                        AdminCompanyAccess(
                            companyId = rs.getInt("company_id"),
                            isCompanyAdmin = rs.getBoolean("is_company_admin"),
                            isGlobalAdmin = rs.getBoolean("is_global_admin")
                        )
                    }
                } ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    CompanySettingsError("unauthorized")
                )

                if (!admin.isCompanyAdmin && !admin.isGlobalAdmin) {
                    return@get call.respond(
                        HttpStatusCode.Forbidden,
                        CompanySettingsError("company_admin_required")
                    )
                }

                val enabled = connection.prepareStatement(
                    """
                    SELECT COALESCE(simplified_worker_flow_enabled, false) AS simplified_worker_flow_enabled
                    FROM company_join_settings
                    WHERE company_id = ?
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, admin.companyId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getBoolean("simplified_worker_flow_enabled") else false
                    }
                }

                call.respond(
                    EasyWorkerFlowResponse(
                        companyId = admin.companyId,
                        simplifiedWorkerFlowEnabled = enabled
                    )
                )
            }
        }

        patch {
            val adminUserId = call.currentUserIdOrNull()
                ?: return@patch call.respond(
                    HttpStatusCode.Unauthorized,
                    CompanySettingsError("unauthorized")
                )

            val request = call.receive<UpdateEasyWorkerFlowRequest>()

            dataSource.connection.use { connection ->
                val admin = connection.prepareStatement(
                    """
                    SELECT id, company_id, is_company_admin, is_global_admin
                    FROM users
                    WHERE id = ?
                      AND is_active = true
                      AND deleted_at IS NULL
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, adminUserId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@use null

                        AdminCompanyAccess(
                            companyId = rs.getInt("company_id"),
                            isCompanyAdmin = rs.getBoolean("is_company_admin"),
                            isGlobalAdmin = rs.getBoolean("is_global_admin")
                        )
                    }
                } ?: return@patch call.respond(
                    HttpStatusCode.Unauthorized,
                    CompanySettingsError("unauthorized")
                )

                if (!admin.isCompanyAdmin && !admin.isGlobalAdmin) {
                    return@patch call.respond(
                        HttpStatusCode.Forbidden,
                        CompanySettingsError("company_admin_required")
                    )
                }

                connection.prepareStatement(
                    """
                    INSERT INTO company_join_settings (
                        company_id,
                        simple_worker_join_enabled,
                        requires_admin_approval,
                        auto_approve_expected_employees,
                        company_search_enabled,
                        simplified_worker_flow_enabled,
                        created_at,
                        updated_at
                    )
                    VALUES (?, false, false, false, false, ?, now(), now())
                    ON CONFLICT (company_id)
                    DO UPDATE SET
                        simplified_worker_flow_enabled = EXCLUDED.simplified_worker_flow_enabled,
                        updated_at = now()
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, admin.companyId)
                    statement.setBoolean(2, request.simplifiedWorkerFlowEnabled)
                    statement.executeUpdate()
                }

                call.respond(
                    EasyWorkerFlowResponse(
                        companyId = admin.companyId,
                        simplifiedWorkerFlowEnabled = request.simplifiedWorkerFlowEnabled
                    )
                )
            }
        }
    }
}

private data class AdminCompanyAccess(
    val companyId: Int,
    val isCompanyAdmin: Boolean,
    val isGlobalAdmin: Boolean
)

private fun io.ktor.server.application.ApplicationCall.currentUserIdOrNull(): Int? {
    val principal = principal<JWTPrincipal>() ?: return null
    val payload = principal.payload

    return payload.getClaim("userId").asInt()
        ?: payload.getClaim("userId").asString()?.toIntOrNull()
        ?: payload.getClaim("id").asInt()
        ?: payload.getClaim("id").asString()?.toIntOrNull()
        ?: payload.subject?.toIntOrNull()
}


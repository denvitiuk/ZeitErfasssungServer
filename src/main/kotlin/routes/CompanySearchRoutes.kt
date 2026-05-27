package com.yourcompany.zeiterfassung.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
private data class CompanySearchItemResponse(
    val companyId: Int,
    val companyName: String,
    val joinAvailable: Boolean
)

@Serializable
private data class CompanySearchResponse(
    val items: List<CompanySearchItemResponse>,
    val page: Int,
    val limit: Int,
    val total: Int,
    val totalPages: Int,
    val hasNextPage: Boolean,
    val hasPreviousPage: Boolean
)

@Serializable
private data class CompanyJoinPreviewResponse(
    val companyId: Int,
    val companyName: String,
    val joinAvailable: Boolean,
    val requiresAdminApproval: Boolean,
    val simpleWorkerJoinEnabled: Boolean,
    val autoApproveExpectedEmployees: Boolean
)

private fun companySearchSql(value: String): String =
    value
        .trim()
        .replace("'", "''")

private fun normalizeCompanySearchPage(value: String?): Int =
    value?.toIntOrNull()?.takeIf { it > 0 } ?: 1

private fun normalizeCompanySearchLimit(value: String?): Int =
    value?.toIntOrNull()?.coerceIn(1, 50) ?: 20

fun Route.companySearchRoutes() {
    route("/companies") {
        // Worker searches for a company by name before sending a join request.
        // Only companies with company_search_enabled=true are returned.
        get("/search") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val userId = principal.payload.getClaim("id").asString()?.toIntOrNull()
                ?: principal.payload.getClaim("id").asInt()
            if (userId == null || userId <= 0) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_user", "Token has no user id"))
            }

            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.length < 2) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("query_too_short", "q must contain at least 2 characters")
                )
            }

            val page = normalizeCompanySearchPage(call.request.queryParameters["page"])
            val limit = normalizeCompanySearchLimit(call.request.queryParameters["limit"])
            val offset = (page - 1) * limit
            val safeQuery = companySearchSql(query)
            val pattern = "%$safeQuery%"

            try {
                val response = transaction {
                    var total = 0
                    val items = mutableListOf<CompanySearchItemResponse>()

                    exec(
                        """
                        SELECT COUNT(*) AS total
                        FROM companies c
                        JOIN company_join_settings cjs ON cjs.company_id = c.id
                        WHERE cjs.company_search_enabled = true
                          AND c.name ILIKE '$pattern'
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            total = rs.getInt("total")
                        }
                    }

                    exec(
                        """
                        SELECT c.id, c.name
                        FROM companies c
                        JOIN company_join_settings cjs ON cjs.company_id = c.id
                        WHERE cjs.company_search_enabled = true
                          AND c.name ILIKE '$pattern'
                        ORDER BY c.name ASC, c.id ASC
                        LIMIT $limit OFFSET $offset
                        """.trimIndent()
                    ) { rs ->
                        while (rs.next()) {
                            items.add(
                                CompanySearchItemResponse(
                                    companyId = rs.getInt("id"),
                                    companyName = rs.getString("name"),
                                    joinAvailable = true
                                )
                            )
                        }
                    }

                    val totalPages = if (total == 0) 0 else ((total + limit - 1) / limit)

                    CompanySearchResponse(
                        items = items,
                        page = page,
                        limit = limit,
                        total = total,
                        totalPages = totalPages,
                        hasNextPage = totalPages > 0 && page < totalPages,
                        hasPreviousPage = page > 1 && totalPages > 0
                    )
                }

                call.respond(HttpStatusCode.OK, response)
            } catch (e: Exception) {
                call.application.log.error("Failed to search companies", e)
                call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
            }
        }

        // Worker opens a safe preview before pressing "Beitreten".
        // This endpoint does not expose invite_code or private company data.
        get("/{id}/join-preview") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val userId = principal.payload.getClaim("id").asString()?.toIntOrNull()
                ?: principal.payload.getClaim("id").asInt()
            if (userId == null || userId <= 0) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("no_user", "Token has no user id"))
            }

            val companyId = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("id_must_be_positive_int", "Company id must be a positive integer"))

            if (companyId <= 0) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiError("id_must_be_positive_int", "Company id must be a positive integer"))
            }

            try {
                val preview = transaction {
                    var response: CompanyJoinPreviewResponse? = null

                    exec(
                        """
                        SELECT
                            c.id,
                            c.name,
                            cjs.company_search_enabled,
                            cjs.requires_admin_approval,
                            cjs.simple_worker_join_enabled,
                            cjs.auto_approve_expected_employees
                        FROM companies c
                        JOIN company_join_settings cjs ON cjs.company_id = c.id
                        WHERE c.id = $companyId
                          AND cjs.company_search_enabled = true
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            response = CompanyJoinPreviewResponse(
                                companyId = rs.getInt("id"),
                                companyName = rs.getString("name"),
                                joinAvailable = rs.getBoolean("company_search_enabled"),
                                requiresAdminApproval = rs.getBoolean("requires_admin_approval"),
                                simpleWorkerJoinEnabled = rs.getBoolean("simple_worker_join_enabled"),
                                autoApproveExpectedEmployees = rs.getBoolean("auto_approve_expected_employees")
                            )
                        }
                    }

                    response
                }

                if (preview == null) {
                    return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("company_not_found_or_search_disabled", "Company was not found or company search is disabled")
                    )
                }

                call.respond(HttpStatusCode.OK, preview)
            } catch (e: Exception) {
                call.application.log.error("Failed to load company join preview for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
            }
        }
    }
}

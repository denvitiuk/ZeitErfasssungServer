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

@Serializable
private data class VerifyCompanyCodeRequest(val code: String)

@Serializable
private data class InviteCodeResponse(val code: String)
@Serializable
private data class InviteCodeSetRequest(val code: String)
@Serializable
private data class ApiError(val error: String, val detail: String? = null)

private fun String.isValidInvite(): Boolean =
    this.length in 8..16 && this.all { it.isLetterOrDigit() }

private fun normalizeCode(raw: String): String = raw.trim().uppercase()

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

fun Route.companiesRoutes() {
    route("/companies") {

        // GET /companies?name=...
        get {
            try {
                val inviteFilter = call.request.queryParameters["invite_code"]
                val nameFilter = call.request.queryParameters["name"]
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
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to fetch companies: ${e.message}")
                )
            }
        }

        // POST /companies/verify-code — validate a company code and return company details
        post("/verify-code") {
            try {
                val req = call.receive<VerifyCompanyCodeRequest>()
                val code = req.code.trim()
                if (code.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "code_required",
                            "detail" to "Provide non-empty company code"
                        )
                    )
                }

                val row = transaction {
                    Companies
                        .slice(Companies.id, Companies.name, Companies.inviteCode, Companies.createdAt)
                        .select { Companies.inviteCode eq code }
                        .singleOrNull()
                } ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "error" to "invalid_code",
                        "detail" to "No company found for the provided code"
                    )
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
                    mapOf(
                        "error" to "invalid_request",
                        "detail" to "Body must be JSON: {\"code\": \"...\"}"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "error" to "verify_failed",
                        "detail" to (e.message ?: "Unknown error")
                    )
                )
            }
        }

        // GET /companies/by-code/{code} — lookup by invite code via path param
        get("/by-code/{code}") {
            val raw = call.parameters["code"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "code_required")
            )
            val code = raw.trim()
            if (code.isEmpty()) {
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "code_required"))
            }

            val row = transaction {
                Companies
                    .slice(Companies.id, Companies.name, Companies.inviteCode, Companies.createdAt)
                    .select { Companies.inviteCode eq code }
                    .singleOrNull()
            } ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "invalid_code", "detail" to "No company found for the provided code")
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
                    return@put call.respond(HttpStatusCode.InternalServerError, ApiError("update_failed", e.message))
                }
            }
        }

        // GET /companies/{id}
        get("/{id}") {
            try {
                val idParam = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "id_required")
                )
                val id = idParam.toIntOrNull() ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "id_must_be_int")
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
                } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "company_not_found"))

                call.respond(HttpStatusCode.OK, company)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to fetch company: ${e.message}")
                )
            }
        }

        // POST /companies
        post {
            try {
                val req = call.receive<CompanyRequest>()

                val newCompany = transaction {
                    val id = Companies.insert {
                        it[Companies.name] = req.name
                    } get Companies.id

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

            } catch (e: ContentTransformationException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid request format: ${e.message}")
                )
            } catch (e: ExposedSQLException) {
                // 23505 = unique_violation in Postgres
                val pgCode = e.sqlState
                if (pgCode == "23505") {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "company_name_already_exists")
                    )
                }
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Database error: ${e.message}")
                )
            } catch (e: IllegalStateException) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Unexpected state: ${e.message}")
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Unexpected error: ${e.message}")
                )
            }
        }
    }
}

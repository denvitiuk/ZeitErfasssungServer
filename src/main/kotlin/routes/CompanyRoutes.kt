package com.yourcompany.zeiterfassung.routes

import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.exceptions.ExposedSQLException

import com.yourcompany.zeiterfassung.models.Company
import com.yourcompany.zeiterfassung.models.CompanyRequest
import com.yourcompany.zeiterfassung.tables.Companies

@Serializable
private data class VerifyCompanyCodeRequest(val code: String)

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
                        !nameFilter.isNullOrBlank()   -> base.select { Companies.name like "%${nameFilter}%" }
                        else                          -> base.selectAll()
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

        // GET /companies/{id}
        get("/{id}") {
            try {
                val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id_required"))
                val id = idParam.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id_must_be_int"))
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
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to fetch company: ${e.message}"))
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
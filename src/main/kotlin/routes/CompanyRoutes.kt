package com.yourcompany.zeiterfassung.routes

import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.exceptions.ExposedSQLException

import com.yourcompany.zeiterfassung.models.Company
import com.yourcompany.zeiterfassung.models.CompanyRequest
import com.yourcompany.zeiterfassung.tables.Companies

fun Route.companiesRoutes() {
    route("/companies") {

        // GET /companies?name=...
        get {
            try {
                val nameFilter = call.request.queryParameters["name"]
                val companies = transaction {
                    if (!nameFilter.isNullOrBlank()) {
                        Companies.select { Companies.name like "%${nameFilter}%" }
                            .map {
                                Company(
                                    it[Companies.id],
                                    it[Companies.name],
                                    it[Companies.inviteCode],
                                    it[Companies.createdAt]
                                )
                            }
                    } else {
                        Companies.selectAll()
                            .map {
                                Company(
                                    it[Companies.id],
                                    it[Companies.name],
                                    it[Companies.inviteCode],
                                    it[Companies.createdAt]
                                )
                            }
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
                                it[Companies.id],
                                it[Companies.name],
                                it[Companies.inviteCode],
                                it[Companies.createdAt]
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
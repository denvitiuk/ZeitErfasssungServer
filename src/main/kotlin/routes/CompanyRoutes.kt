package com.yourcompany.zeiterfassung.routes

import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

import com.yourcompany.zeiterfassung.models.Company
import com.yourcompany.zeiterfassung.models.CompanyRequest
import com.yourcompany.zeiterfassung.tables.Companies

fun Route.companiesRoutes() {
    route("/companies") {
        // GET /companies?name=...
        get {
            val nameFilter = call.request.queryParameters["name"]
            val companies = transaction {
                if (!nameFilter.isNullOrBlank()) {
                    Companies.select { Companies.name like "%${nameFilter}%" }
                        .map { Company(it[Companies.id], it[Companies.name], it[Companies.inviteCode], it[Companies.createdAt]) }
                } else {
                    Companies.selectAll()
                        .map { Company(it[Companies.id], it[Companies.name], it[Companies.inviteCode], it[Companies.createdAt]) }
                }
            }
            call.respond(companies)
        }
        // POST /companies
        post {
            val req = call.receive<CompanyRequest>()
            val newCompany = transaction {
                val id = Companies.insert {
                    it[Companies.name] = req.name
                } get Companies.id
                Companies.select { Companies.id eq id }
                    .map { Company(it[Companies.id], it[Companies.name], it[Companies.inviteCode], it[Companies.createdAt]) }
                    .single()
            }
            call.respond(HttpStatusCode.Created, newCompany)
        }
    }
}

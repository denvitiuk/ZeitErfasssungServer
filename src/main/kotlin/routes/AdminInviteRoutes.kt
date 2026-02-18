package com.yourcompany.zeiterfassung.routes

import com.yourcompany.zeiterfassung.dto.SendAdminInviteDTO
import com.yourcompany.zeiterfassung.dto.VerifyAdminInviteDTO
import com.yourcompany.zeiterfassung.dto.InviteResponse
import com.yourcompany.zeiterfassung.db.Companies
import com.yourcompany.zeiterfassung.service.EmailService
import com.yourcompany.zeiterfassung.service.EmailTemplates
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

fun Route.adminInviteRoutes(env: Dotenv) {

    // Отправить приглашение админа
    post("/send-admin-invite") {
        try {
            val dto = call.receive<SendAdminInviteDTO>()

            val companyRow = try {
                transaction {
                    Companies.select { Companies.id eq dto.companyId }.singleOrNull()
                }
            } catch (e: ExposedSQLException) {
                return@post call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Database error while fetching company: ${e.message}")
                )
            }

            if (companyRow == null) {
                return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Company not found"))
            }

            val code = companyRow[Companies.inviteCode]?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { newCode ->
                    try {
                        transaction {
                            Companies.update({ Companies.id eq dto.companyId }) {
                                it[Companies.inviteCode] = newCode
                            }
                        }
                    } catch (e: ExposedSQLException) {
                        return@post call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Database error while updating invite code: ${e.message}")
                        )
                    }
                }

            val html = try {
                EmailTemplates.buildAdminInviteHtml(code, companyRow[Companies.name])
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to build email HTML: ${e.message}")
                )
            }

            try {
                EmailService.send(
                    to = dto.to,
                    subject = "Einladung als Administrator",
                    body = html,
                    env = env
                )
            } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Email delivery failed: ${e.message}")
                )
            }

            call.respond(HttpStatusCode.OK, InviteResponse(result = "invite_sent"))

        } catch (e: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request format"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Unexpected error: ${e.message}"))
        }
    }

    // Проверить приглашение админа
    post("/verify-admin-invite") {
        try {
            val dto = call.receive<VerifyAdminInviteDTO>()

            val isValid = try {
                transaction {
                    Companies.select {
                        Companies.id eq dto.companyId and
                                (Companies.inviteCode eq dto.code)
                    }.any()
                }
            } catch (e: ExposedSQLException) {
                return@post call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Database error while verifying invite: ${e.message}")
                )
            }

            if (isValid) {
                call.respond(HttpStatusCode.OK, InviteResponse(result = "code_valid"))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or expired invite code"))
            }

        } catch (e: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request format"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Unexpected error: ${e.message}"))
        }
    }
}

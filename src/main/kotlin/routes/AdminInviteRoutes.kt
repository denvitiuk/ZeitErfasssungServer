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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * DTOs expected in com.yourcompany.zeiterfassung.dto package:
 * data class SendAdminInviteDTO(val to: String, val companyId: Int)
 * data class VerifyAdminInviteDTO(val companyId: Int, val code: String)
 * data class InviteResponse(val result: String)
 */

fun Route.adminInviteRoutes(env: Dotenv) {
    // Отправить приглашение админа
    post("/send-admin-invite") {
        val dto = call.receive<SendAdminInviteDTO>()

        // Проверяем, что компания существует
        val companyRow = transaction {
            Companies.select { Companies.id eq dto.companyId }.singleOrNull()
        }
        if (companyRow == null) {
            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Company not found"))
        }

        // Получаем или генерируем inviteCode
        val code = companyRow[Companies.inviteCode]?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { newCode ->
                transaction {
                    Companies.update({ Companies.id eq dto.companyId }) {
                        it[Companies.inviteCode] = newCode
                    }
                }
            }

        // Собираем тело письма
        val html = EmailTemplates.buildAdminInviteHtml(code, companyRow[Companies.name])
        val text = EmailTemplates.buildAdminInviteText(code, companyRow[Companies.name])

        try {
            EmailService.send(
                to = dto.to,
                subject = "Einladung als Administrator",
                body = html,
                env = env
            )
            // при желании можно отправить и текстовую версию
        } catch (e: Exception) {
            return@post call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.message ?: "Email delivery failed"))
            )
        }

        call.respond(HttpStatusCode.OK, InviteResponse(result = "invite_sent"))
    }

    // Проверить приглашение админа
    post("/verify-admin-invite") {
        val dto = call.receive<VerifyAdminInviteDTO>()

        val isValid = transaction {
            Companies.select {
                Companies.id eq dto.companyId and
                        (Companies.inviteCode eq dto.code)
            }.any()
        }
        if (isValid) {
            call.respond(HttpStatusCode.OK, InviteResponse(result = "code_valid"))
        } else {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or expired invite code"))
        }
    }
}
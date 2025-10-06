// SupportAutoReplyRoutes.kt
package com.yourcompany.zeiterfassung.routes

import com.yourcompany.zeiterfassung.db.Companies
import com.yourcompany.zeiterfassung.service.CodeUtil
import com.yourcompany.zeiterfassung.service.EmailService
import com.yourcompany.zeiterfassung.service.EmailTemplates
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class SupportEmailDTO(
    val from: String,
    val subject: String? = null,
    val text: String? = null,
    val html: String? = null
)

fun Route.supportAutoReplyRoutes(env: Dotenv) {

    post("/api/support/auto-reply") {
        // простая аутентификация вебхука + базовый лог
        println("[INBOUND] hit /api/support/auto-reply")
        val token = call.request.header("X-Inbound-Token")
        val tokenOk = (token == env["INBOUND_TOKEN"])
        println("[INBOUND] token ok? $tokenOk")
        if (!tokenOk) {
            return@post call.respond(HttpStatusCode.Forbidden)
        }

        val dto = call.receive<SupportEmailDTO>()
        println("[INBOUND] from=${dto.from} subject=${dto.subject}")
        val requesterEmail = dto.from.trim()

        // 1) Определяем базовое имя компании
        val baseName = extractCompanyBaseName(dto)

        // 2) Делаем имя уникальным: "Name", "Name 1", "Name 2", ...
        val finalName = ensureUniqueCompanyName(baseName)
        println("[INBOUND] baseName=$baseName finalName=$finalName")

        // 3) Создаём / находим компанию
        val companyId = transaction {
            Companies.select { Companies.name eq finalName }
                .singleOrNull()
                ?.get(Companies.id)?.value
                ?: Companies.insertAndGetId {
                    it[name] = finalName
                    it[createdAt] = LocalDateTime.now()
                }.value
        }
        println("[INBOUND] companyId=$companyId")

        // 4) Генерим и пишем inviteCode (колонка уже есть)
        val code = CodeUtil.randomCode(8, 4)
        transaction {
            Companies.update({ Companies.id eq companyId }) {
                it[inviteCode] = code
            }
        }
        println("[INBOUND] inviteCode=$code")

        // 5) Отправляем ровно твой HTML-шаблон (можешь заменить текст сабжа)
        val html = EmailTemplates.buildAdminInviteHtml(code, finalName)
        val text = EmailTemplates.buildAdminInviteText(code, finalName)
        EmailService.send(
            to = requesterEmail,
            subject = "Ihr Firmen-Code für ZeitErfassung",
            body = html,
            env = env
        )
        // если хочешь text/plain — расширь EmailService по примеру, что я показал ранее
        println("[INBOUND] email sent to=$requesterEmail")

        call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "companyName" to finalName, "code" to code))
    }
}

private fun extractCompanyBaseName(dto: SupportEmailDTO): String {
    val body = dto.text ?: dto.html?.replace(Regex("<[^>]*>"), " ") ?: ""
    Regex("(?i)\\b(firma|company|unternehmen|компан(ия|и|ий)|фірма)\\s*[:=]\\s*([\\p{L}\\p{N} .,&-]{2,})")
        .find(body)?.groupValues?.getOrNull(3)?.trim()?.let { return normalize(it) }

    dto.subject?.takeIf { it.isNotBlank() }?.trim()?.let { return normalize(it) }

    val local = dto.from.substringBefore("@")
        .replace('.', ' ').replace('_', ' ')
        .replace(Regex("\\d+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .joinToString(" ") { it.lowercase(Locale.getDefault()).replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }
        .ifBlank { "Neue Firma" }

    return normalize("$local Unternehmen")
}

private fun normalize(s: String) = s.replace(Regex("\\s+"), " ").trim().take(120)

private fun ensureUniqueCompanyName(base: String): String = transaction {
    val existing = Companies.slice(Companies.name)
        .select { Companies.name like "$base%" }
        .map { it[Companies.name] }
        .toSet()
    if (base !in existing) return@transaction base
    var i = 1
    while ("$base $i" in existing) i++
    "$base $i"
}
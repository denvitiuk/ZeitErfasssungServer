package com.yourcompany.zeiterfassung.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import com.yourcompany.zeiterfassung.dto.ScanRequest
import com.yourcompany.zeiterfassung.dto.ScanResponse
import com.yourcompany.zeiterfassung.models.Logs
import com.yourcompany.zeiterfassung.models.Nonces

import java.time.LocalDate
import org.jetbrains.exposed.sql.SortOrder

/**
 * Handles scanning of QR codes (in/out actions).
 */
fun Route.scanRoutes() {
    authenticate("bearerAuth") {
        post("/scan") {
            // 1. Получаем userId из JWT
            val principal = call.principal<JWTPrincipal>()!!
            val userId = principal.payload
                .getClaim("id")
                .asString()
                .toInt()

            // 2. Читаем тело запроса
            val req = call.receive<ScanRequest>()
            val now = Instant.now()

            // 3. Транзакционно проверяем nonce, пишем лог, помечаем used и возвращаем action
            val actionPerformed: String? = transaction {
                // Проверяем, что nonce существует и не был использован
                Nonces.select {
                    (Nonces.nonce eq req.nonce) and
                            (Nonces.used eq false) and
                            (Nonces.userId eq userId)
                }.firstOrNull() ?: return@transaction null

                // Определяем начало сегодняшнего дня (LocalDateTime)
                val todayStart = LocalDate.now().atStartOfDay()

                // Получаем последний лог этого пользователя за сегодня
                val lastAction = Logs.select {
                    (Logs.userId eq userId) and
                    (Logs.timestamp greaterEq todayStart)
                }
                .orderBy(Logs.timestamp, SortOrder.DESC)
                .limit(1)
                .map { it[Logs.action] }
                .firstOrNull()

                // Выбираем новое действие: если был "in" — ставим "out", иначе "in"
                val action = if (lastAction == "in") "out" else "in"

                Logs.insert {
                    it[Logs.userId]        = userId
                    it[Logs.terminalNonce] = req.nonce
                    it[Logs.action]        = action
                    it[Logs.timestamp]     = LocalDateTime.ofInstant(now, ZoneId.systemDefault())
                    it[Logs.latitude]      = req.latitude
                    it[Logs.longitude]     = req.longitude
                    it[Logs.locationDesc]  = req.locationDescription
                }

                Nonces.update({ Nonces.nonce eq req.nonce }) {
                    it[Nonces.used] = true
                }

                action
            }


            if (actionPerformed == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "invalid_or_used_nonce")
                )
            } else {
                call.respond(
                    ScanResponse(
                        status = "ok",
                        timestamp = now.toString(),
                        action = actionPerformed
                    )
                )
            }
        }
    }
}
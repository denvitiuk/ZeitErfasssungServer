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
import com.yourcompany.zeiterfassung.db.Projects
import com.yourcompany.zeiterfassung.dto.ProjectDTO
import org.jetbrains.exposed.dao.id.EntityID

import java.time.LocalDate
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq

// Resolve timezone from query ?tz=... or header X-Timezone; default to Europe/Berlin
private fun resolveZone(call: io.ktor.server.application.ApplicationCall): ZoneId {
    val q = call.request.queryParameters["tz"]
    val h = call.request.headers["X-Timezone"]
    val id = q ?: h ?: "Europe/Berlin"
    return try { ZoneId.of(id) } catch (_: Exception) { ZoneId.of("Europe/Berlin") }
}

private fun syncTrackingSessionFromScanLog(
    userId: Int,
    action: String,
    timestampUtc: LocalDateTime
) {
    try {
        when (action.lowercase()) {
            "in" -> {
                transaction {
                    exec(
                        """
                        INSERT INTO tracking_sessions (company_id, user_id, started_at, is_active)
                        SELECT u.company_id, u.id, '${timestampUtc}'::timestamp, TRUE
                          FROM users u
                         WHERE u.id = $userId
                           AND u.company_id IS NOT NULL
                           AND NOT EXISTS (
                                SELECT 1
                                  FROM tracking_sessions s
                                 WHERE s.user_id = u.id
                                   AND s.is_active = TRUE
                           )
                        """.trimIndent()
                    )
                }
                println("🧾 [scan→tracking] userId=$userId action=in startedAt=$timestampUtc")
            }

            "out" -> {
                transaction {
                    exec(
                        """
                        UPDATE tracking_sessions
                           SET is_active = FALSE,
                               ended_at = '${timestampUtc}'::timestamp
                         WHERE id = (
                                SELECT id
                                  FROM tracking_sessions
                                 WHERE user_id = $userId
                                   AND is_active = TRUE
                                 ORDER BY started_at DESC
                                 LIMIT 1
                         )
                        """.trimIndent()
                    )
                }
                println("🧾 [scan→tracking] userId=$userId action=out endedAt=$timestampUtc")
            }
        }
    } catch (t: Throwable) {
        println("⚠️ [scan→tracking] failed userId=$userId action=$action: ${t.message}")
    }
}

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
            val timestampUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
            val zone = resolveZone(call)
            call.response.headers.append("X-Timezone-Used", zone.id)

            // 3. Транзакционно проверяем nonce, пишем лог, помечаем used и возвращаем action
            val actionPerformed: String? = transaction {
                // Проверяем, что nonce существует и не был использован
                Nonces.select {
                    (Nonces.nonce eq req.nonce) and
                            (Nonces.used eq false) and
                            (Nonces.userId eq userId)
                }.firstOrNull() ?: return@transaction null

                // Определяем projectId из запроса (query/header). Если клиент его не прислал, оставим null.
                val projectIdFromQuery = call.request.queryParameters["projectId"]?.toIntOrNull()
                val projectIdFromHeader = call.request.headers["X-Project-Id"]?.toIntOrNull()
                val resolvedProjectId = projectIdFromQuery ?: projectIdFromHeader

                // Определяем начало сегодняшнего дня в зоне пользователя,
                // но переводим его в UTC, потому что в БД мы храним timestamp как UTC.
                val todayStartUtc = LocalDate.now(zone)
                    .atStartOfDay(zone)
                    .toInstant()
                    .let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }

                val baseCond = (Logs.userId eq userId) and (Logs.timestamp greaterEq todayStartUtc)
                val cond = if (resolvedProjectId != null) {
                    baseCond and (Logs.projectId eq EntityID(resolvedProjectId, Projects))
                } else baseCond

                val lastAction = Logs.select { cond }
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
                    // IMPORTANT: store UTC in DB to avoid timezone shifts (Berlin/Kyiv/etc.)
                    it[Logs.timestamp]     = timestampUtc
                    it[Logs.latitude]      = req.latitude
                    it[Logs.longitude]     = req.longitude
                    it[Logs.locationDesc]  = req.locationDescription
                    if (resolvedProjectId != null) {
                        it[Logs.projectId] = EntityID(resolvedProjectId, Projects)
                    }
                }

                syncTrackingSessionFromScanLog(userId, action, timestampUtc)

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

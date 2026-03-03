package com.yourcompany.zeiterfassung.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.sql.ResultSet
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.yourcompany.zeiterfassung.dto.QrResponse
import com.yourcompany.zeiterfassung.models.Nonces  // поправьте под свой пакет
import com.yourcompany.zeiterfassung.models.Logs

@kotlinx.serialization.Serializable
data class QrScanRequest(
    val nonce: String
)

@kotlinx.serialization.Serializable
data class QrScanResponse(
    val action: String,               // "in" | "out"
    val sessionId: String,
    val startedAt: String? = null,
    val endedAt: String? = null
)

fun Route.qrRoutes() {
    authenticate("bearerAuth") {
        get("/qr/generate") {
            // 1. Извлекаем userId из JWT
            val principal = call.principal<JWTPrincipal>()!!
            val userId = principal.payload
                .getClaim("id")
                .asString()
                .toInt()

            // 2. Генерируем неиспользованный nonce
            val newNonce = UUID.randomUUID().toString()
            val createdAt = Instant.now()
            transaction {
                Nonces.insert {
                    it[Nonces.nonce]     = newNonce
                    it[Nonces.createdAt] = LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC)
                    it[Nonces.used]      = false
                    it[Nonces.userId]    = userId
                    it[Nonces.workDate]  = LocalDate.now()
                }
            }

            // 3. Собираем PNG-байты QR-кода и кодируем в Base64
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(newNonce, BarcodeFormat.QR_CODE, 250, 250)
            val pngOutput = ByteArrayOutputStream()
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutput)
            val base64 = Base64.getEncoder().encodeToString(pngOutput.toByteArray())
            val dataUri = "data:image/png;base64,$base64"

            // 4. Отдаём клиенту DTO c nonce и картинкой
            call.respond(QrResponse(nonce = newNonce, qrCode = dataUri))
        }

        post("/qr/scan") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = principal.payload
                .getClaim("id")
                .asString()
                .toInt()

            val req = call.receive<QrScanRequest>()
            val nonce = req.nonce.trim()
            if (nonce.isBlank()) throw BadRequestException("Missing nonce")

            val nowInstant = Instant.now()
            val nowIso = nowInstant.toString() // e.g. 2026-03-03T10:38:37.726193Z

            // NOTE:
            // We intentionally use raw SQL for `tracking_sessions` so this file compiles even if
            // there is no Exposed Table object for that table.
            val resp: QrScanResponse = transaction {

                fun selectActiveSessionId(): String? {
                    // NOTE: id is UUID in Postgres; always cast explicitly to avoid silent no-op updates.
                    val sql = """
                        SELECT id::text AS id
                        FROM tracking_sessions
                        WHERE user_id = $userId AND is_active = TRUE
                        ORDER BY started_at DESC
                        LIMIT 1
                    """.trimIndent()

                    val idOrEmpty: String = exec(sql) { rs: ResultSet ->
                        if (rs.next()) (rs.getString("id") ?: "") else ""
                    } ?: ""

                    return idOrEmpty.takeIf { it.isNotBlank() }
                }

                fun stopSession(sessionId: String) {
                    // 1) Stop the конкретную активную сессию
                    val sqlStopOne = """
                        UPDATE tracking_sessions
                        SET is_active = FALSE,
                            ended_at  = '$nowIso'
                        WHERE id = '$sessionId'::uuid
                          AND is_active = TRUE
                    """.trimIndent()

                    exec(sqlStopOne)

                    // 2) Safety net: если по каким-то причинам у юзера осталось несколько активных сессий,
                    // закрываем все остальные тоже (чтобы QR-toggle не ломался).
                    val sqlStopOthers = """
                        UPDATE tracking_sessions
                        SET is_active = FALSE,
                            ended_at  = '$nowIso'
                        WHERE user_id = $userId
                          AND is_active = TRUE
                    """.trimIndent()

                    exec(sqlStopOthers)
                }

                fun startSession(): String {
                    val newId = UUID.randomUUID().toString()

                    // Safety: перед стартом новой — убедимся, что нет подвисших активных сессий.
                    val sqlCloseStale = """
                        UPDATE tracking_sessions
                        SET is_active = FALSE,
                            ended_at  = '$nowIso'
                        WHERE user_id = $userId
                          AND is_active = TRUE
                    """.trimIndent()
                    exec(sqlCloseStale)

                    val sql = """
                        INSERT INTO tracking_sessions (id, user_id, is_active, started_at)
                        VALUES ('$newId'::uuid, $userId, TRUE, '$nowIso')
                    """.trimIndent()

                    exec(sql)
                    return newId
                }

                // Lock nonce row to avoid double-scan races
                val nonceRow = Nonces
                    .select { Nonces.nonce eq nonce }
                    .forUpdate()
                    .limit(1)
                    .singleOrNull()
                    ?: throw BadRequestException("Invalid nonce")

                val nonceUserId = nonceRow[Nonces.userId]
                val used = nonceRow[Nonces.used]

                // Security: nonce must belong to this user
                if (nonceUserId != userId) throw BadRequestException("Nonce does not belong to this user")

                // We allow re-scans of the same nonce (same QR image) by the same user.
                // Keep `used=true` as a marker, but do NOT reject subsequent scans.
                if (!used) {
                    Nonces.update({ Nonces.nonce eq nonce }) {
                        it[Nonces.used] = true
                    }
                }

                // Toggle is based on ACTIVE SESSION
                val activeSessionId: String? = selectActiveSessionId()

                if (activeSessionId != null) {
                    val sid: String = activeSessionId

                    // OUT: stop current active session
                    stopSession(sid)

                    Logs.insert {
                        it[Logs.userId] = userId
                        it[Logs.terminalNonce] = nonce
                        it[Logs.action] = "out"
                        it[Logs.timestamp] = LocalDateTime.ofInstant(nowInstant, ZoneOffset.UTC)
                    }

                    QrScanResponse(
                        action = "out",
                        sessionId = sid,
                        endedAt = nowInstant.toString()
                    )
                } else {
                    // IN: start a new session
                    val newSessionId = startSession()

                    Logs.insert {
                        it[Logs.userId] = userId
                        it[Logs.terminalNonce] = nonce
                        it[Logs.action] = "in"
                        it[Logs.timestamp] = LocalDateTime.ofInstant(nowInstant, ZoneOffset.UTC)
                    }

                    QrScanResponse(
                        action = "in",
                        sessionId = newSessionId,
                        startedAt = nowInstant.toString()
                    )
                }
            }

            call.respond(HttpStatusCode.OK, resp)
        }
    }
}

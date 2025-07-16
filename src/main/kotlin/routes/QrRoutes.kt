package com.yourcompany.zeiterfassung.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.yourcompany.zeiterfassung.dto.QrResponse
import com.yourcompany.zeiterfassung.models.Nonces  // поправьте под свой пакет

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
                    it[Nonces.createdAt] = LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault())
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
    }
}
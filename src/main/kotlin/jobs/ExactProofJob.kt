package com.yourcompany.zeiterfassung.jobs

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import com.yourcompany.zeiterfassung.models.Proofs
import com.yourcompany.zeiterfassung.models.DeviceTokens
import com.yourcompany.zeiterfassung.dto.DeviceTokenDto
import com.yourcompany.zeiterfassung.dto.RespondProofRequest
import com.yourcompany.zeiterfassung.routes.loadAllDeviceTokens
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.quartz.Job
import org.quartz.JobExecutionContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ExactProofJob : Job {
    override fun execute(context: JobExecutionContext) {
        // 1) Создать proof-запись
        val newProofId = transaction {
            val now = Instant.now()
            Proofs.insert {
                it[userId] = /* тут можете взять userId из JobDataMap или фиксировать одного */
                    it[latitude] = /* фикс. координаты */
                    it[longitude] = /* фикс. координаты */
                    it[radius] = 100
                it[slot] = 0.toShort()
                it[sentAt] = LocalDateTime.ofInstant(now, ZoneId.of("Europe/Berlin"))
            } get Proofs.id
        }

        // 2) Загрузить все device tokens
        val tokens = loadAllDeviceTokens()

        // 3) Отправить пуши всем
        tokens.forEach { dto ->
            when(dto.platform) {
                "ios" -> sendApns(
                    deviceToken = dto.token,
                    payload = mapOf(
                        "aps" to mapOf("alert" to mapOf(
                            "title" to "🚨 Überprüfung fällig",
                            "body" to "Bitte tippe auf 'Überprüfen'."
                        )),
                        "proofId" to newProofId
                    )
                )
                "android" -> sendFcm(
                    fcmToken = dto.token,
                    data = mapOf(
                        "title" to "🚨 Überprüfung fällig",
                        "body" to "Bitte tippe auf 'Überprüfen'.",
                        "proofId" to newProofId.toString()
                    )
                )
            }
        }
    }
}
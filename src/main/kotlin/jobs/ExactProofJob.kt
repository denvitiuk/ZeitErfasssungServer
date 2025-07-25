package com.yourcompany.zeiterfassung.jobs

import com.yourcompany.zeiterfassung.dto.loadAllDeviceTokens
import com.yourcompany.zeiterfassung.models.Proofs
import com.yourcompany.zeiterfassung.push.FcmSender
import com.yourcompany.zeiterfassung.push.sendApns
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.quartz.Job
import org.quartz.JobExecutionContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ExactProofJob : Job {
    override fun execute(context: JobExecutionContext) {
        val newProofId = transaction {
            val now = Instant.now()
            Proofs.insert {
                it[Proofs.userId] = 123  // Пример ID
                it[Proofs.latitude] = 52.5200
                it[Proofs.longitude] = 13.4050
                it[Proofs.radius] = 100
                it[Proofs.slot] = 0.toShort()
                it[Proofs.sentAt] = LocalDateTime.ofInstant(now, ZoneId.of("Europe/Berlin"))
            } get Proofs.id
        }

        runBlocking {
            val tokens = loadAllDeviceTokens()

            val payloadIos = Json.encodeToString(
                mapOf(
                    "aps" to mapOf(
                        "alert" to mapOf(
                            "title" to "🚨 Überprüfung fällig",
                            "body" to "Bitte tippe auf 'Überprüfen'."
                        )
                    ),
                    "proofId" to newProofId.toString()
                )
            )

            tokens.forEach { dto ->
                when (dto.platform.lowercase()) {
                    "ios" -> sendApns(dto.token, payloadIos)
                    "android" -> FcmSender.send(
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
}

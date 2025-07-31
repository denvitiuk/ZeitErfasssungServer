package com.yourcompany.zeiterfassung.jobs

import com.yourcompany.zeiterfassung.dto.DeviceTokenDto
import com.yourcompany.zeiterfassung.dto.loadAllDeviceTokens
import com.yourcompany.zeiterfassung.models.Proofs
import com.yourcompany.zeiterfassung.push.FcmSender
import com.yourcompany.zeiterfassung.push.sendApns
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.quartz.Job
import org.quartz.JobExecutionContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Quartz job: creates a new proof for each user and sends push notifications at a random delay.
 */
class ExactProofJob : Job {

    companion object {
        private const val MAX_JITTER_MS: Long = 10 * 60_000L // 10 minutes
    }

    private val logger = org.slf4j.LoggerFactory.getLogger(ExactProofJob::class.java)

    override fun execute(context: JobExecutionContext) {
        runBlocking {
            try {
                val tokensByUser: Map<Int, List<DeviceTokenDto>> = loadAllDeviceTokens().groupBy { it.userId }

                tokensByUser.map { (userId, tokens) ->
                    async {
                        val jitter = Random.nextLong(0, MAX_JITTER_MS)
                        logger.info("Delaying push for user=$userId by ${jitter}ms")
                        delay(jitter)

                        val proofId = transaction {
                            val now = Instant.now()
                            Proofs.insert {
                                it[Proofs.userId] = userId
                                it[Proofs.latitude] = 52.5200 // Berlin latitude
                                it[Proofs.longitude] = 13.4050 // Berlin longitude
                                it[Proofs.radius] = 100
                                it[Proofs.slot] = 0.toShort() // TODO: slot based on Berlin time
                                it[Proofs.sentAt] = LocalDateTime.ofInstant(now, ZoneId.of("Europe/Berlin"))
                            } get Proofs.id
                        }

                        val payloadIos = Json.encodeToString(
                            mapOf(
                                "aps" to mapOf(
                                    "alert" to mapOf(
                                        "title" to "🚨 Überprüfung fällig",
                                        "body" to "Bitte tippe auf 'Überprüfen'."
                                    )
                                ),
                                "proofId" to proofId.toString()
                            )
                        )

                        val androidData = mapOf(
                            "title" to "🚨 Überprüfung fällig",
                            "body" to "Bitte tippe auf 'Überprüfen'.",
                            "proofId" to proofId.toString()
                        )

                        tokens.forEach { dto ->
                            try {
                                when (dto.platform.lowercase()) {
                                    "ios" -> sendApns(dto.token, payloadIos)
                                    "android" -> FcmSender.send(dto.token, androidData)
                                    else -> logger.warn("Unknown platform '${dto.platform}' for user=$userId")
                                }
                            } catch (e: Exception) {
                                logger.error("Failed to send push to user=$userId, token=${dto.token}", e)
                            }
                        }
                    }
                }.awaitAll()
            } catch (e: Exception) {
                logger.error("Error executing ExactProofJob", e)
            }
        }
    }
}

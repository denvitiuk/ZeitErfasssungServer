package com.yourcompany.zeiterfassung.service


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.yourcompany.zeiterfassung.service.events.AppEventPublisher
import com.yourcompany.zeiterfassung.service.events.AppEventPublisherFactory
import com.yourcompany.zeiterfassung.service.events.ProcessedAppEvent
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AppEventProcessorService(
    private val dataSource: DataSource,
    private val interval: Duration = 60.seconds,
    private val limit: Int = 100,
    private val publisher: AppEventPublisher = AppEventPublisherFactory.create()
) {
    private val payloadJson = Json { ignoreUnknownKeys = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return

        job = scope.launch {
            println("⚙️ [AppEventProcessorService] started interval=${interval.inWholeSeconds}s limit=$limit")

            while (isActive) {
                runCatching {
                    val result = processOnce()
                    if (result.processed > 0 || result.failed > 0) {
                        println(
                            "⚙️ [AppEventProcessorService] processed=${result.processed} " +
                                    "failed=${result.failed} skipped=${result.skipped}"
                        )
                    }
                }.onFailure { error ->
                    println("🛑 [AppEventProcessorService] failed: ${error.message ?: error::class.simpleName}")
                }

                delay(interval)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        println("⚙️ [AppEventProcessorService] stopped")
    }

    suspend fun processOnce(): AppEventProcessorServiceResult = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val originalAutoCommit = connection.autoCommit
            connection.autoCommit = false

            try {
                val candidates = selectProcessableEvents(
                    connection = connection,
                    limit = limit.coerceIn(1, 500)
                )

                val processedIds = mutableListOf<Long>()
                val failedIds = mutableListOf<Long>()
                val eventsToPublish = mutableListOf<ProcessedAppEvent>()
                var skipped = 0

                for (candidate in candidates) {
                    if (candidate.eventType !in processableEventTypes) {
                        skipped += 1
                        continue
                    }

                    val error = validateBusinessEvent(
                        connection = connection,
                        candidate = candidate
                    )

                    val marked = if (error == null) {
                        markEventProcessed(
                            connection = connection,
                            id = candidate.id
                        )
                    } else {
                        markEventFailed(
                            connection = connection,
                            id = candidate.id,
                            errorMessage = error
                        )
                    }

                    if (marked && error == null) {
                        processedIds += candidate.id
                        eventsToPublish += candidate.toProcessedAppEvent()
                    } else {
                        failedIds += candidate.id
                    }
                }

                connection.commit()
                connection.autoCommit = originalAutoCommit

                eventsToPublish.forEach { event ->
                    runCatching {
                        publisher.publish(event)
                        markKafkaPublished(event.id)
                    }.onFailure { error ->
                        val message = error.message ?: error::class.simpleName ?: "UNKNOWN_KAFKA_ERROR"
                        markKafkaPublishFailed(
                            id = event.id,
                            errorMessage = message
                        )

                        println(
                            "⚠️ [AppEventProcessorService] Kafka publish failed " +
                                    "id=${event.id} eventType=${event.eventType}: " +
                                    message
                        )
                    }
                }

                AppEventProcessorServiceResult(
                    requestedLimit = limit,
                    processed = processedIds.size,
                    skipped = skipped,
                    failed = failedIds.size,
                    processedIds = processedIds,
                    failedIds = failedIds
                )
            } catch (t: Throwable) {
                runCatching { connection.rollback() }
                connection.autoCommit = originalAutoCommit
                throw t
            }
        }
    }

    private data class AppEventCandidate(
        val id: Long,
        val eventId: String?,
        val eventType: String,
        val userId: Int,
        val companyId: Int?,
        val projectId: Int?,
        val sessionId: UUID?,
        val occurredAt: Timestamp?,
        val payloadRaw: String?,
        val payload: JsonObject?
    ) {
        fun toProcessedAppEvent(): ProcessedAppEvent = ProcessedAppEvent(
            id = id,
            eventId = eventId,
            eventType = eventType,
            userId = userId,
            companyId = companyId,
            projectId = projectId,
            sessionId = sessionId?.toString(),
            payload = payloadRaw,
            occurredAt = occurredAt?.toInstant()?.toString(),
            processedAt = java.time.Instant.now().toString()
        )
    }

    private fun selectProcessableEvents(
        connection: Connection,
        limit: Int
    ): List<AppEventCandidate> {
        connection.prepareStatement(
            """
            SELECT id, event_id, event_type, user_id, company_id, project_id, session_id, occurred_at, payload::text AS payload_text
            FROM app_events
            WHERE status = 'received'
              AND processed_at IS NULL
              AND event_type IN (
                'CLOCK_IN',
                'CLOCK_OUT',
                'LOCATION_PING',
                'BREAK_STARTED',
                'BREAK_ENDED',
                'DOCUMENT_REQUEST_CREATED',
                'SIGNATURE_SUBMITTED',
                'DOCUMENT_STATUS_CHANGED',
                'PROOF_CREATED',
                'PROOF_RESPONDED'
              )
            ORDER BY id ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)

            statement.executeQuery().use { resultSet ->
                val items = mutableListOf<AppEventCandidate>()

                while (resultSet.next()) {
                    val payloadRaw = resultSet.getString("payload_text")

                    items += AppEventCandidate(
                        id = resultSet.getLong("id"),
                        eventId = resultSet.getString("event_id"),
                        eventType = resultSet.getString("event_type"),
                        userId = resultSet.getInt("user_id"),
                        companyId = resultSet.getNullableInt("company_id"),
                        projectId = resultSet.getNullableInt("project_id"),
                        sessionId = resultSet.getObject("session_id") as? UUID,
                        occurredAt = resultSet.getTimestamp("occurred_at"),
                        payloadRaw = payloadRaw,
                        payload = parsePayload(payloadRaw)
                    )
                }

                return items
            }
        }
    }

    private fun validateBusinessEvent(
        connection: Connection,
        candidate: AppEventCandidate
    ): String? {
        return when (candidate.eventType) {
            "CLOCK_IN" -> validateTrackingSessionExists(
                connection = connection,
                candidate = candidate,
                expectedActive = true
            )

            "CLOCK_OUT" -> validateTrackingSessionExists(
                connection = connection,
                candidate = candidate,
                expectedActive = null
            )

            "LOCATION_PING" -> validateLocationPing(
                connection = connection,
                candidate = candidate
            )

            "BREAK_STARTED" -> validatePauseSessionExists(
                connection = connection,
                candidate = candidate,
                started = true
            )

            "BREAK_ENDED" -> validatePauseSessionExists(
                connection = connection,
                candidate = candidate,
                started = false
            )

            "DOCUMENT_REQUEST_CREATED" -> validateDocumentRequestCreated(
                connection = connection,
                candidate = candidate
            )

            "SIGNATURE_SUBMITTED" -> validateSignatureSubmitted(
                connection = connection,
                candidate = candidate
            )

            "DOCUMENT_STATUS_CHANGED" -> validateDocumentStatusChanged(
                connection = connection,
                candidate = candidate
            )

            "PROOF_CREATED" -> validateProofCreated(
                connection = connection,
                candidate = candidate
            )

            "PROOF_RESPONDED" -> validateProofResponded(
                connection = connection,
                candidate = candidate
            )

            else -> "UNSUPPORTED_EVENT_TYPE"
        }
    }

    private fun validateTrackingSessionExists(
        connection: Connection,
        candidate: AppEventCandidate,
        expectedActive: Boolean?
    ): String? {
        val sessionId = candidate.sessionId ?: return "SESSION_ID_REQUIRED"

        connection.prepareStatement(
            """
            SELECT is_active
            FROM tracking_sessions
            WHERE id = ?
              AND user_id = ?
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setInt(2, candidate.userId)

            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return "TRACKING_SESSION_NOT_FOUND"
                }

                if (expectedActive != null) {
                    val isActive = resultSet.getBoolean("is_active")
                    if (isActive != expectedActive) {
                        return "TRACKING_SESSION_STATE_MISMATCH"
                    }
                }

                return null
            }
        }
    }

    private fun validateLocationPing(
        connection: Connection,
        candidate: AppEventCandidate
    ): String? {
        val sessionError = validateTrackingSessionExists(
            connection = connection,
            candidate = candidate,
            expectedActive = null
        )
        if (sessionError != null) return sessionError

        val payload = candidate.payload ?: return "LOCATION_PAYLOAD_REQUIRED"
        payload.stringValue("lat")?.toDoubleOrNull()
            ?: return "LOCATION_LAT_REQUIRED"
        payload.stringValue("lon")?.toDoubleOrNull()
            ?: return "LOCATION_LON_REQUIRED"

        return null
    }

    private fun validatePauseSessionExists(
        connection: Connection,
        candidate: AppEventCandidate,
        started: Boolean
    ): String? {
        val occurredAt = candidate.occurredAt ?: return "OCCURRED_AT_REQUIRED"

        val timestampColumn = if (started) "started_at" else "ended_at"
        val error = if (started) "PAUSE_SESSION_START_NOT_FOUND" else "PAUSE_SESSION_END_NOT_FOUND"

        connection.prepareStatement(
            """
            SELECT id
            FROM pause_sessions
            WHERE user_id = ?
              AND $timestampColumn IS NOT NULL
              AND $timestampColumn BETWEEN (?::timestamptz - interval '10 minutes')
                                      AND (?::timestamptz + interval '10 minutes')
            ORDER BY ABS(EXTRACT(EPOCH FROM ($timestampColumn - ?::timestamptz))) ASC
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, candidate.userId)
            statement.setTimestamp(2, occurredAt)
            statement.setTimestamp(3, occurredAt)
            statement.setTimestamp(4, occurredAt)

            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) null else error
            }
        }
    }

    private fun validateDocumentRequestCreated(
        connection: Connection,
        candidate: AppEventCandidate
    ): String? {
        val requestId = candidate.payload?.stringValue("documentRequestId")?.toIntOrNull()
            ?: return "DOCUMENT_REQUEST_ID_REQUIRED"

        connection.prepareStatement(
            """
            SELECT id
            FROM document_requests
            WHERE id = ?
              AND user_id = ?
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, requestId)
            statement.setInt(2, candidate.userId)

            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) null else "DOCUMENT_REQUEST_NOT_FOUND"
            }
        }
    }

    private fun validateProofCreated(
        connection: Connection,
        candidate: AppEventCandidate
    ): String? {
        val proofId = candidate.payload?.stringValue("proofId")?.toIntOrNull()
            ?: return "PROOF_ID_REQUIRED"

        connection.prepareStatement(
            """
            SELECT id
            FROM proofs
            WHERE id = ?
              AND user_id = ?
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, proofId)
            statement.setInt(2, candidate.userId)

            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) null else "PROOF_NOT_FOUND"
            }
        }
    }

    private fun validateProofResponded(
        connection: Connection,
        candidate: AppEventCandidate
    ): String? {
        val proofId = candidate.payload?.stringValue("proofId")?.toIntOrNull()
            ?: return "PROOF_ID_REQUIRED"

        connection.prepareStatement(
            """
            SELECT id
            FROM proofs
            WHERE id = ?
              AND user_id = ?
              AND responded = true
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, proofId)
            statement.setInt(2, candidate.userId)

            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) null else "PROOF_RESPONSE_NOT_FOUND"
            }
        }
    }

    private fun validateSignatureSubmitted(
        connection: Connection,
        candidate: AppEventCandidate
    ): String? {
        val signatureId = candidate.payload?.stringValue("signatureId")?.toLongOrNull()
            ?: return "SIGNATURE_ID_REQUIRED"

        connection.prepareStatement(
            """
            SELECT id
            FROM document_request_signatures
            WHERE id = ?
              AND signer_user_id = ?
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, signatureId)
            statement.setLong(2, candidate.userId.toLong())

            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) null else "SIGNATURE_NOT_FOUND"
            }
        }
    }

    private fun validateDocumentStatusChanged(
        connection: Connection,
        candidate: AppEventCandidate
    ): String? {
        val requestId = candidate.payload?.stringValue("documentRequestId")?.toIntOrNull()
            ?: return "DOCUMENT_REQUEST_ID_REQUIRED"

        connection.prepareStatement(
            """
            SELECT id
            FROM document_requests
            WHERE id = ?
              AND company_id IS NOT DISTINCT FROM (
                SELECT company_id
                FROM app_events
                WHERE id = ?
                LIMIT 1
              )
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, requestId)
            statement.setLong(2, candidate.id)

            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) null else "DOCUMENT_REQUEST_NOT_FOUND"
            }
        }
    }

    private fun java.sql.ResultSet.getNullableInt(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    private fun parsePayload(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            payloadJson.parseToJsonElement(raw).jsonObject
        }.getOrNull()
    }

    private fun JsonObject.stringValue(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private fun markEventProcessed(
        connection: Connection,
        id: Long
    ): Boolean {
        connection.prepareStatement(
            """
            UPDATE app_events
            SET
              status = 'processed',
              processed_at = now(),
              error_message = NULL
            WHERE id = ?
              AND status = 'received'
              AND processed_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, id)
            return statement.executeUpdate() == 1
        }
    }

    private fun markEventFailed(
        connection: Connection,
        id: Long,
        errorMessage: String
    ): Boolean {
        connection.prepareStatement(
            """
            UPDATE app_events
            SET
              status = 'failed',
              processed_at = now(),
              error_message = ?
            WHERE id = ?
              AND status = 'received'
              AND processed_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, errorMessage)
            statement.setLong(2, id)
            return statement.executeUpdate() == 1
        }
    }

    private fun markKafkaPublished(id: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE app_events
                SET
                  kafka_published_at = now(),
                  kafka_publish_error = NULL,
                  kafka_publish_attempts = kafka_publish_attempts + 1
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, id)
                statement.executeUpdate()
            }
        }
    }

    private fun markKafkaPublishFailed(
        id: Long,
        errorMessage: String
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE app_events
                SET
                  kafka_publish_error = ?,
                  kafka_publish_attempts = kafka_publish_attempts + 1
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, errorMessage.take(2000))
                statement.setLong(2, id)
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        val processableEventTypes = setOf(
            "CLOCK_IN",
            "CLOCK_OUT",
            "LOCATION_PING",
            "BREAK_STARTED",
            "BREAK_ENDED",
            "DOCUMENT_REQUEST_CREATED",
            "SIGNATURE_SUBMITTED",
            "DOCUMENT_STATUS_CHANGED",
            "PROOF_CREATED",
            "PROOF_RESPONDED"
        )
    }
}

data class AppEventProcessorServiceResult(
    val requestedLimit: Int,
    val processed: Int,
    val skipped: Int,
    val failed: Int,
    val processedIds: List<Long>,
    val failedIds: List<Long>
)

package com.yourcompany.zeiterfassung.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class SyncEventsRequest(
    val deviceId: String,
    val source: String = "ios",
    val events: List<SyncEventDto>
)

@Serializable
data class SyncEventDto(
    val localId: String,
    val eventType: String,
    val userId: String? = null,
    val workerId: String? = null,
    val companyId: String? = null,
    val projectId: String? = null,
    val sessionId: String? = null,
    val entityType: String? = null,
    val entityLocalId: String? = null,
    val payloadJson: String? = null,
    val occurredAt: Long,
    val createdAt: Long
)

@Serializable
data class SyncEventsResponse(
    val accepted: List<AcceptedSyncEvent>,
    val rejected: List<RejectedSyncEvent>
)

@Serializable
data class AcceptedSyncEvent(
    val localId: String,
    val serverId: String? = null
)

@Serializable
data class RejectedSyncEvent(
    val localId: String,
    val reason: String
)

fun Route.syncEventRoutes(dataSource: DataSource) {
    authenticate("bearerAuth") {
        route("/sync") {
            post("/events") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val authenticatedUserId = principal.payload.getClaim("id").asString()?.toIntOrNull()
                    ?: principal.payload.getClaim("userId").asString()?.toIntOrNull()
                    ?: principal.payload.subject?.toIntOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Invalid JWT user id")
                    )

                val body = call.receive<SyncEventsRequest>()

                if (body.events.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.OK,
                        SyncEventsResponse(
                            accepted = emptyList(),
                            rejected = emptyList()
                        )
                    )
                }

                val accepted = mutableListOf<AcceptedSyncEvent>()
                val rejected = mutableListOf<RejectedSyncEvent>()

                body.events.forEach { event ->
                    val validationError = validateSyncEvent(event)
                    if (validationError != null) {
                        rejected += RejectedSyncEvent(
                            localId = event.localId,
                            reason = validationError
                        )
                        return@forEach
                    }

                    try {
                        insertAppEvent(
                            dataSource = dataSource,
                            userId = authenticatedUserId,
                            deviceId = body.deviceId,
                            source = body.source,
                            event = event
                        )

                        accepted += AcceptedSyncEvent(
                            localId = event.localId,
                            serverId = null
                        )
                    } catch (e: Throwable) {
                        rejected += RejectedSyncEvent(
                            localId = event.localId,
                            reason = e.message ?: "DB_INSERT_FAILED"
                        )
                    }
                }

                call.application.environment.log.info(
                    "[SyncEvents] userId=$authenticatedUserId deviceId=${body.deviceId} source=${body.source} accepted=${accepted.size} rejected=${rejected.size}"
                )

                call.respond(
                    HttpStatusCode.OK,
                    SyncEventsResponse(
                        accepted = accepted,
                        rejected = rejected
                    )
                )
            }
        }
    }
}

private fun validateSyncEvent(event: SyncEventDto): String? {
    if (event.localId.isBlank()) return "LOCAL_ID_EMPTY"
    if (event.eventType.isBlank()) return "EVENT_TYPE_EMPTY"
    if (event.occurredAt <= 0L) return "OCCURRED_AT_INVALID"
    if (event.createdAt <= 0L) return "CREATED_AT_INVALID"

    if (event.companyId != null && event.companyId.toIntOrNull() == null) {
        return "COMPANY_ID_INVALID"
    }

    if (event.projectId != null && event.projectId.toIntOrNull() == null) {
        return "PROJECT_ID_INVALID"
    }

    if (event.sessionId != null) {
        try {
            UUID.fromString(event.sessionId)
        } catch (_: Throwable) {
            return "SESSION_ID_INVALID"
        }
    }

    return null
}

private suspend fun insertAppEvent(
    dataSource: DataSource,
    userId: Int,
    deviceId: String,
    source: String,
    event: SyncEventDto
) = withContext(Dispatchers.IO) {
    dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO app_events (
                local_id,
                event_type,
                user_id,
                company_id,
                project_id,
                session_id,
                source,
                device_id,
                status,
                payload,
                occurred_at,
                client_created_at
            )
            VALUES (
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                'received',
                ?::jsonb,
                ?,
                ?
            )
            ON CONFLICT (user_id, local_id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, event.localId)
            statement.setString(2, event.eventType)
            statement.setInt(3, userId)

            val companyId = event.companyId?.toIntOrNull()
            if (companyId != null) {
                statement.setInt(4, companyId)
            } else {
                statement.setNull(4, Types.INTEGER)
            }

            val projectId = event.projectId?.toIntOrNull()
            if (projectId != null) {
                statement.setInt(5, projectId)
            } else {
                statement.setNull(5, Types.INTEGER)
            }

            if (event.sessionId != null) {
                statement.setObject(6, UUID.fromString(event.sessionId))
            } else {
                statement.setNull(6, Types.OTHER)
            }

            statement.setString(7, source.ifBlank { "unknown" })
            statement.setString(8, deviceId)
            statement.setString(9, event.payloadJson?.ifBlank { "{}" } ?: "{}")
            statement.setTimestamp(10, Timestamp.from(Instant.ofEpochMilli(event.occurredAt)))
            statement.setTimestamp(11, Timestamp.from(Instant.ofEpochMilli(event.createdAt)))

            statement.executeUpdate()
        }
    }
}

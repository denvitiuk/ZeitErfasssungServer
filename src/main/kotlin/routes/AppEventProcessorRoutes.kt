package com.yourcompany.zeiterfassung.routes

import com.yourcompany.zeiterfassung.service.AppEventProcessorService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Timestamp
import javax.sql.DataSource

@Serializable
data class AppEventProcessorResult(
    val requestedLimit: Int,
    val processed: Int,
    val skipped: Int,
    val failed: Int,
    val processedIds: List<Long>,
    val failedIds: List<Long>
)

@Serializable
data class AppEventListItem(
    val id: Long,
    val eventId: String?,
    val eventType: String,
    val source: String?,
    val userId: Int?,
    val companyId: Int?,
    val projectId: Int?,
    val sessionId: String?,
    val status: String,
    val payload: String?,
    val errorMessage: String?,
    val occurredAt: String?,
    val receivedAt: String?,
    val processedAt: String?
)

@Serializable
data class AppEventListResponse(
    val limit: Int,
    val eventType: String?,
    val status: String?,
    val items: List<AppEventListItem>
)

@Serializable
data class AppEventSummaryItem(
    val eventType: String,
    val status: String,
    val count: Long,
    val lastReceivedAt: String?,
    val lastProcessedAt: String?
)

@Serializable
data class AppEventSummaryResponse(
    val eventType: String?,
    val status: String?,
    val items: List<AppEventSummaryItem>
)

fun Route.appEventProcessorRoutes(dataSource: DataSource) {
    authenticate("bearerAuth") {
        route("/admin/app-events") {
            get("/summary") {
                val eventType = call.request.queryParameters["eventType"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val status = call.request.queryParameters["status"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val items = summarizeAppEvents(
                    dataSource = dataSource,
                    eventType = eventType,
                    status = status
                )

                call.respond(
                    HttpStatusCode.OK,
                    AppEventSummaryResponse(
                        eventType = eventType,
                        status = status,
                        items = items
                    )
                )
            }

            get {
                val limit = call.request.queryParameters["limit"]
                    ?.toIntOrNull()
                    ?.coerceIn(1, 500)
                    ?: 100

                val eventType = call.request.queryParameters["eventType"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val status = call.request.queryParameters["status"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val items = listAppEvents(
                    dataSource = dataSource,
                    eventType = eventType,
                    status = status,
                    limit = limit
                )

                call.respond(
                    HttpStatusCode.OK,
                    AppEventListResponse(
                        limit = limit,
                        eventType = eventType,
                        status = status,
                        items = items
                    )
                )
            }

            post("/process") {
                val limit = call.request.queryParameters["limit"]
                    ?.toIntOrNull()
                    ?.coerceIn(1, 500)
                    ?: 100

                val serviceResult = AppEventProcessorService(
                    dataSource = dataSource,
                    limit = limit
                ).processOnce()

                call.respond(
                    HttpStatusCode.OK,
                    AppEventProcessorResult(
                        requestedLimit = serviceResult.requestedLimit,
                        processed = serviceResult.processed,
                        skipped = serviceResult.skipped,
                        failed = serviceResult.failed,
                        processedIds = serviceResult.processedIds,
                        failedIds = serviceResult.failedIds
                    )
                )
            }
        }
    }
}

private suspend fun summarizeAppEvents(
    dataSource: DataSource,
    eventType: String?,
    status: String?
): List<AppEventSummaryItem> = withContext(Dispatchers.IO) {
    dataSource.connection.use { connection ->
        val where = mutableListOf<String>()
        val params = mutableListOf<String>()

        if (eventType != null) {
            where += "event_type = ?"
            params += eventType
        }

        if (status != null) {
            where += "status = ?"
            params += status
        }

        val whereSql = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"

        connection.prepareStatement(
            """
            SELECT
              event_type,
              status,
              COUNT(*) AS event_count,
              MAX(received_at) AS last_received_at,
              MAX(processed_at) AS last_processed_at
            FROM app_events
            $whereSql
            GROUP BY event_type, status
            ORDER BY event_type ASC, status ASC
            """.trimIndent()
        ).use { statement ->
            params.forEachIndexed { index, value ->
                statement.setString(index + 1, value)
            }

            statement.executeQuery().use { resultSet ->
                val items = mutableListOf<AppEventSummaryItem>()

                while (resultSet.next()) {
                    items += AppEventSummaryItem(
                        eventType = resultSet.getString("event_type"),
                        status = resultSet.getString("status"),
                        count = resultSet.getLong("event_count"),
                        lastReceivedAt = resultSet.getTimestamp("last_received_at").toIsoStringOrNull(),
                        lastProcessedAt = resultSet.getTimestamp("last_processed_at").toIsoStringOrNull()
                    )
                }

                items
            }
        }
    }
}

private suspend fun listAppEvents(
    dataSource: DataSource,
    eventType: String?,
    status: String?,
    limit: Int
): List<AppEventListItem> = withContext(Dispatchers.IO) {
    dataSource.connection.use { connection ->
        val where = mutableListOf<String>()
        val params = mutableListOf<String>()

        if (eventType != null) {
            where += "event_type = ?"
            params += eventType
        }

        if (status != null) {
            where += "status = ?"
            params += status
        }

        val whereSql = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"

        connection.prepareStatement(
            """
            SELECT
              id,
              event_id,
              event_type,
              source,
              user_id,
              company_id,
              project_id,
              session_id,
              status,
              payload::text AS payload_text,
              error_message,
              occurred_at,
              received_at,
              processed_at
            FROM app_events
            $whereSql
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            var index = 1
            params.forEach { value ->
                statement.setString(index++, value)
            }
            statement.setInt(index, limit)

            statement.executeQuery().use { resultSet ->
                val items = mutableListOf<AppEventListItem>()

                while (resultSet.next()) {
                    items += AppEventListItem(
                        id = resultSet.getLong("id"),
                        eventId = resultSet.getString("event_id"),
                        eventType = resultSet.getString("event_type"),
                        source = resultSet.getString("source"),
                        userId = resultSet.getNullableInt("user_id"),
                        companyId = resultSet.getNullableInt("company_id"),
                        projectId = resultSet.getNullableInt("project_id"),
                        sessionId = resultSet.getString("session_id"),
                        status = resultSet.getString("status"),
                        payload = resultSet.getString("payload_text"),
                        errorMessage = resultSet.getString("error_message"),
                        occurredAt = resultSet.getTimestamp("occurred_at").toIsoStringOrNull(),
                        receivedAt = resultSet.getTimestamp("received_at").toIsoStringOrNull(),
                        processedAt = resultSet.getTimestamp("processed_at").toIsoStringOrNull()
                    )
                }

                items
            }
        }
    }
}

private fun java.sql.ResultSet.getNullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

private fun Timestamp?.toIsoStringOrNull(): String? = this?.toInstant()?.toString()

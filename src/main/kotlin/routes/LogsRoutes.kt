// src/main/kotlin/com/yourcompany/zeiterfassung/routes/LogsRoutes.kt
package com.yourcompany.zeiterfassung.routes

import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.yourcompany.zeiterfassung.dto.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.yourcompany.zeiterfassung.models.Logs
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Retrieves attendance logs for the authenticated user.
 */
fun Route.logsRoutes() {
    authenticate("bearerAuth") {
        get("/logs") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = principal.payload
                .getClaim("id")
                .asString()
                .toInt()
            val entries = transaction {
                Logs
                    .select { Logs.userId eq userId }
                    .orderBy(Logs.timestamp, SortOrder.DESC)
                    .map {
                        LogEntryDTO(
                            employeeId = it[Logs.userId].toString(),
                            action = it[Logs.action],
                            timestamp = it[Logs.timestamp]
                                .atZone(ZoneOffset.UTC)
                                .withZoneSameInstant(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        )
                    }
            }
            call.respond(entries)
        }
    }
}

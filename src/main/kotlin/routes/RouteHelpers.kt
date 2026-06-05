package com.yourcompany.zeiterfassung.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.ResultSet
import java.util.UUID

// Shared route helpers for modules that should not depend on trackingRoutes.kt.
// Keep generic helpers here so feature routes like ZeitPlan, Tracking, Pause, etc. can evolve separately.

data class RouteUserContext(
    val userId: Long,
    val companyId: Int,
    val email: String? = null
)

fun ApplicationCall.requireUserId(): Long {
    val principal = principal<JWTPrincipal>()
        ?: throw BadRequestException("Missing auth principal")

    val rawUserId = principal.payload.getClaim("userId").asLong()
        ?: principal.payload.getClaim("user_id").asLong()
        ?: principal.payload.subject?.toLongOrNull()
        ?: throw BadRequestException("Missing user id in token")

    return rawUserId
}

fun loadUserContext(userId: Long): RouteUserContext = transaction {
    queryOne(
        """
        SELECT id, company_id, email
        FROM users
        WHERE id = ?
        LIMIT 1
        """.trimIndent(),
        listOf(userId)
    ) { rs ->
        val companyId = rs.getObject("company_id")
            ?: throw BadRequestException("User is not assigned to a company")

        RouteUserContext(
            userId = rs.getLong("id"),
            companyId = (companyId as Number).toInt(),
            email = rs.getString("email")
        )
    } ?: throw NotFoundException("User not found")
}

fun parseUuid(value: String?, field: String = "id"): UUID {
    if (value.isNullOrBlank()) {
        throw BadRequestException("Missing $field")
    }

    return try {
        UUID.fromString(value)
    } catch (_: Throwable) {
        throw BadRequestException("Invalid $field")
    }
}


fun <T> Transaction.queryOne(
    sql: String,
    params: List<Any> = emptyList(),
    mapper: (ResultSet) -> T
): T? {
    val stmt = connection.prepareStatement(sql, false)
    try {
        params.forEachIndexed { index, value ->
            stmt.set(index + 1, value)
        }

        val rs = stmt.executeQuery()
        rs.use {
            return if (it.next()) mapper(it) else null
        }
    } finally {
        // Exposed manages this statement resource inside the transaction.
    }
}

package com.yourcompany.zeiterfassung.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.ResultSet
import java.util.UUID

data class RouteUserContext(
    val userId: Long,
    val companyId: Int,
    val email: String? = null
)

fun ApplicationCall.routeRequireUserId(): Long {
    val principal = principal<JWTPrincipal>()
        ?: throw BadRequestException("Missing auth principal")

    return principal.payload.getClaim("id").asString()?.toLongOrNull()
        ?: principal.payload.getClaim("id").asLong()
        ?: principal.payload.getClaim("userId").asLong()
        ?: principal.payload.getClaim("user_id").asLong()
        ?: principal.payload.subject?.toLongOrNull()
        ?: throw BadRequestException("Missing user id in token")
}

fun routeLoadUserContext(userId: Long): RouteUserContext = transaction {
    routeQueryOne(
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

fun routeParseUuid(value: String?, field: String = "id"): UUID {
    if (value.isNullOrBlank()) {
        throw BadRequestException("Missing $field")
    }

    return try {
        UUID.fromString(value)
    } catch (_: Throwable) {
        throw BadRequestException("Invalid $field")
    }
}

fun <T> Transaction.routeQueryOne(
    sql: String,
    params: List<Any> = emptyList(),
    mapper: (ResultSet) -> T
): T? {
    val stmt = connection.prepareStatement(sql, false)

    params.forEachIndexed { index, value ->
        stmt.set(index + 1, value)
    }

    val rs = stmt.executeQuery()

    rs.use {
        return if (it.next()) mapper(it) else null
    }
}
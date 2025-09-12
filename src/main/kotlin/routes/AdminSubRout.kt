package com.yourcompany.zeiterfassung.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction


@Serializable
data class EntitlementsDto(
    val companyId: Int,
    val companyName: String?,
    val paid: Boolean,
    val reason: String?,
    val graceUntil: String?,
    val priceId: String?,
    val seatsUsed: Int,
    val seatsLimit: Int,
    val expiresAt: String?,

    // Compatibility fields for callers that use these names
    val proActive: Boolean = false, // mirrors `paid`
    val productId: String? = null,  // mirrors `priceId`
    val source: String = "db"       // indicates the data source
)

/**
 * GET /api/me/entitlements?companyId=123
 * Возвращает сводку прав (paid/unpaid, причина) и лимиты мест для компании.
 * Основано на вьюхе v_company_entitlements (см. SQL миграцию из предыдущего шага).
 *
 * Подключение:
 * routing { registerEntitlementsRoutes() }
 */
fun Route.registerEntitlementsRoutes() {
    route("/api") {
        get("/me/entitlements") {
            val companyIdParam = call.request.queryParameters["companyId"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "missing_companyId")
                )

            val companyId = companyIdParam.toIntOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "invalid_companyId")
                )

            val dto: EntitlementsDto? = transaction {
                var result: EntitlementsDto? = null
                val sql = """
                    SELECT
                      company_id,
                      company_name,
                      paid,
                      reason,
                      grace_until,
                      price_id,
                      seats_used,
                      seats_limit
                    FROM v_company_entitlements
                    WHERE company_id = $companyId
                    LIMIT 1
                """.trimIndent()

                TransactionManager.current().exec(sql) { rs ->
                    if (rs.next()) {
                        result = EntitlementsDto(
                            companyId = rs.getInt("company_id"),
                            companyName = rs.getString("company_name"),
                            paid = rs.getBoolean("paid"),
                            reason = rs.getString("reason"),
                            graceUntil = rs.getTimestamp("grace_until")?.toInstant()?.toString(),
                            priceId = rs.getString("price_id"),
                            seatsUsed = rs.getInt("seats_used"),
                            seatsLimit = rs.getInt("seats_limit"),
                            // if there's no dedicated expires_at column, mirror grace_until
                            expiresAt = rs.getTimestamp("grace_until")?.toInstant()?.toString(),
                            proActive = rs.getBoolean("paid"),
                            productId = rs.getString("price_id"),
                            source = "db"
                        )
                    }
                }
                result
            }

            if (dto == null) {
                return@get call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "company_not_found")
                )
            }

            call.respond(dto)
        }
    }
}

package com.yourcompany.zeiterfassung.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.javatime.timestamp
import kotlinx.datetime.Instant as KInstant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.Contextual
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

private fun ResultRow.toEntitlementsDTO(): EntitlementsDTO = EntitlementsDTO(
    companyId   = this[VCompanyEntitlements.companyId],
    companyName = this[VCompanyEntitlements.companyName],
    paid        = this[VCompanyEntitlements.paid],
    reason      = this[VCompanyEntitlements.reason],
    graceUntil  = this[VCompanyEntitlements.graceUntil]?.toKotlinInstant(),
    priceId     = this[VCompanyEntitlements.priceId],
    seatsUsed   = this[VCompanyEntitlements.seatsUsed],
    seatsLimit  = this[VCompanyEntitlements.seatsLimit]
)

private fun loadEntitlements(companyId: Int): EntitlementsDTO? = transaction {
    VCompanyEntitlements
        .select { VCompanyEntitlements.companyId eq companyId }
        .limit(1)
        .firstOrNull()
        ?.toEntitlementsDTO()
}

/**
 * Billing-related HTTP routes.
 *
 * Endpoints:
 *  POST /billing/rc/webhook     – RevenueCat webhook receiver (JSON). Stores & applies via SQL fn apply_revenuecat_event(jsonb).
 *  GET  /billing/entitlements   – Returns current seats + paid status for a company (from v_company_entitlements).
 *  GET  /billing/events         – Last N RevenueCat events for quick debugging (from revenuecat_events).
 *
 * Notes:
 *  • SQL helper function `apply_revenuecat_event(jsonb)` must exist in the DB.
 *  • Views used here: v_company_entitlements (columns: company_id, company_name, paid, reason, grace_until, price_id, seats_used, seats_limit).
 */
fun Route.registerBillingRoutes() {
    route("/billing") {
        /** RevenueCat webhook */
        post("/rc/webhook") {
            val signature = call.request.headers["RevenueCat-Signature"]
            val body = call.receiveText()
            call.application.environment.log.info("[RC] webhook sig=$signature bytes=${body.length}")

            try {
                transaction {
                    // addLogger(StdOutSqlLogger)
                    val payload = sqlQuote(body)
                    exec("SELECT apply_revenuecat_event('${payload}'::jsonb)")
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            } catch (t: Throwable) {
                call.application.environment.log.error("[RC] webhook error", t)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("status" to "error", "message" to (t.message ?: "unknown"))
                )
            }
        }

        /** Entitlements for a company (simple read API for clients/admin). */
        get("/entitlements") {
            val companyId = call.request.queryParameters["companyId"]?.toIntOrNull()
            if (companyId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "companyId is required"))
                return@get
            }

            try {
                val dto = loadEntitlements(companyId)
                if (dto == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "company not found"))
                } else {
                    call.respond(dto)
                }
            } catch (t: Throwable) {
                call.application.environment.log.error("[Billing] entitlements error", t)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (t.message ?: "unknown"))
                )
            }
        }

        authenticate("bearerAuth") {
            get("/entitlements/self") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))

                val companyId = principal.payload.getClaim("companyId").asInt() ?: 0
                if (companyId <= 0) {
                    return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no_company"))
                }

                try {
                    val dto = loadEntitlements(companyId)
                    if (dto == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "company not found"))
                    } else {
                        call.respond(dto)
                    }
                } catch (t: Throwable) {
                    call.application.environment.log.error("[Billing] entitlements/self error", t)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (t.message ?: "unknown"))
                    )
                }
            }
        }

        /** Recent RevenueCat events for quick debugging/visibility in dev tools. */
        get("/events") {
            val companyId = call.request.queryParameters["companyId"]?.toIntOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50

            try {
                val events = transaction {
                    val base = if (companyId != null) {
                        RevenuecatEvents.select { RevenuecatEvents.companyId eq companyId }
                    } else {
                        RevenuecatEvents.selectAll()
                    }

                    base
                        .orderBy(RevenuecatEvents.id to SortOrder.DESC)
                        .limit(limit)
                        .map { row ->
                            RcEventDTO(
                                id = row[RevenuecatEvents.id],
                                receivedAt = row[RevenuecatEvents.receivedAt].toKotlinInstant(),
                                companyId = row[RevenuecatEvents.companyId],
                                rcAppUserId = row[RevenuecatEvents.rcAppUserId],
                                environment = row[RevenuecatEvents.environment],
                                productId = row[RevenuecatEvents.productId],
                                eventType = row[RevenuecatEvents.eventType],
                                originalTransactionId = row[RevenuecatEvents.originalTransactionId],
                                expirationAt = row[RevenuecatEvents.expirationAt]?.toKotlinInstant(),
                                willRenew = row[RevenuecatEvents.willRenew]
                            )
                        }
                }
                call.respond(events)
            } catch (t: Throwable) {
                call.application.environment.log.error("[Billing] events error", t)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (t.message ?: "unknown")))
            }
        }

        /** Health ping */
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
    }
}

private fun sqlQuote(s: String): String = s.replace("'", "''")

// === Exposed table/view mappings ===
private object VCompanyEntitlements : Table("v_company_entitlements") {
    val companyId = integer("company_id")
    val companyName = text("company_name")
    val paid = bool("paid")
    val reason = text("reason").nullable()
    val graceUntil = timestamp("grace_until").nullable() // java.time.Instant
    val priceId = text("price_id").nullable()
    val seatsUsed = integer("seats_used")
    val seatsLimit = integer("seats_limit")
}

private object RevenuecatEvents : Table("revenuecat_events") {
    val id = long("id")
    val receivedAt = timestamp("received_at")
    val companyId = integer("company_id").nullable()
    val rcAppUserId = text("rc_app_user_id").nullable()
    val environment = text("environment").nullable()
    val productId = text("product_id").nullable()
    val eventType = text("event_type").nullable()
    val originalTransactionId = text("original_transaction_id").nullable()
    val expirationAt = timestamp("expiration_at").nullable()
    val willRenew = bool("will_renew").nullable()
    override val primaryKey = PrimaryKey(id)
}

// === DTOs ===
@kotlinx.serialization.Serializable
private data class EntitlementsDTO(
    val companyId: Int,
    val companyName: String,
    val paid: Boolean,
    val reason: String? = null,
    @Contextual val graceUntil: KInstant? = null,
    val priceId: String? = null,
    val seatsUsed: Int,
    val seatsLimit: Int
)

@kotlinx.serialization.Serializable
private data class RcEventDTO(
    val id: Long,
    @Contextual val receivedAt: KInstant,
    val companyId: Int? = null,
    val rcAppUserId: String? = null,
    val environment: String? = null,
    val productId: String? = null,
    val eventType: String? = null,
    val originalTransactionId: String? = null,
    @Contextual val expirationAt: KInstant? = null,
    val willRenew: Boolean? = null
)

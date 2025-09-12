package com.yourcompany.zeiterfassung.routes



import com.yourcompany.zeiterfassung.rc.RevenueCatClient
import com.yourcompany.zeiterfassung.rc.parseIsoToInstant
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

import rc.*
import io.ktor.client.HttpClient
import java.time.Instant

private fun jwtUserId(call: ApplicationCall): String? =
    call.principal<JWTPrincipal>()?.payload?.getClaim("id")?.asString()



fun Route.revenueCatRoutes(http: HttpClient) {

    val rc = RevenueCatClient(
        http = http,
        apiKey = System.getenv("RC_SECRET") ?: error("RC_SECRET missing")
    )

    authenticate("bearerAuth") {
        /**
         * Быстрый ответ клиенту: смотрим кэш, если старый/нет — подтягиваем из RC.
         */
        get("/api/me/entitlements") {
            val userId = jwtUserId(call) ?: return@get call.respond(HttpStatusCode.Unauthorized)

            // 1) пробуем кэш
            val cached = transaction {
                var row: Triple<Boolean, Instant?, String?>? = null
                TransactionManager.current().exec(
                    """
                    SELECT is_active, expires_at, product_id
                    FROM user_entitlements
                    WHERE user_id = '$userId' AND entitlement_key = 'pro'
                    LIMIT 1
                    """.trimIndent()
                ) { rs ->
                    if (rs.next()) {
                        row = Triple(
                            rs.getBoolean("is_active"),
                            rs.getTimestamp("expires_at")?.toInstant(),
                            rs.getString("product_id")
                        )
                    }
                }
                row
            }

            if (cached != null) {
                val (active, exp, pid) = cached
                return@get call.respond(
                    EntitlementsDto(
                        companyId = 0,
                        companyName = null,
                        paid = active,
                        reason = null,
                        graceUntil = null,
                        priceId = pid,
                        seatsUsed = 0,
                        seatsLimit = 0,
                        expiresAt = exp?.toString(),
                        proActive = active,
                        productId = pid,
                        source = "cache"
                    )
                )
            }

            // 2) нет кэша — тянем из RC и сохраняем
            val rcResp = rc.fetchSubscriber(userId)
            val pro = rcResp.subscriber.entitlements["pro"]
            val exp = parseIsoToInstant(pro?.expiresDate)

            val active = exp?.isAfter(Instant.now()) == true

            transaction {
                TransactionManager.current().exec(
                    """
                    INSERT INTO user_entitlements(user_id, entitlement_key, is_active, expires_at, product_id, updated_at)
                    VALUES ('$userId', 'pro', ${if (active) "TRUE" else "FALSE"}, ${exp?.let { "'$it'"} ?: "NULL"}, ${pro?.productId?.let { "'$it'"} ?: "NULL"}, now())
                    ON CONFLICT (user_id, entitlement_key) DO UPDATE SET
                      is_active = EXCLUDED.is_active,
                      expires_at = EXCLUDED.expires_at,
                      product_id = EXCLUDED.product_id,
                      updated_at = now()
                    """.trimIndent()
                )
            }

            call.respond(
                EntitlementsDto(
                    companyId = 0,
                    companyName = null,
                    paid = active,
                    reason = null,
                    graceUntil = null,
                    priceId = pro?.productId,
                    seatsUsed = 0,
                    seatsLimit = 0,
                    expiresAt = exp?.toString(),
                    proActive = active,
                    productId = pro?.productId,
                    source = "rc"
                )
            )
        }
    }

    /**
     * Вебхук от RevenueCat: ставим/снимаем активность, обновляем expiry.
     * Рекоммендация: проверь заголовок Authorization: Bearer <RC_WEBHOOK_SECRET>
     */
    post("/api/revenuecat/webhook") {
        val expected = System.getenv("RC_WEBHOOK_SECRET") ?: ""
        val got = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        if (expected.isNotBlank() && got != expected) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }

        // Мы читаем «универсально»: возьмём app_user_id, entitlement_id (или список), expiration_at_ms, product_id
        val payload = call.receive<Map<String, Any?>>()

        val appUserId = (payload["app_user_id"] ?: payload["app_user_id_rc"])?.toString()
        if (appUserId.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest)

        // RevenueCat события разные (INITIAL_PURCHASE, RENEWAL, CANCELLATION, EXPIRATION, BILLING_ISSUE и т.д.)
        val entitlement = (payload["entitlement_id"] ?: payload["entitlement_identifier"])?.toString() ?: "pro"
        val productId = payload["product_id"]?.toString()

        // expiry может приходить как expiration_at_ms или expires_date
        val expMs = (payload["expiration_at_ms"] as? Number)?.toLong()
        val expIso = payload["expires_date"]?.toString()
        val expiry = when {
            expMs != null -> Instant.ofEpochMilli(expMs)
            expIso != null -> parseIsoToInstant(expIso)
            else -> null
        }

        // активна, если expiry в будущем и нет явной отмены
        val eventType = payload["type"]?.toString()?.uppercase()
        val active = when (eventType) {
            "INITIAL_PURCHASE", "RENEWAL", "PRODUCT_CHANGE" -> expiry?.isAfter(Instant.now()) == true
            "CANCELLATION", "EXPIRATION", "UNCANCELLATION", "BILLING_ISSUE" -> expiry?.isAfter(Instant.now()) == true
            else -> expiry?.isAfter(Instant.now()) == true
        }

        transaction {
            TransactionManager.current().exec(
                """
                INSERT INTO user_entitlements(user_id, entitlement_key, is_active, expires_at, product_id, updated_at)
                VALUES ('$appUserId', '$entitlement', ${if (active) "TRUE" else "FALSE"}, ${expiry?.let { "'$it'"} ?: "NULL"}, ${productId?.let { "'$it'"} ?: "NULL"}, now())
                ON CONFLICT (user_id, entitlement_key) DO UPDATE SET
                  is_active = EXCLUDED.is_active,
                  expires_at = EXCLUDED.expires_at,
                  product_id = EXCLUDED.product_id,
                  updated_at = now()
                """.trimIndent()
            )
        }

        call.respond(HttpStatusCode.OK)
    }
}
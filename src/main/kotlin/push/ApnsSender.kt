/*


package com.yourcompany.zeiterfassung.push

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.DeliveryPriority
import com.eatthepath.pushy.apns.PushNotificationResponse
import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import com.eatthepath.pushy.apns.util.ApnsPayloadBuilder
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Unified APNs Sender with an internal client factory (sandbox/prod) and error mapping.
 * Depends on Pushy:
 * implementation("com.eatthepath:pushy:0.15.3") // or newer
 *
 * Required env variables:
 * APNS_TEAM_ID        - Apple Team ID (Обязательно, не хватает в вашем .env!)
 * APNS_KEY_ID         - Key ID for your .p8 key
 * APNS_P8_KEY         - Base64-encoded contents of the .p8 key (Используется вместо APNS_P8_BASE64)
 * Optional:
 * APNS_DEFAULT_TTL    - default TTL in seconds (default 600)
 */
object ApnsSender {

    private val logger = LoggerFactory.getLogger(ApnsSender::class.java)

    enum class Env { SANDBOX, PROD }
    enum class PushType { ALERT, BACKGROUND }

    enum class Status {
        SENT,
        RETRYABLE,
        UNREGISTERED,
        INVALID_TOKEN,
        INVALID_TOPIC,
        FAILED
    }

    data class Request(
        val deviceToken: String,
        val bundleId: String,
        val env: Env,
        val data: Map<String, Any?> = emptyMap(),
        val title: String? = null,
        val body: String? = null,
        val pushType: PushType = PushType.ALERT,
        val ttlSeconds: Long? = null,
        val collapseId: String? = null,
        val sound: String? = "default",
        val badge: Int? = null,
        val category: String? = null,
        /** Set to correlate with your push job id; propagates to apns-id header. */
        val apnsId: String? = null
    )

    data class Result(
        val status: Status,
        val apnsId: String? = null,
        val httpStatusCode: Int? = null,
        val reason: String? = null
    )

    /**
     * Sends the push notification asynchronously.
     */
    suspend fun send(req: Request): Result = withContext(Dispatchers.IO) {
        val ttl = (req.ttlSeconds ?: (System.getenv("APNS_DEFAULT_TTL")?.toLongOrNull() ?: 600L)).coerceAtLeast(0)
        // Retrieve the client based only on the environment (Env)
        val client = ClientFactory.clientFor(req.env)

        val payloadBuilder = com.eatthepath.pushy.apns.util.ApnsPayloadBuilder()
        applyPushTypeToPayload(req, payloadBuilder)

        // Custom payload data (userInfo)
        req.data.forEach { (k, v) ->
            if (v != null) {
                payloadBuilder.addCustomProperty(k, v)
            }
        }

        val payload = payloadBuilder.build()
        val expiration: Instant? = if (ttl == 0L) null else Instant.now().plusSeconds(ttl)
        val priority = if (req.pushType == PushType.ALERT) DeliveryPriority.IMMEDIATE else DeliveryPriority.CONSERVE_POWER
        val apnsUuid = req.apnsId?.let { kotlin.runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.randomUUID()

        val notification = SimpleApnsPushNotification(
            req.deviceToken,
            req.bundleId,
            payload,
            expiration,
            priority,
            req.collapseId,
            apnsUuid
        )

        return@withContext try {
            val response: PushNotificationResponse<SimpleApnsPushNotification> =
                client.sendNotification(notification).get(20, TimeUnit.SECONDS)

            if (response.isAccepted) {
                val id = response.apnsId.toString()
                logger.info("✅ APNs sent [env={}, topic={}, type={}, ttl={}, collapseId={}, apnsId={} tokenSuffix={}]",
                    req.env, req.bundleId, req.pushType, ttl, req.collapseId, id, tokenSuffix(req.deviceToken)
                )
                Result(Status.SENT, id, 200, null)
            } else {
                val reason = response.rejectionReason.orElse("unknown")
                val mapped = mapApnsRejection(reason)
                logger.warn("⚠️ APNs rejected [env={}, topic={}, type={}, reason={}, tokenSuffix={}]",
                    req.env, req.bundleId, req.pushType, reason, tokenSuffix(req.deviceToken)
                )
                Result(mapped, null, null, reason)
            }
        } catch (t: Throwable) {
            val (status, httpCode) = mapThrowable(t)
            logger.error("❌ APNs send failed [env={}, topic={}, type={}, tokenSuffix={}]: {}",
                req.env, req.bundleId, req.pushType, tokenSuffix(req.deviceToken), t.toString()
            )
            Result(status, null, httpCode, t.message)
        }
    }

    /**
     * Gracefully shuts down all active APNs clients. This should be called when your
     * application is shutting down to free up network resources.
     */
    fun shutdown() {
        ClientFactory.shutdown()
    }

    private fun tokenSuffix(token: String): String =
        if (token.length <= 8) token else token.takeLast(8)

    private fun mapApnsRejection(reason: String): Status = when (reason) {
        "BadDeviceToken", "Unregistered" -> Status.UNREGISTERED
        "DeviceTokenNotForTopic", "TopicDisallowed" -> Status.INVALID_TOPIC
        "TooManyRequests", "ServiceUnavailable", "Shutdown" -> Status.RETRYABLE
        "InternalServerError" -> Status.RETRYABLE
        "PayloadTooLarge", "BadMessageId", "BadCollapseId", "BadTopic", "MissingTopic" -> Status.FAILED
        "BadExpirationDate", "BadPriority", "BadPath", "BadCertificate", "BadCertificateEnvironment" -> Status.FAILED
        else -> Status.FAILED
    }

    private fun mapThrowable(t: Throwable): Pair<Status, Int?> {
        // Check for TimeoutException specifically
        if (t is TimeoutException) return Status.RETRYABLE to null

        val msg = t.message ?: ""
        return when {
            msg.contains("Unrecognized APNs authentication token", ignoreCase = true) -> Status.RETRYABLE to 401
            msg.contains("InvalidProviderToken", ignoreCase = true) -> Status.RETRYABLE to 403
            msg.contains("TooManyRequests", ignoreCase = true) -> Status.RETRYABLE to 429
            msg.contains("connect", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) -> Status.RETRYABLE to null
            else -> Status.FAILED to null
        }
    }

    /**
     * Applies APNs push-type semantics to the payload builder.
     * - ALERT: sets alert title/body; only then applies sound/badge/category.
     * If both title and body are empty, falls back to background (content-available).
     * - BACKGROUND: sets content-available and avoids any alert fields.
     */
    private fun applyPushTypeToPayload(
        req: Request,
        payloadBuilder: com.eatthepath.pushy.apns.util.ApnsPayloadBuilder
    ) {
        when (req.pushType) {
            PushType.ALERT -> {
                val hasTitle = !req.title.isNullOrBlank()
                val hasBody = !req.body.isNullOrBlank()
                if (hasTitle) payloadBuilder.setAlertTitle(req.title)
                if (hasBody) payloadBuilder.setAlertBody(req.body)

                if (hasTitle || hasBody) {
                    req.sound?.let { payloadBuilder.setSound(it) }
                    req.badge?.let { payloadBuilder.setBadgeNumber(it) }
                    req.category?.let { payloadBuilder.setCategoryName(it) }
                } else {
                    // No visible text – treat as background to avoid an empty alert.
                    payloadBuilder.setContentAvailable(true)
                }
            }
            PushType.BACKGROUND -> {
                payloadBuilder.setContentAvailable(true)
                // Ensure no alert fields are set for background pushes.
            }
        }
    }

    /**
     * Internal APNs client factory with caching by environment (Env).
     */
    private object ClientFactory {
        // Key is now just the environment, as the client configuration depends only on env/keys.
        private val clients = ConcurrentHashMap<Env, ApnsClient>()

        fun clientFor(env: Env): ApnsClient =
            clients.computeIfAbsent(env) {
                buildClient(env)
            }

        private fun buildClient(env: Env): ApnsClient {
            // APNS_TEAM_ID (Обязателен)
            val teamId = envOrThrow("APNS_TEAM_ID")
            val keyId = envOrThrow("APNS_KEY_ID")

            // Используем APNS_P8_KEY из .env и очищаем его от заголовков и символов новой строки
            val p8KeyContentWithHeaders = envOrThrow("APNS_P8_KEY")

            val p8KeyContent = p8KeyContentWithHeaders
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                // Удаляем все варианты новых строк, включая экранированные (\n) и фактические
                .replace("\\n", "")
                .replace("\n", "")
                .trim()

            val p8bytes = try {
                Base64.getDecoder().decode(p8KeyContent)
            } catch (e: IllegalArgumentException) {
                // Измененное сообщение об ошибке для более точного указания на проблему с форматом
                throw IllegalStateException("APNS_P8_KEY content is not valid Base64 after cleaning (check key format in .env)", e)
            }

            val signingKey = ApnsSigningKey.loadFromInputStream(ByteArrayInputStream(p8bytes), teamId, keyId)
            val host = when (env) {
                Env.SANDBOX -> ApnsClientBuilder.DEVELOPMENT_APNS_HOST
                Env.PROD -> ApnsClientBuilder.PRODUCTION_APNS_HOST
            }

            logger.info("🔌 Creating APNs client [env={}, host={}]", env, host)
            return ApnsClientBuilder()
                .setApnsServer(host)
                .setSigningKey(signingKey)
                .build()
        }

        fun shutdown() {
            clients.values.forEach { client ->
                try {
                    client.close().get(5, TimeUnit.SECONDS)
                    logger.info("🔌 APNs client closed successfully.")
                } catch (e: Exception) {
                    logger.error("Error closing APNs client: {}", e.message)
                }
            }
            clients.clear()
        }

        private fun envOrThrow(name: String): String =
            System.getenv(name) ?: error("Missing required env var: $name")
    }
}

*/
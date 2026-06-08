package com.yourcompany.zeiterfassung.push

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.DeliveryPriority
import com.eatthepath.pushy.apns.PushNotificationResponse
import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
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
 * APNS_P8_KEY         - .p8 private key content. Accepts full PEM, escaped \n PEM, or clean base64 body.
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
        val payload = buildPayload(req)
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

    private fun buildPayload(req: Request): String {
        val apsEntries = mutableListOf<String>()

        when (req.pushType) {
            PushType.ALERT -> {
                val hasTitle = !req.title.isNullOrBlank()
                val hasBody = !req.body.isNullOrBlank()

                if (hasTitle || hasBody) {
                    val alertEntries = mutableListOf<String>()
                    req.title?.takeIf { it.isNotBlank() }?.let {
                        alertEntries += "\"title\":${jsonValue(it)}"
                    }
                    req.body?.takeIf { it.isNotBlank() }?.let {
                        alertEntries += "\"body\":${jsonValue(it)}"
                    }
                    apsEntries += "\"alert\":{${alertEntries.joinToString(",")}}"
                    req.sound?.let { apsEntries += "\"sound\":${jsonValue(it)}" }
                    req.badge?.let { apsEntries += "\"badge\":$it" }
                    req.category?.let { apsEntries += "\"category\":${jsonValue(it)}" }
                } else {
                    apsEntries += "\"content-available\":1"
                }
            }

            PushType.BACKGROUND -> {
                apsEntries += "\"content-available\":1"
            }
        }

        val rootEntries = mutableListOf<String>()
        rootEntries += "\"aps\":{${apsEntries.joinToString(",")}}"

        req.data.forEach { (key, value) ->
            if (value != null && key != "aps") {
                rootEntries += "${jsonValue(key)}:${jsonValue(value)}"
            }
        }

        return "{${rootEntries.joinToString(",")}}"
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> "\"${value.toString().jsonEscaped()}\""
    }

    private fun String.jsonEscaped(): String = buildString {
        this@jsonEscaped.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
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

            val signingKey = ApnsSigningKey.loadFromInputStream(
                normalizedP8KeyPem().byteInputStream(),
                teamId,
                keyId
            )
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

        private fun normalizedP8KeyPem(): String {
            val raw = envOrThrow("APNS_P8_KEY")
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .replace("\\n", "\n")

            val body = raw
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")

            require(body.isNotBlank()) {
                "APNS_P8_KEY is empty after cleaning"
            }

            return buildString {
                appendLine("-----BEGIN PRIVATE KEY-----")
                body.chunked(64).forEach { appendLine(it) }
                appendLine("-----END PRIVATE KEY-----")
            }
        }
    }
}

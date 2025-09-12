package com.yourcompany.zeiterfassung.rc



import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException

@Serializable
data class RcEntitlement(
    @SerialName("expires_date") val expiresDate: String? = null,
    @SerialName("product_identifier") val productId: String? = null,
    @SerialName("grace_period_expires_date") val graceExpires: String? = null,
    @SerialName("unsubscribe_detected_at") val unsubAt: String? = null,
    @SerialName("billing_issues_detected_at") val billingIssueAt: String? = null
)

@Serializable
data class RcSubscriber(
    val entitlements: Map<String, RcEntitlement> = emptyMap()
)

@Serializable
data class RcSubscriberResp(
    val subscriber: RcSubscriber
)

class RevenueCatClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val base: String = System.getenv("RC_BASE") ?: "https://api.revenuecat.com/v1"
) {
    suspend fun fetchSubscriber(appUserId: String): RcSubscriberResp {
        return http.get("$base/subscribers/$appUserId") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            accept(ContentType.Application.Json)
        }.body()
    }
}

fun parseIsoToInstant(s: String?): Instant? = try {
    s?.let { Instant.parse(it) }
} catch (_: DateTimeParseException) { null }
package com.yourcompany.zeiterfassung.service.events

import kotlinx.serialization.Serializable

interface AppEventPublisher {
    suspend fun publish(event: ProcessedAppEvent)
}

@Serializable
data class ProcessedAppEvent(
    val id: Long,
    val eventId: String?,
    val eventType: String,
    val userId: Int?,
    val companyId: Int?,
    val projectId: Int?,
    val sessionId: String?,
    val payload: String?,
    val occurredAt: String?,
    val processedAt: String?
)
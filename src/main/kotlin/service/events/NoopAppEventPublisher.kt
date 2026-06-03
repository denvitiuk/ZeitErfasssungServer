package com.yourcompany.zeiterfassung.service.events

class NoopAppEventPublisher : AppEventPublisher {
    override suspend fun publish(event: ProcessedAppEvent) {
        println(
            "📭 [NoopAppEventPublisher] Kafka disabled, event ready " +
                    "id=${event.id} type=${event.eventType}"
        )
    }
}
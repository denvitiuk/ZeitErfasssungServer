package com.yourcompany.zeiterfassung.service.events

object AppEventPublisherFactory {

    fun create(): AppEventPublisher {
        val enabled = System.getenv("KAFKA_ENABLED") == "true"

        if (!enabled) {
            return NoopAppEventPublisher()
        }

        val bootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS")
            ?: error("KAFKA_BOOTSTRAP_SERVERS is required when KAFKA_ENABLED=true")

        val topic = System.getenv("KAFKA_TOPIC_APP_EVENTS")
            ?: "app.events.processed"

        return KafkaAppEventPublisher(
            bootstrapServers = bootstrapServers,
            topic = topic,
            securityProtocol = System.getenv("KAFKA_SECURITY_PROTOCOL"),
            saslMechanism = System.getenv("KAFKA_SASL_MECHANISM"),
            username = System.getenv("KAFKA_USERNAME"),
            password = System.getenv("KAFKA_PASSWORD"),
            caCertBase64 = System.getenv("KAFKA_CA_CERT")
        )
    }
}
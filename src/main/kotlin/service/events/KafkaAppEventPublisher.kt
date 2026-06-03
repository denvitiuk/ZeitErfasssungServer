package com.yourcompany.zeiterfassung.service.events

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringSerializer
import java.nio.file.Files
import java.util.Base64
import java.util.Properties

class KafkaAppEventPublisher(
    bootstrapServers: String,
    private val topic: String,
    securityProtocol: String? = null,
    saslMechanism: String? = null,
    username: String? = null,
    password: String? = null,
    caCertBase64: String? = null
) : AppEventPublisher {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val properties: Properties = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)

        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
        put(ProducerConfig.RETRIES_CONFIG, Int.MAX_VALUE.toString())
        put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5")
        put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "120000")
        put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000")
        put(ProducerConfig.LINGER_MS_CONFIG, "10")

        if (!securityProtocol.isNullOrBlank()) {
            put("security.protocol", securityProtocol)
        }

        val caCertPath = createTempCaCertFile(caCertBase64)
        if (!caCertPath.isNullOrBlank()) {
            put("ssl.truststore.type", "PEM")
            put("ssl.truststore.location", caCertPath)
        }

        if (!saslMechanism.isNullOrBlank()) {
            put(SaslConfigs.SASL_MECHANISM, saslMechanism)
        }

        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            put(
                SaslConfigs.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                        "username=\"$username\" password=\"$password\";"
            )
        }
    }

    private val producer: KafkaProducer<String, String> by lazy {
        println("📡 [KafkaAppEventPublisher] initializing Kafka producer topic=$topic")
        KafkaProducer<String, String>(properties)
    }

    override suspend fun publish(event: ProcessedAppEvent) {
        withContext(Dispatchers.IO) {
            val key = event.eventId ?: event.id.toString()
            val value = json.encodeToString(event)

            producer.send(
                ProducerRecord(topic, key, value)
            ).get()

            println(
                "📤 [KafkaAppEventPublisher] published " +
                        "id=${event.id} type=${event.eventType} topic=$topic"
            )
        }
    }

    private fun createTempCaCertFile(caCertBase64: String?): String? {
        if (caCertBase64.isNullOrBlank()) return null

        return runCatching {
            val decoded = Base64.getDecoder().decode(caCertBase64.trim())
            val tempFile = Files.createTempFile("kafka-aiven-ca-", ".pem")
            Files.write(tempFile, decoded)
            tempFile.toFile().deleteOnExit()
            tempFile.toAbsolutePath().toString()
        }.getOrElse { error ->
            throw IllegalStateException(
                "Failed to decode KAFKA_CA_CERT. Expected base64-encoded PEM certificate.",
                error
            )
        }
    }
}
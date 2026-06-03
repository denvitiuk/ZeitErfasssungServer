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
import java.util.Base64
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore
import java.io.ByteArrayInputStream

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

    private val producer = KafkaProducer<String, String>(
        Properties().apply {
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

            if (!saslMechanism.isNullOrBlank()) {
                put(SaslConfigs.SASL_MECHANISM, saslMechanism)
            }

            if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                put(
                    SaslConfigs.SASL_JAAS_CONFIG,
                    "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                            "username=\"$username\" password=\"$password\";"
                )
            }

            if (!caCertBase64.isNullOrBlank()) {
                val caCertBytes = Base64.getDecoder().decode(caCertBase64)
                val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
                    val cert = certFactory.generateCertificate(ByteArrayInputStream(caCertBytes))
                    setCertificateEntry("caCert", cert)
                }
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(trustStore)
                }
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, tmf.trustManagers, null)
                }
                put("ssl.truststore.type", KeyStore.getDefaultType())
                put("ssl.truststore.location", "")
                put("ssl.endpoint.identification.algorithm", "")
                put("ssl.trustmanager.algorithm", TrustManagerFactory.getDefaultAlgorithm())
                // Note: Kafka client does not support setting SSLContext directly via properties.
                // This is a placeholder in case of custom SSL context usage.
            }
        }
    )

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
}
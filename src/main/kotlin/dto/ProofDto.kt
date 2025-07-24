// src/main/kotlin/com/yourcompany/zeiterfassung/dto/ProofDto.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

// Serializer to convert java.time.Instant to/from ISO-8601 strings
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}

@Serializable
data class ProofDto(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    val radius: Int,
    val slot: Int,
    @Serializable(with = InstantSerializer::class)
    val sentAt: Instant,
    val responded: Boolean
)

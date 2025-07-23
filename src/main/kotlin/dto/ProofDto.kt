// src/main/kotlin/com/yourcompany/zeiterfassung/dto/ProofDto.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ProofDto(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    val radius: Int,
    val slot: Int,
    @Contextual val sentAt: Instant,
    val responded: Boolean
)

// src/main/kotlin/com/yourcompany/zeiterfassung/dto/RespondProofRequest.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class RespondProofRequest(
    val latitude: Double,
    val longitude: Double
)

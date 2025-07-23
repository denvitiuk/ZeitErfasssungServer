// src/main/kotlin/com/yourcompany/zeiterfassung/dto/ProofResponse.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class ProofResponse(
    val status: String,
    val timestamp: String
)

// src/main/kotlin/com/yourcompany/zeiterfassung/dto/PauseResponse.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class PauseResponse(
    val status: String,
    val sessionId: Int? = null,
    val timestamp: String
)

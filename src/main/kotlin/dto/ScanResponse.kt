// src/main/kotlin/com/yourcompany/zeiterfassung/dto/ScanResponse.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class ScanResponse(
    val status: String,
    val timestamp: String
)

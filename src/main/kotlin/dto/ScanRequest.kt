// src/main/kotlin/com/yourcompany/zeiterfassung/dto/ScanRequest.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class ScanRequest(
    val nonce: String
)

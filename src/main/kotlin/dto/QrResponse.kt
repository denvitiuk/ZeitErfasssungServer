// src/main/kotlin/com/yourcompany/zeiterfassung/dto/QrResponse.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class QrResponse(
    val nonce: String,
    val qrCode: String
)



// src/main/kotlin/com/yourcompany/zeiterfassung/dto/VerifyRequest.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class VerifyRequest(
    val to: String,
    val code: String
)
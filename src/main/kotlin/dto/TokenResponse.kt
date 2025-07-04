// src/main/kotlin/com/yourcompany/zeiterfassung/dto/TokenResponse.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    val token: String
)
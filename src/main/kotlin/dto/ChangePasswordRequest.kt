package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

// src/main/kotlin/com/yourcompany/zeiterfassung/dto/ChangePasswordRequest.kt
@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

// можно вернуть просто статус
@Serializable
data class ChangePasswordResponse(
    val status: String
)
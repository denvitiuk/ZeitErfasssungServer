// src/main/kotlin/com/yourcompany/zeiterfassung/dto/LoginDTO.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginDTO(
    val email: String,
    val password: String
)
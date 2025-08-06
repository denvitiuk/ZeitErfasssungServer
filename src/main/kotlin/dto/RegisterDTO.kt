// src/main/kotlin/com/yourcompany/zeiterfassung/dto/RegisterDTO.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterDTO(

    val firstName: String,
    val lastName: String,
    val birthDate: String,
    val email: String,
    val password: String,
    val phone: String? = null,
    /** Код приглашения компании */
    val inviteCode: String
)

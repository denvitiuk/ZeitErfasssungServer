package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterResponse(
    val id: Int,
    val email: String,
    val firstName: String,
    val lastName: String,
    val phone: String,
    val birthDate: String,
    val password: String? = null
)

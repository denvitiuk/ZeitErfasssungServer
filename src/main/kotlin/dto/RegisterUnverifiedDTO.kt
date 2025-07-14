package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterUnverifiedDTO(val phone: String)

@Serializable
data class RegisterUnverifiedResponse(val phone: String)

@Serializable
data class CompleteRegistrationDTO(
    val phone: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val password: String,
    val birthDate: String
)
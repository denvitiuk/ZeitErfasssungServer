package com.yourcompany.zeiterfassung.dto


import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

// где-то в пакете dto или сразу в AuthRoutes.kt
@Serializable
data class ProfileResponse(
    val first_name: String,
    val last_name: String,
    val email: String,
    val phone: String,
    val employee_number: String,
    val avatar_url: String? = null,
    val created_at: String
)
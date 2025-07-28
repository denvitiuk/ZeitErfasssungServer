// 1) DTOs for Ktor serialization
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class ForgotRequest(
    val to: String       // can be email or phone number
)

@Serializable
data class ResetRequest(
    val to: String,      // email or phone number
    val code: String,    // 6-digit code
    val newPassword: String
)

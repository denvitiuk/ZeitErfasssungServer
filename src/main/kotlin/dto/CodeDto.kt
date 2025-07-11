

package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

/**
 * DTO for initiating a phone verification SMS.
 */
@Serializable
data class SendCodeDTO(
    val phone: String
)

/**
 * DTO for verifying the received phone code.
 */
@Serializable
data class VerifyCodeDTO(
    val phone: String,
    val code: String
)
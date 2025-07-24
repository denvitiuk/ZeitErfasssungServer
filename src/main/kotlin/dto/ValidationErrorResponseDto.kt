package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class ValidationErrorResponseDto(
    val error: String,
    val details: Map<String, List<String>>
)
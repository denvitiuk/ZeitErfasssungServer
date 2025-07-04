// src/main/kotlin/com/yourcompany/zeiterfassung/dto/LogEntryDTO.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class LogEntryDTO(
    val employeeId: String,
    val action: String,
    val timestamp: String
)

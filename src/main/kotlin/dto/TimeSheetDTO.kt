// src/main/kotlin/com/yourcompany/zeiterfassung/dto/TimesheetDTOs.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class TimesheetDayDTO(
    val day: Int,
    val firstStart: String? = null, // "HH:mm"
    val lastEnd: String? = null,    // "HH:mm"
    val minutes: Int = 0            // суммарно за день
)

@Serializable
data class TimesheetMonthDTO(
    val userId: Int,
    val userName: String? = null,
    val month: String,              // "YYYY-MM"
    val tz: String,                 // "Europe/Berlin"
    val days: List<TimesheetDayDTO>,// всегда длиной 31 (лишние дни нулевые)
    val totalMinutes: Int
)
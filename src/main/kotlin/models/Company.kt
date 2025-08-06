package com.yourcompany.zeiterfassung.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Company(
    val id: Int,
    val name: String,
    val inviteCode: String,
    @Contextual val createdAt: Instant
)

@Serializable
data class CompanyRequest(
    val name: String
)


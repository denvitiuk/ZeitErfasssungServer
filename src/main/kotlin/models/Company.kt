package com.yourcompany.zeiterfassung.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID

@Serializable
data class Company(
    val id: Int,
    val name: String,
    val inviteCode: String,
    val createdAt: String
)

@Serializable
data class CompanyRequest(
    val name: String
)


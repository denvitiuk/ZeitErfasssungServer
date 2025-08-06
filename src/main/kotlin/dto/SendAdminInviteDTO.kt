package com.yourcompany.zeiterfassung.dto

@kotlinx.serialization.Serializable
data class SendAdminInviteDTO(val to: String, val companyId: Int)


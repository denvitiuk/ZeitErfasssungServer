package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable


@Serializable
data class VerifyAdminInviteDTO(val companyId: Int, val code: String)



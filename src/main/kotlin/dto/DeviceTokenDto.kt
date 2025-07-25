package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import com.yourcompany.zeiterfassung.models.DeviceTokens
import org.jetbrains.exposed.sql.selectAll

@Serializable
data class DeviceTokenDto(
    val userId: Int,
    val platform: String,
    val token: String
)

suspend fun loadAllDeviceTokens(): List<DeviceTokenDto> =
    transaction {
        DeviceTokens.selectAll().map {
            DeviceTokenDto(
                userId = it[DeviceTokens.userId],
                platform = it[DeviceTokens.platform],
                token = it[DeviceTokens.token]
            )
        }
    }
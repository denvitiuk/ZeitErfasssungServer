package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class ProofCreateRequest(
    val latitude: Double,
    val longitude: Double,
    val radius: Int,
    val slot: Int
)
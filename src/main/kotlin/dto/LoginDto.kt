// src/main/kotlin/com/yourcompany/zeiterfassung/dto/LoginDTO.kt
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginDTO(
    val email: String,
    val password: String
)

// Ответ при ошибке: код ошибки и человекочитаемое сообщение
@Serializable
data class ErrorResponseDTO(
    val code: String,
    val message: String
)

// Пара токенов: access и refresh
@Serializable
data class TokenResponseDTO(
    val accessToken: String,
    val refreshToken: String
)

// Запрос на обновление токенов
@Serializable
data class RefreshTokenRequestDTO(
    val refreshToken: String
)

// Ответ при обновлении токенов: новый access и refresh
@Serializable
data class RefreshTokenResponseDTO(
    val accessToken: String,
    val refreshToken: String
)


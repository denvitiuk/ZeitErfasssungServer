package com.yourcompany.zeiterfassung.models

import com.yourcompany.zeiterfassung.db.Users
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp


/**
 * Модель таблицы device_tokens для хранения APNs/FCM токенов устройств
 */
object DeviceTokens : Table("device_tokens") {
    // Primary key
    val id = integer("id").autoIncrement()
    override val primaryKey = PrimaryKey(id, name = "pk_device_tokens_id")

    // Внешний ключ на таблицу users
    val userId = integer("user_id").references(Users.id)

    // Платформа: "ios" или "android"
    val platform = varchar("platform", length = 16)

    // Сам токен устройства
    val token = text("token")

    // Время создания записи
    val createdAt = timestamp("created_at")
}

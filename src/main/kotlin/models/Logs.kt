package com.yourcompany.zeiterfassung.models

import com.yourcompany.zeiterfassung.db.Users
import com.yourcompany.zeiterfassung.models.Nonces

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

object Logs : Table("logs") {
    val id            = integer("id").autoIncrement()

    // FK → users(id), на удаление пользователя удаляем его логи
    val userId        = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)

    // FK → nonces(nonce), запрещаем удаление использованного nonce
    val terminalNonce = reference("terminal_nonce", Nonces.nonce, onDelete = ReferenceOption.RESTRICT)

    val action        = varchar("action", 10)                 // "in" | "out"

    // Соответствует текущей зависимости Exposed: LocalDateTime
    val timestamp     = datetime("timestamp")

    val latitude      = double("latitude").nullable()
    val longitude     = double("longitude").nullable()
    val locationDesc  = text("location_description").nullable()

    override val primaryKey = PrimaryKey(id, name = "pk_logs")
}
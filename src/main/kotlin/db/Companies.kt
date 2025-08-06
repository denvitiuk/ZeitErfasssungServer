package com.yourcompany.zeiterfassung.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/**
 * Таблица компаний (арендаторов) для мультиарендности
 */
object Companies : IntIdTable("companies") {
    /** Уникальное название компании */
    val name = varchar("name", 255).uniqueIndex()

    /** Уникальный код приглашения для регистрации пользователей */
    val inviteCode = varchar("invite_code", 16).uniqueIndex()

    /** Время создания записи */
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
}

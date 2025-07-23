// src/main/kotlin/com/yourcompany/zeiterfassung/models/PauseSessions.kt
package com.yourcompany.zeiterfassung.models

import com.yourcompany.zeiterfassung.db.Users
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

// Таблица для хранения сессий паузы

object PauseSessions : IntIdTable("pause_sessions") {
    val userId    = integer("user_id").references(Users.id)
    val startedAt = datetime("started_at")
    val endedAt   = datetime("ended_at").nullable()
    val isActive  = bool("is_active")
}

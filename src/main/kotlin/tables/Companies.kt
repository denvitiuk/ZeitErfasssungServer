package com.yourcompany.zeiterfassung.tables


import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

import org.jetbrains.exposed.dao.id.IntIdTable

object Companies : IntIdTable("companies") {
    val name = text("name").uniqueIndex()
    val inviteCode = varchar("invite_code", 16).uniqueIndex()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
}
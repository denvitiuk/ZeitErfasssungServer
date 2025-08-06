package com.yourcompany.zeiterfassung.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object Companies : Table("companies") {
    val id = integer("id").autoIncrement()
    val name = text("name").uniqueIndex()
    val inviteCode = varchar("invite_code", 16).uniqueIndex()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)
}
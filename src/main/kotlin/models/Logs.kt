package com.yourcompany.zeiterfassung.models

import com.yourcompany.zeiterfassung.db.Users
import com.yourcompany.zeiterfassung.models.Nonces

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object Logs : Table("logs") {
    val id           = integer("id").autoIncrement()
    val userId       = integer("user_id").references(Users.id)
    val terminalNonce  = varchar("terminal_nonce", 64).references(Nonces.nonce)
    val action       = varchar("action", 10)               // "in" или "out"
    val timestamp    = datetime("timestamp")               // java.time.LocalDateTime
    val latitude     = double("latitude").nullable()      // nullable, если не передаём
    val longitude    = double("longitude").nullable()
    val locationDesc = text("location_description").nullable()

    override val primaryKey = PrimaryKey(id, name = "pk_logs")
}
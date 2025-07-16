package com.yourcompany.zeiterfassung.models

import com.yourcompany.zeiterfassung.db.Users

import org.jetbrains.exposed.sql.Table

import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

object Nonces : Table("nonces") {
    val nonce     = varchar("nonce", 64)
    val createdAt = datetime("created_at")
    val used      = bool("used").default(false)
    val userId    = integer("user_id").references(Users.id)
    val workDate  = date("work_date")
    val action    = varchar("action", 64)

    override val primaryKey = PrimaryKey(nonce, name = "pk_nonces")
}
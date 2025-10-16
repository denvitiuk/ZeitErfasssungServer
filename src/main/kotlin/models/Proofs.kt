// src/main/kotlin/com/yourcompany/zeiterfassung/models/Proofs.kt
package com.yourcompany.zeiterfassung.models

import com.yourcompany.zeiterfassung.db.Users
import com.yourcompany.zeiterfassung.db.Projects
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.date

// Таблица для хранения proof-проверок
object Proofs : Table("proofs") {
    val id          = integer("id").autoIncrement()
    val userId      = integer("user_id").references(Users.id)
    val projectId   = integer("project_id").references(Projects.id)
    val latitude    = double("latitude")
    val longitude   = double("longitude")
    val radius      = integer("radius")
    val date        = date("date")
    val slot        = short("slot")
    val sentAt      = datetime("sent_at").nullable()
    val responded   = bool("responded")
    val respondedAt = datetime("responded_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("ux_proofs_user_project_date_slot", userId, projectId, date, slot)
    }
}

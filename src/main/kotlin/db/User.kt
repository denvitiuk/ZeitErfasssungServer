package com.yourcompany.zeiterfassung.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime
import com.yourcompany.zeiterfassung.db.Companies
import java.util.UUID
import org.jetbrains.exposed.sql.UUIDColumnType

object Users : IntIdTable("users") {
    // Employee number (UUID), unique
    val employeeNumber = registerColumn<UUID>("employee_number", UUIDColumnType()).uniqueIndex()

    // PII — now nullable to allow proper de-identification on account deletion
    val firstName      = varchar("first_name", 100).nullable()
    val lastName       = varchar("last_name", 100).nullable()
    val birthDate      = date("birth_date").nullable()
    val email          = varchar("email", 255).nullable()  // DB also has functional unique index on lower(email)
    val password       = varchar("password", 60).nullable()
    val phone          = varchar("phone", 20).nullable()
    val phoneVerified  = bool("phone_verified").default(false)
    val avatarUrl      = varchar("avatar_url", 255).nullable()

    val createdAt      = datetime("created_at").clientDefault { LocalDateTime.now() }

    // Tenant
    val companyId      = reference("company_id", Companies.id).nullable()

    // Roles
    val isCompanyAdmin = bool("is_company_admin").default(false)
    val isGlobalAdmin  = bool("is_global_admin").default(false)

    // Soft-delete / access control
    val status         = varchar("status", 16).default("active") // active | deleting | deleted
    val deletedAt      = datetime("deleted_at").nullable()
    val deletionMethod = varchar("deletion_method", 16).nullable()
    val isActive       = bool("is_active").default(true)
}

package com.yourcompany.zeiterfassung.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

import java.time.LocalDateTime
import com.yourcompany.zeiterfassung.db.Companies

object Users : IntIdTable("users") {
    val firstName     = varchar("first_name", 100)
    val lastName      = varchar("last_name", 100)
    val birthDate     = date("birth_date")
    val email         = varchar("email", 255).uniqueIndex()
    val password      = varchar("password", 60)
    val phone         = varchar("phone", 20).nullable()
    val phoneVerified = bool("phone_verified").default(false)
    val avatarUrl     = varchar("avatar_url", 255).nullable()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    /** ID компании (арендатор) */

    val companyId = reference("company_id", Companies.id).nullable()    /** Флаг админа компании */
    val isCompanyAdmin  = bool("is_company_admin").default(false)
    /** Флаг супер-админа */
    val isGlobalAdmin   = bool("is_global_admin").default(false)
}

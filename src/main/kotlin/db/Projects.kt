package com.yourcompany.zeiterfassung.db




import com.yourcompany.zeiterfassung.tables.Companies
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed tables for Projects & Project Members
 */
object Projects : IntIdTable(name = "projects") {
    val companyId   = reference("company_id", Companies)
    val title       = varchar("title", 120)
    val description = text("description").nullable()
    val location    = text("location").nullable()
    val lat         = double("lat").nullable()
    val lng         = double("lng").nullable()
    val createdAt   = timestamp("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt   = timestamp("updated_at").defaultExpression(CurrentTimestamp())

    init {
        // UNIQUE (company_id, title) создаётся миграцией; эти индексы помогут планировщику
        index(isUnique = true, companyId, title)
        index(isUnique = false, companyId)
    }
}

object ProjectMembers : Table(name = "project_members") {
    val projectId = reference("project_id", Projects)
    val userId    = reference("user_id", Users)
    val role      = short("role").default(0) // 0=member, 1=manager (на будущее)
    val joinedAt  = timestamp("joined_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(projectId, userId)

    init {
        index(isUnique = false, userId)
    }
}
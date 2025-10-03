package com.yourcompany.zeiterfassung.service

import com.yourcompany.zeiterfassung.db.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

@Serializable
data class DeletionLinks(val transfer: String? = null, val workspaceDelete: String? = null)

/**
 * Simplified deletion service:
 * - No writes to logs or nonces
 * - Deletes device tokens
 * - Soft-deletes and de-identifies the user
 */
class AccountDeletionService(
    private val appBaseUrl: String // kept for future use
) {
    // Lightweight table for device tokens (for deleteWhere)
    private object DeviceTokens : Table("device_tokens") {
        val id = integer("id").autoIncrement()
        val userId = integer("user_id")
        val platform = varchar("platform", 16)
        val token = text("token")
        val createdAt = datetime("created_at")
        override val primaryKey = PrimaryKey(id)
    }

    data class CheckResult(val canDelete: Boolean, val links: DeletionLinks? = null)

    /**
     * By product decision any user may delete their account at any time.
     * Always returns true.
     */
    fun canDelete(@Suppress("UNUSED_PARAMETER") userId: Int): CheckResult =
        CheckResult(true, null)

    /**
     * Hard-simplified deletion:
     * - remove device tokens
     * - de-identify user & mark as deleted
     */
    fun deleteAccount(userId: Int, idempotencyKey: String? = null): Boolean = transaction {
        // 1) device tokens
        DeviceTokens.deleteWhere { DeviceTokens.userId eq userId }

        // 2) user soft-delete & de-identification
        Users.update({ Users.id eq userId }) {
            it[Users.firstName] = null
            it[Users.lastName] = null
            it[Users.email] = null
            it[Users.password] = null
            it[Users.phone] = null
            it[Users.avatarUrl] = null

            it[Users.isActive] = false
            it[Users.isCompanyAdmin] = false
            it[Users.isGlobalAdmin] = false

            it[Users.status] = "DELETED"
            it[Users.deletedAt] = LocalDateTime.now()
            it[Users.deletionMethod] = "self"
        }

        true
    }
}
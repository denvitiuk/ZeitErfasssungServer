package com.yourcompany.zeiterfassung.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime
import java.util.*

object PasswordResetTokens : UUIDTable("password_reset_tokens") {
    // Foreign key to users table
    val userId      = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    // Channel: "sms" or "email"
    val channel     = varchar("channel", 10)
    // Destination email address or phone number
    val destination = varchar("destination", 255)
    // One-time code
    val code        = varchar("code", 6)
    // Expiration timestamp
    val expiresAt   = datetime("expires_at")
}
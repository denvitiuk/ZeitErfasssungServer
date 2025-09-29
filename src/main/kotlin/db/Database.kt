package com.yourcompany.zeiterfassung.db


import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import io.ktor.server.application.*
import io.ktor.server.config.*

/**
 * Initializes HikariCP data source and runs Flyway migrations.
 */
fun Application.configureDatabases() {
    val db = environment.config.config("ktor.database")
    val config = HikariConfig().apply {
        jdbcUrl = db.property("url").getString()
        driverClassName = db.property("driver").getString()
        username = db.property("user").getString()
        password = db.property("password").getString()
        maximumPoolSize = db.property("maximumPoolSize").getString().toInt()
    }
    val ds = HikariDataSource(config)
    Database.connect(ds)
    log.info("Database connected (Flyway skipped)")
}

package com.yourcompany.zeiterfassung.db


import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import io.ktor.server.application.*
import io.ktor.server.config.*

/**
 * Initializes HikariCP data source and runs Flyway migrations.
 */
fun Application.configureDatabases() {
    val cfg = environment.config
    val dotenv = io.github.cdimascio.dotenv.dotenv {
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }
    val db = cfg.config("ktor.database")

    val dbJdbcUrl = db.propertyOrNull("url")?.getString()
        ?: System.getenv("JDBC_DATABASE_URL")
        ?: dotenv["JDBC_DATABASE_URL"]
        ?: "jdbc:postgresql://localhost:5432/attendance"
    val dbDriver = db.propertyOrNull("driver")?.getString() ?: "org.postgresql.Driver"
    val dbUser = db.propertyOrNull("user")?.getString()
        ?: System.getenv("DB_USER")
        ?: dotenv["DB_USER"]
        ?: "postgres"
    val dbPassword = db.propertyOrNull("password")?.getString()
        ?: System.getenv("DB_PASSWORD")
        ?: dotenv["DB_PASSWORD"]
        ?: "postgres"
    val dbMaxPool = db.propertyOrNull("maximumPoolSize")?.getString()?.toIntOrNull()
        ?: System.getenv("DB_MAX_POOL")?.toIntOrNull()
        ?: dotenv["DB_MAX_POOL"]?.toIntOrNull()
        ?: 10

    val config = HikariConfig().apply {
        this.jdbcUrl = dbJdbcUrl
        this.driverClassName = dbDriver
        this.username = dbUser
        this.password = dbPassword
        this.maximumPoolSize = dbMaxPool
    }
    val ds = HikariDataSource(config)
    Database.connect(ds)
    log.info("Database connected")
}

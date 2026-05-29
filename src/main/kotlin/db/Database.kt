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

    val dbJdbcUrl = System.getenv("JDBC_DATABASE_URL_NEON")
        ?: dotenv["JDBC_DATABASE_URL_NEON"]
        ?: System.getenv("JDBC_DATABASE_URL")
        ?: dotenv["JDBC_DATABASE_URL"]
        ?: db.propertyOrNull("url")?.getString()
        ?: "jdbc:postgresql://localhost:5432/attendance"

    val dbDriver = db.propertyOrNull("driver")?.getString() ?: "org.postgresql.Driver"

    val dbUser = System.getenv("DB_USER_NEON")
        ?: dotenv["DB_USER_NEON"]
        ?: System.getenv("DB_USER")
        ?: dotenv["DB_USER"]
        ?: db.propertyOrNull("user")?.getString()
        ?: "postgres"

    val dbPassword = System.getenv("DB_PASSWORD_NEON")
        ?: dotenv["DB_PASSWORD_NEON"]
        ?: System.getenv("DB_PASSWORD")
        ?: dotenv["DB_PASSWORD"]
        ?: db.propertyOrNull("password")?.getString()
        ?: "postgres"

    val dbMaxPool = System.getenv("DB_MAX_POOL_NEON")?.toIntOrNull()
        ?: dotenv["DB_MAX_POOL_NEON"]?.toIntOrNull()
        ?: System.getenv("DB_MAX_POOL")?.toIntOrNull()
        ?: dotenv["DB_MAX_POOL"]?.toIntOrNull()
        ?: db.propertyOrNull("maximumPoolSize")?.getString()?.toIntOrNull()
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

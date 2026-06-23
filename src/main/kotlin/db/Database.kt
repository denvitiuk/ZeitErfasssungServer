package com.yourcompany.zeiterfassung.db


import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import io.ktor.server.application.*
import io.ktor.server.config.*

/**
 * Initializes HikariCP data source and runs Flyway migrations.
 */
fun Application.configureDatabases(isTestMode: Boolean = false) {
    val cfg = environment.config
    val dotenv = io.github.cdimascio.dotenv.dotenv {
        filename = if (isTestMode) ".env.test" else ".env"
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }

    if (isTestMode) {
        val testJdbcUrl = dotenv["JDBC_DATABASE_URL_TEST"]
            ?: error("JDBC_DATABASE_URL_TEST must be configured for test mode")
        val testUser = dotenv["DB_USER_TEST"]
            ?: error("DB_USER_TEST must be configured for test mode")
        val testPassword = dotenv["DB_PASSWORD_TEST"]
            ?: error("DB_PASSWORD_TEST must be configured for test mode")

        require(testJdbcUrl.startsWith("jdbc:postgresql://")) {
            "Test mode requires a PostgreSQL JDBC URL"
        }

        val testConfig = HikariConfig().apply {
            jdbcUrl = testJdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = testUser
            password = testPassword
            maximumPoolSize = (dotenv["DB_MAX_POOL_TEST"] ?: "3").toInt()
        }

        Database.connect(HikariDataSource(testConfig))
        log.info("Test database connected")
        return
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
        jdbcUrl = dbJdbcUrl
        driverClassName = dbDriver
        username = dbUser
        password = dbPassword
        maximumPoolSize = dbMaxPool
    }

    Database.connect(HikariDataSource(config))
    log.info("Database connected")
}

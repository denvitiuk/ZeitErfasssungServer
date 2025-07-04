package com.yourcompany.zeiterfassung

import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.cors.routing.CORS

import io.ktor.http.HttpHeaders
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.yourcompany.zeiterfassung.db.configureDatabases
import com.yourcompany.zeiterfassung.routes.authRoutes
import com.yourcompany.zeiterfassung.routes.qrRoutes
import com.yourcompany.zeiterfassung.routes.scanRoutes
import com.yourcompany.zeiterfassung.routes.logsRoutes

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    // 1. JSON serialization
    install(ContentNegotiation) {
        json()
    }

    // 2. CORS
    install(CORS) {
        anyHost()
        allowCredentials = true
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    // 3. JWT authentication
    install(Authentication) {
        jwt("bearerAuth") {
            realm = this@module.environment.config.property("ktor.jwt.realm").getString()
            // Read JWT settings from HOCON
            val jwtConfig = this@module.environment.config.config("ktor.jwt")
            val secret = jwtConfig.property("secret").getString()
            val issuer = jwtConfig.property("issuer").getString()
            val audience = jwtConfig.property("audience").getString()
            // Configure the JWT verifier
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withIssuer(issuer)
                    .withAudience(audience)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("id").asString() != null) JWTPrincipal(credential.payload) else null
            }
        }
    }

    // 4. Database initialization
    configureDatabases()

    // 5. Routing
    routing {
        // Public
        authRoutes()

        // Protected
        authenticate("bearerAuth") {
            qrRoutes()
            scanRoutes()
            logsRoutes()
        }
    }
}

// src/main/kotlin/com/yourcompany/zeiterfassung/routes/AuthRoutes.kt
package com.yourcompany.zeiterfassung.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.yourcompany.zeiterfassung.dto.*

/**
 * Handles user registration and login.
 */
fun Route.authRoutes() {
    route("/register") {
        post {
            val dto = call.receive<RegisterDTO>()
            // TODO: implement user creation
            call.respond(HttpStatusCode.Created)
        }
    }
    route("/login") {
        post {
            val dto = call.receive<LoginDTO>()
            // TODO: verify credentials & generate JWT
            call.respond(mapOf("token" to "stub-jwt-token"))
        }
    }
}

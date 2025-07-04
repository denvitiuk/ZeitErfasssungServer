// src/main/kotlin/com/yourcompany/zeiterfassung/routes/ScanRoutes.kt
package com.yourcompany.zeiterfassung.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.yourcompany.zeiterfassung.dto.*

/**
 * Handles scanning of QR codes (in/out actions).
 */
fun Route.scanRoutes() {
    post("/scan") {
        val req = call.receive<ScanRequest>()
        // TODO: validate nonce, record log
        call.respond(
            ScanResponse(
                status = "ok",
                timestamp = java.time.Instant.now().toString()
            )
        )
    }
}

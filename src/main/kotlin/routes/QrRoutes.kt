

// src/main/kotlin/com/yourcompany/zeiterfassung/routes/QrRoutes.kt
package com.yourcompany.zeiterfassung.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.yourcompany.zeiterfassung.dto.*

/**
 * Handles QR code generation.
 */
fun Route.qrRoutes() {
    get("/qr/generate") {
        // TODO: generate nonce, create QR image
        call.respond(
            QrResponse(
                nonce = "stub-nonce-123",
                qrCode = "data:image/png;base64,...."
            )
        )
    }
}


package com.yourcompany.zeiterfassung.routes

import com.yourcompany.zeiterfassung.service.AccountDeletionService
import com.yourcompany.zeiterfassung.service.DeletionLinks
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class DeletionCheckDTO(val canDelete: Boolean, val error: String? = null, val links: DeletionLinks? = null)

// Extract numeric userId from JWT; supports either numeric or string claim
private fun extractUserId(jwt: JWTPrincipal): Int? {
    val claim = jwt.payload.getClaim("id")
    return claim.asInt() ?: claim.asString()?.toIntOrNull()
}

fun Route.accountDeletionRoutes(service: AccountDeletionService) {
    authenticate("bearerAuth") {
        route("/v1/account") {

            get("/deletion-check") {
                val jwt = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", null))
                val userId = extractUserId(jwt)
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", null))

                val res = service.canDelete(userId)
                if (res.canDelete) {
                    call.respond(HttpStatusCode.OK, DeletionCheckDTO(true))
                } else {
                    call.respond(HttpStatusCode.Conflict, DeletionCheckDTO(false, "last_admin", res.links))
                }
            }

            delete {
                val jwt = call.principal<JWTPrincipal>()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", null))
                val userId = extractUserId(jwt)
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", null))

                // идемпотентность
                val idempotencyKey = call.request.headers["Idempotency-Key"]

                // быстрый pre-check "последний админ"
                val res = service.canDelete(userId)
                if (!res.canDelete) {
                    return@delete call.respond(HttpStatusCode.Conflict, DeletionCheckDTO(false, "last_admin", res.links))
                }

                val ok = service.deleteAccount(userId, idempotencyKey)
                if (ok) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.Accepted) // rare: если поставишь асинхронную джобу
            }

            post("/delete") {
                val jwt = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", null))
                val userId = extractUserId(jwt)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", null))

                val idempotencyKey = call.request.headers["Idempotency-Key"]

                val res = service.canDelete(userId)
                if (!res.canDelete) {
                    return@post call.respond(HttpStatusCode.Conflict, DeletionCheckDTO(false, "last_admin", res.links))
                }

                val ok = service.deleteAccount(userId, idempotencyKey)
                if (ok) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.Accepted)
            }
        }
    }
}
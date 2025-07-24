package com.yourcompany.zeiterfassung.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.yourcompany.zeiterfassung.models.Proofs
import com.yourcompany.zeiterfassung.dto.ProofDto
import com.yourcompany.zeiterfassung.dto.RespondProofRequest
import com.yourcompany.zeiterfassung.dto.ProofResponse
import com.yourcompany.zeiterfassung.dto.ValidationErrorResponseDto
import com.yourcompany.zeiterfassung.dto.ProofCreateRequest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.*

// 🌍 Haversine formula
private fun distanceMeters(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double
): Double {
    val R = 6_371_000.0
    val φ1 = Math.toRadians(lat1)
    val φ2 = Math.toRadians(lat2)
    val Δφ = Math.toRadians(lat2 - lat1)
    val Δλ = Math.toRadians(lon2 - lon1)
    val a = sin(Δφ / 2).pow(2.0) +
            cos(φ1) * cos(φ2) * sin(Δλ / 2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

fun Route.proofsRoutes() {
    authenticate("bearerAuth") {
        route("/api/proofs") {

            // 📅 GET /api/proofs/today
            get("/today") {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("id").asString().toInt()
                val today = LocalDate.now()

                val list = transaction {
                    Proofs.select {
                        (Proofs.userId eq userId) and
                                (Proofs.date eq today)
                    }.map {
                        ProofDto(
                            id = it[Proofs.id],
                            latitude = it[Proofs.latitude],
                            longitude = it[Proofs.longitude],
                            radius = it[Proofs.radius],
                            slot = it[Proofs.slot].toInt(),
                            sentAt = it[Proofs.sentAt]?.atZone(ZoneId.systemDefault())?.toInstant() ?: Instant.EPOCH,
                            responded = it[Proofs.responded]
                        )
                    }
                }

                call.respond(list)
            }


            // ✏️ POST /api/proofs — создание новой проверки
            post {
              try {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("id").asString().toInt()
                val req = call.receive<ProofCreateRequest>()
                val now = Instant.now()

                // Вставляем новую запись в БД
                val newId = transaction {
                  Proofs.insert {
                    it[Proofs.userId] = userId
                    it[Proofs.latitude] = req.latitude
                    it[Proofs.longitude] = req.longitude
                    it[Proofs.radius] = req.radius
                    it[Proofs.slot] = req.slot.toShort()
                    it[Proofs.sentAt] = LocalDateTime.ofInstant(now, ZoneId.systemDefault())
                  } get Proofs.id
                }

                val created = ProofDto(
                  id = newId,
                  latitude = req.latitude,
                  longitude = req.longitude,
                  radius = req.radius,
                  slot = req.slot,
                  sentAt = now,
                  responded = false
                )
                call.respond(created)
              } catch (e: Throwable) {
                call.application.log.error("Error creating proof", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.localizedMessage ?: "Unknown error")))
              }
            }

            // 📩 POST /api/proofs/{id}/respond
            post("/{proofId}/respond") {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("id").asString().toInt()
                val proofId = call.parameters["proofId"]!!.toInt()
                val req = call.receive<RespondProofRequest>()
                val now = Instant.now()

                // Collect validation errors
                val details = mutableMapOf<String, MutableList<String>>()
                val success = transaction {
                    // 1) Check ownership and existence
                    val row = Proofs.select {
                        (Proofs.id eq proofId) and
                                (Proofs.userId eq userId)
                    }.singleOrNull().also {
                        if (it == null) {
                            details.getOrPut("proofId") { mutableListOf() }
                                .add("Proof not found or unauthorized")
                        }
                    } ?: return@transaction false

                    val lat0 = row[Proofs.latitude]
                    val lon0 = row[Proofs.longitude]
                    val radius = row[Proofs.radius]
                    val slot = row[Proofs.slot].toInt()
                    val sentAt = row[Proofs.sentAt] ?: run {
                        details.getOrPut("sentAt") { mutableListOf() }
                            .add("Missing sent timestamp")
                        return@transaction false
                    }

                    // 2) Check time window
                    val endSlotInstant = when (slot) {
                        1 -> sentAt.withHour(12).withMinute(0).withSecond(0)
                        else -> sentAt.withHour(17).withMinute(0).withSecond(0)
                    }.atZone(ZoneId.systemDefault()).toInstant()
                    if (now.isAfter(endSlotInstant)) {
                        details.getOrPut("slot") { mutableListOf() }
                            .add("Slot has expired")
                        return@transaction false
                    }

                    // 3) Check distance
                    val dist = distanceMeters(lat0, lon0, req.latitude, req.longitude)
                    if (dist > radius) {
                        details.getOrPut("location") { mutableListOf() }
                            .add("Distance \$dist exceeds radius \$radius")
                        return@transaction false
                    }

                    // Passed all checks → mark responded
                    Proofs.update({ Proofs.id eq proofId }) {
                        it[responded] = true
                        it[respondedAt] = LocalDateTime.ofInstant(now, ZoneId.systemDefault())
                    }
                    true
                }

                if (success) {
                    call.respond(ProofResponse(status = "ok", timestamp = now.toString()))
                } else {
                    call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        ValidationErrorResponseDto(
                            error = "validation_failed",
                            details = details
                        )
                    )
                }
            }
        }
    }
}

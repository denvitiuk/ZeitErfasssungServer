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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.*
import com.yourcompany.zeiterfassung.db.Projects
import com.yourcompany.zeiterfassung.db.ProjectMembers
import com.yourcompany.zeiterfassung.db.Users
import org.jetbrains.exposed.dao.id.EntityID
import java.time.LocalTime
import java.security.SecureRandom

// 🌍 Haversine formula
private fun distanceMeters(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double
): Double {
    val R = 6_371_000.0
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2).pow(2.0) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers: timezone, project id, random time in window
// ─────────────────────────────────────────────────────────────────────────────
private fun resolveZone(call: ApplicationCall): ZoneId {
    val q = call.request.queryParameters["tz"]?.trim()
    val h = call.request.headers["X-Timezone"]?.trim()
    return try {
        when {
            !q.isNullOrBlank() -> ZoneId.of(q)
            !h.isNullOrBlank() -> ZoneId.of(h)
            else -> ZoneId.systemDefault()
        }
    } catch (_: Exception) {
        ZoneId.systemDefault()
    }
}

private fun parseProjectId(call: ApplicationCall): Int? {
    val q = call.request.queryParameters["projectId"]?.trim()
    val h = call.request.headers["X-Project-Id"]?.trim()
    return (q ?: h)?.toIntOrNull()
}

private val rnd = SecureRandom()

private fun randomLocalDateTimeInWindow(day: LocalDate, startHour: Int, endHourExclusive: Int, zone: ZoneId): LocalDateTime {
    val hour = startHour + rnd.nextInt(endHourExclusive - startHour) // [start, end)
    val minute = rnd.nextInt(60)
    val second = rnd.nextInt(60)
    return ZonedDateTime.of(day, LocalTime.of(hour, minute, second), zone).toLocalDateTime()
}

// Membership check: is the user a member of the project?
private fun isMember(userId: Int, projectId: Int): Boolean = transaction {
    ProjectMembers.select {
        (ProjectMembers.projectId eq EntityID(projectId, Projects)) and
        (ProjectMembers.userId eq EntityID(userId, Users))
    }.any()
}

fun Route.proofsRoutes() {
    authenticate("bearerAuth") {
        route("/api/proofs") {

            // 📅 GET /api/proofs/today
            get("/today") {
                val principal = call.principal<JWTPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                val userId = principal.payload.getClaim("id").asString().toInt()
                val projectId = parseProjectId(call)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "project_required"))

                val zone = resolveZone(call)

                // Load project and ensure it has coordinates
                val projectRow = transaction {
                    Projects
                        .slice(Projects.id, Projects.lat, Projects.lng)
                        .select { Projects.id eq projectId }
                        .singleOrNull()
                } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "project_not_found"))

                // Ensure the user is a member of this project
                if (!isMember(userId, projectId)) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden_not_member"))
                }

                val projLat = projectRow[Projects.lat]
                val projLng = projectRow[Projects.lng]
                if (projLat == null || projLng == null) {
                    return@get call.respond(HttpStatusCode.Conflict, mapOf("error" to "project_location_missing"))
                }

                val today = LocalDate.now(zone)

                // Ensure two slots exist for today for this user+project
                transaction {
                    val existing = Proofs.select {
                        (Proofs.userId eq userId) and
                        (Proofs.projectId eq projectId) and
                        (Proofs.date eq today)
                    }.associateBy { it[Proofs.slot].toInt() }

                    val defaultRadius = 150

                    if (existing[1] == null) {
                        val sentAtLdt = randomLocalDateTimeInWindow(today, 9, 12, zone)
                        try {
                            Proofs.insert {
                                it[Proofs.userId] = userId
                                it[Proofs.projectId] = projectId
                                it[Proofs.latitude] = projLat
                                it[Proofs.longitude] = projLng
                                it[Proofs.radius] = defaultRadius
                                it[Proofs.date] = today
                                it[Proofs.slot] = 1
                                it[Proofs.sentAt] = sentAtLdt
                                it[Proofs.responded] = false
                            }
                        } catch (_: Exception) {
                            // ignore duplicates in case of race
                        }
                    }
                    if (existing[2] == null) {
                        val sentAtLdt = randomLocalDateTimeInWindow(today, 13, 17, zone)
                        try {
                            Proofs.insert {
                                it[Proofs.userId] = userId
                                it[Proofs.projectId] = projectId
                                it[Proofs.latitude] = projLat
                                it[Proofs.longitude] = projLng
                                it[Proofs.radius] = defaultRadius
                                it[Proofs.date] = today
                                it[Proofs.slot] = 2
                                it[Proofs.sentAt] = sentAtLdt
                                it[Proofs.responded] = false
                            }
                        } catch (_: Exception) {
                            // ignore duplicates in case of race
                        }
                    }
                }

                // Return today's proofs with sentAt converted using the same zone
                val list = transaction {
                    Proofs.select {
                        (Proofs.userId eq userId) and
                        (Proofs.projectId eq projectId) and
                        (Proofs.date eq today)
                    }
                    .orderBy(Proofs.slot to SortOrder.ASC)
                    .map {
                        val ldt = it[Proofs.sentAt]
                        val instant = ldt?.atZone(zone)?.toInstant() ?: Instant.EPOCH
                        ProofDto(
                            id = it[Proofs.id],
                            latitude = it[Proofs.latitude],
                            longitude = it[Proofs.longitude],
                            radius = it[Proofs.radius],
                            slot = it[Proofs.slot].toInt(),
                            sentAt = instant,
                            responded = it[Proofs.responded]
                        )
                    }
                }

                call.respond(list)
            }


            // ✏️ POST /api/proofs — создание новой проверки
            post {
                try {
                  val principal = call.principal<JWTPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                  val userId = principal.payload.getClaim("id").asString().toInt()
                  val projectId = parseProjectId(call)
                      ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "project_required"))

                  val zone = resolveZone(call)

                  // Load project coordinates
                  val projectRow = transaction {
                      Projects
                          .slice(Projects.id, Projects.lat, Projects.lng)
                          .select { Projects.id eq projectId }
                          .singleOrNull()
                  } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "project_not_found"))

                  // Ensure the user is a member of this project
                  if (!isMember(userId, projectId)) {
                      return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden_not_member"))
                  }

                  val projLat = projectRow[Projects.lat]
                  val projLng = projectRow[Projects.lng]
                  if (projLat == null || projLng == null) {
                      return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "project_location_missing"))
                  }

                  val nowZdt = ZonedDateTime.now(zone)
                  val slotVal: Short = when (nowZdt.hour) {
                      in 9 until 12 -> 1
                      in 13 until 17 -> 2
                      else -> 0
                  }

                  val newId = transaction {
                    Proofs.insert {
                      it[Proofs.userId] = userId
                      it[Proofs.projectId] = projectId
                      it[Proofs.latitude] = projLat
                      it[Proofs.longitude] = projLng
                      it[Proofs.radius] = 150
                      it[Proofs.date] = nowZdt.toLocalDate()
                      it[Proofs.slot] = slotVal
                      it[Proofs.sentAt] = nowZdt.toLocalDateTime()
                      it[Proofs.responded] = false
                    } get Proofs.id
                  }

                  val created = ProofDto(
                    id = newId,
                    latitude = projLat,
                    longitude = projLng,
                    radius = 150,
                    slot = slotVal.toInt(),
                    sentAt = nowZdt.toInstant(),
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
                val zone = resolveZone(call)

                var notMember = false

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

                    val slot = row[Proofs.slot].toInt()
                    val sentAt = row[Proofs.sentAt] ?: run {
                        details.getOrPut("sentAt") { mutableListOf() }
                            .add("Missing sent timestamp")
                        return@transaction false
                    }

                    // Resolve project center from DB
                    val projectId = row[Proofs.projectId]
                    if (projectId == null) {
                        details.getOrPut("project") { mutableListOf() }.add("Missing project for proof")
                        return@transaction false
                    }

                    // Check membership inside transaction; if not, mark and abort
                    val member = ProjectMembers.select {
                        (ProjectMembers.projectId eq EntityID(projectId, Projects)) and
                        (ProjectMembers.userId eq EntityID(userId, Users))
                    }.any()
                    if (!member) {
                        notMember = true
                        return@transaction false
                    }

                    val proj = Projects
                        .slice(Projects.lat, Projects.lng)
                        .select { Projects.id eq projectId }
                        .singleOrNull()
                    val centerLat = proj?.get(Projects.lat)
                    val centerLng = proj?.get(Projects.lng)
                    if (centerLat == null || centerLng == null) {
                        details.getOrPut("project") { mutableListOf() }.add("project_location_missing")
                        return@transaction false
                    }

                    val radius = row[Proofs.radius]

                    // 2) Check time window in correct zone
                    val endLdt = when (slot) {
                        1 -> sentAt.withHour(12).withMinute(0).withSecond(0).withNano(0)
                        else -> sentAt.withHour(17).withMinute(0).withSecond(0).withNano(0)
                    }
                    val endSlotInstant = endLdt.atZone(zone).toInstant()
                    if (now.isAfter(endSlotInstant)) {
                        details.getOrPut("slot") { mutableListOf() }.add("expired")
                        return@transaction false
                    }

                    // 3) Check distance vs project center
                    val dist = distanceMeters(centerLat, centerLng, req.latitude, req.longitude)
                    if (dist > radius) {
                        details.getOrPut("site") { mutableListOf() }.add("outside_geofence")
                        return@transaction false
                    }

                    // Passed all checks → mark responded
                    Proofs.update({ Proofs.id eq proofId }) {
                        it[responded] = true
                        it[respondedAt] = LocalDateTime.ofInstant(now, zone)
                    }
                    true
                }

                if (notMember) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden_not_member"))
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

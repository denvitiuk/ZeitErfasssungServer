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
import com.yourcompany.zeiterfassung.models.Logs
import com.yourcompany.zeiterfassung.dto.ProofDto
import com.yourcompany.zeiterfassung.dto.RespondProofRequest
import com.yourcompany.zeiterfassung.dto.ProofResponse
import com.yourcompany.zeiterfassung.dto.ValidationErrorResponseDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.ZoneOffset
import kotlin.math.*
import com.yourcompany.zeiterfassung.db.Projects
import com.yourcompany.zeiterfassung.db.ProjectMembers
import com.yourcompany.zeiterfassung.db.Users
import org.jetbrains.exposed.dao.id.EntityID
import java.time.LocalTime
import java.security.SecureRandom
import kotlinx.serialization.Serializable

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
            else -> ZoneId.of("Europe/Berlin")
        }
    } catch (_: Exception) {
        ZoneId.of("Europe/Berlin")
    }
}

private fun parseProjectId(call: ApplicationCall): Int? {
    val q = call.request.queryParameters["projectId"]?.trim()
    val h = call.request.headers["X-Project-Id"]?.trim()
    return (q ?: h)?.toIntOrNull()
}

private data class ProjectResolution(val id: Int?, val reason: String, val choices: List<Int> = emptyList())

private fun autoResolveProject(call: ApplicationCall, userId: Int, zone: ZoneId): ProjectResolution {
    // 1) Explicit projectId via query/header
    val explicit = parseProjectId(call)
    if (explicit != null) return ProjectResolution(explicit, "explicit")

    val today = LocalDate.now(zone)

    // 2) Last log today and active (IN) → use that project
    val lastLog = transaction {
        Logs
            .slice(Logs.projectId, Logs.action, Logs.timestamp)
            .select { Logs.userId eq userId }
            .orderBy(Logs.timestamp, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
    }
    if (lastLog != null) {
        val ts = lastLog[Logs.timestamp]
        val d = ts.atZone(zone).toLocalDate()
        if (d == today && lastLog[Logs.action].equals("in", ignoreCase = true)) {
            val pid = lastLog[Logs.projectId]
            if (pid != null) return ProjectResolution(pid.value, "inferred_from_active_shift")
        }
    }

    // 3) Last IN today across projects
    val lastInToday = transaction {
        Logs
            .slice(Logs.projectId, Logs.timestamp)
            .select { (Logs.userId eq userId) and (Logs.action eq "in") }
            .orderBy(Logs.timestamp, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
    }
    if (lastInToday != null) {
        val ts = lastInToday[Logs.timestamp]
        if (ts.atZone(zone).toLocalDate() == today) {
            val pid = lastInToday[Logs.projectId]
            if (pid != null) return ProjectResolution(pid.value, "inferred_from_last_in")
        }
    }

    // 4) Single membership → use that project
    val memberships: List<Int> = transaction {
        ProjectMembers
            .slice(ProjectMembers.projectId)
            .select { ProjectMembers.userId eq userId }
            .map { it[ProjectMembers.projectId].value }
    }
    return when (memberships.size) {
        1 -> ProjectResolution(memberships.first(), "inferred_from_single_membership")
        0 -> ProjectResolution(null, "project_required")
        else -> ProjectResolution(null, "project_ambiguous", memberships)
    }
}

private val rnd = SecureRandom()

private fun randomLocalDateTimeInWindow(day: LocalDate, startHour: Int, endHourExclusive: Int, zone: ZoneId): LocalDateTime {
    val hour = startHour + rnd.nextInt(endHourExclusive - startHour) // [start, end)
    val minute = rnd.nextInt(60)
    val second = rnd.nextInt(60)
    return ZonedDateTime.of(day, LocalTime.of(hour, minute, second), zone).toLocalDateTime()
}

@Serializable
data class ErrorRequired(val error: String = "project_required")

@Serializable
data class ErrorAmbiguous(val error: String = "project_ambiguous", val choices: List<Int>)

@Serializable
data class ErrorNoActiveShift(val error: String = "no_active_shift", val message: String, val shiftActive: Boolean)

@Serializable
data class ErrorDebugOnly(val error: String = "debug_only")

@Serializable
data class TestCreatedDto(val id: Int, val slot: Int, val sentAt: String, val mode: String)

// Membership check: is the user a member of the project?
private fun isMember(userId: Int, projectId: Int): Boolean = transaction {
    ProjectMembers.select {
        (ProjectMembers.projectId eq projectId) and
        (ProjectMembers.userId eq userId)
    }.any()
}

// Shift gate: active if the last log for today is IN (in the requested zone)
private fun isShiftActiveToday(userId: Int, projectId: Int, zone: ZoneId): Boolean = transaction {
    val row = Logs
        .slice(Logs.action, Logs.timestamp)
        .select { (Logs.userId eq userId) and (Logs.projectId eq projectId) }
        .orderBy(Logs.timestamp, SortOrder.DESC)
        .limit(1)
        .singleOrNull()

    if (row == null) return@transaction false
    val action = row[Logs.action]
    val ts = row[Logs.timestamp]
    val today = LocalDate.now(zone)
    val d = ts.atZone(zone).toLocalDate()
    return@transaction (d == today && action.equals("in", ignoreCase = true))
}

fun Route.proofsRoutes() {
    authenticate("bearerAuth") {
        route("/api/proofs") {

            // 📅 GET /api/proofs/today
            get("/today") {
                val principal = call.principal<JWTPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                val userId = principal.payload.getClaim("id").asString().toInt()
                val zone = resolveZone(call)
                val pr = autoResolveProject(call, userId, zone)
                if (pr.id == null) {
                    val body = if (pr.reason == "project_ambiguous") mapOf("error" to "project_ambiguous", "choices" to pr.choices) else mapOf("error" to "project_required")
                    return@get call.respond(if (pr.reason == "project_ambiguous") HttpStatusCode.Conflict else HttpStatusCode.BadRequest, body)
                }
                val projectId = pr.id
                call.response.headers.append("X-Resolved-Project-Id", projectId.toString())
                call.response.headers.append("X-Reason", pr.reason)
                call.response.headers.append("X-Timezone-Used", zone.id)

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

                // ---- QR-gate: create slots only if today's shift is active (last log today = IN)
                val requireShift = call.request.queryParameters["requireShift"] == "1" ||
                                   call.request.queryParameters["requireActiveShift"] == "1"
                val shiftActive = isShiftActiveToday(userId, projectId, zone)

                val allowCreation = shiftActive
                if (!shiftActive) {
                    if (requireShift) {
                        return@get call.respond(
                            HttpStatusCode.Conflict,
                            ErrorNoActiveShift(message = "No IN log for today — slots are not created", shiftActive = false)
                        )
                    } else {
                        call.response.headers.append("X-Reason", "no_active_shift")
                        // Do not return early: we will still return existing proofs for today
                    }
                }

                // Ensure two slots exist for today only when the shift is active
                if (allowCreation) {
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
                                    it[Proofs.sentAt] = ZonedDateTime.of(sentAtLdt, zone)
                                        .withZoneSameInstant(ZoneOffset.UTC)
                                        .toLocalDateTime()
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
                                    it[Proofs.sentAt] = ZonedDateTime.of(sentAtLdt, zone)
                                        .withZoneSameInstant(ZoneOffset.UTC)
                                        .toLocalDateTime()
                                    it[Proofs.responded] = false
                                }
                            } catch (_: Exception) {
                                // ignore duplicates in case of raceы
                            }
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
                        val instant = ldt?.atZone(ZoneOffset.UTC)?.toInstant() ?: Instant.EPOCH
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
                  val zone = resolveZone(call)
                  val pr = autoResolveProject(call, userId, zone)
                  if (pr.id == null) {
                      val body = if (pr.reason == "project_ambiguous") mapOf("error" to "project_ambiguous", "choices" to pr.choices) else mapOf("error" to "project_required")
                      return@post call.respond(if (pr.reason == "project_ambiguous") HttpStatusCode.Conflict else HttpStatusCode.BadRequest, body)
                  }
                  val projectId = pr.id
                  call.response.headers.append("X-Resolved-Project-Id", projectId.toString())
                  call.response.headers.append("X-Reason", pr.reason)

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
                      it[Proofs.sentAt] = nowZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
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
                        (ProjectMembers.projectId eq projectId) and
                        (ProjectMembers.userId eq userId)
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
                    val sentZdt = sentAt.atZone(ZoneOffset.UTC).withZoneSameInstant(zone)
                    val endZdt = if (slot == 1) {
                        sentZdt.withHour(12).withMinute(0).withSecond(0).withNano(0)
                    } else {
                        sentZdt.withHour(17).withMinute(0).withSecond(0).withNano(0)
                    }
                    val endSlotInstant = endZdt.toInstant()
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
                        it[respondedAt] = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
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
            // 🧪 POST /api/proofs/test?debug=1&push=1&mode=adhoc|replace[&slot=1|2]
            // Minimal server-side for the Test button. It does **not** send APNs here —
            // it only prepares a proof and returns its id/schedule. Your push worker
            // (or a later step) may pick it up and deliver a real alert.
            post("/test") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                val userId = principal.payload.getClaim("id").asString().toInt()
                val zone = resolveZone(call)
                val pr = autoResolveProject(call, userId, zone)
                if (pr.id == null) {
                    val body = if (pr.reason == "project_ambiguous") mapOf("error" to "project_ambiguous", "choices" to pr.choices) else mapOf("error" to "project_required")
                    return@post call.respond(if (pr.reason == "project_ambiguous") HttpStatusCode.Conflict else HttpStatusCode.BadRequest, body)
                }
                val projectId = pr.id
                call.response.headers.append("X-Resolved-Project-Id", projectId.toString())
                call.response.headers.append("X-Reason", pr.reason)

                val debug = (call.request.queryParameters["debug"] == "1") ||
                            (call.request.headers["X-Debug-Push"] == "1")
                // allow only debug for now — so боевые лимиты не трогаем
                if (!debug) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorDebugOnly())
                }

                val requestedMode = call.request.queryParameters["mode"]?.lowercase() ?: "adhoc"
                val slotParam = call.request.queryParameters["slot"]?.toIntOrNull()
                val fallback = call.request.queryParameters["fallback"]?.lowercase()
                var effectiveMode = requestedMode

                // Load project + check membership
                val projectRow = transaction {
                    Projects
                        .slice(Projects.id, Projects.lat, Projects.lng)
                        .select { Projects.id eq projectId }
                        .singleOrNull()
                } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "project_not_found"))

                if (!isMember(userId, projectId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden_not_member"))
                }

                val projLat = projectRow[Projects.lat]
                val projLng = projectRow[Projects.lng]
                if (projLat == null || projLng == null) {
                    return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "project_location_missing"))
                }

                val nowZdt = ZonedDateTime.now(zone)
                val fireAtZdt = nowZdt.plusSeconds(15)

                // If we are replacing a real slot, require an active shift today; allow safe downgrade in debug or when fallback=adhoc
                if (requestedMode == "replace") {
                    val shiftActive = isShiftActiveToday(userId, projectId, zone)
                    if (!shiftActive) {
                        val allowFallback = (fallback == "adhoc") || debug
                        if (allowFallback) {
                            effectiveMode = "adhoc"
                            call.response.headers.append("X-Mode-Downgraded", "replace→adhoc")
                        } else {
                            return@post call.respond(
                                HttpStatusCode.Conflict,
                                ErrorNoActiveShift(message = "Cannot replace slot when no IN log today", shiftActive = false)
                            )
                        }
                    }
                }
                call.response.headers.append("X-Effective-Mode", effectiveMode)

                // helper: choose slot by current time
                fun chooseSlotByTime(h: Int): Short = when (h) {
                    in 9..11 -> 1
                    in 13..16 -> 2
                    else -> 1 // default to 1 for adhoc
                }.toShort()

                val result: TestCreatedDto = transaction {
                    when (effectiveMode) {
                        "replace" -> {
                            val chosenSlot: Short = (slotParam ?: chooseSlotByTime(nowZdt.hour)).toShort()
                            val today = nowZdt.toLocalDate()
                            val existing = Proofs.select {
                                (Proofs.userId eq userId) and
                                (Proofs.projectId eq projectId) and
                                (Proofs.date eq today) and
                                (Proofs.slot eq chosenSlot)
                            }.singleOrNull()

                            val idVal = if (existing != null) {
                                Proofs.update({ Proofs.id eq existing[Proofs.id] }) {
                                    it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                                }
                                existing[Proofs.id]
                            } else {
                                (Proofs.insert {
                                    it[Proofs.userId] = userId
                                    it[Proofs.projectId] = projectId
                                    it[Proofs.latitude] = projLat
                                    it[Proofs.longitude] = projLng
                                    it[Proofs.radius] = 150
                                    it[Proofs.date] = today
                                    it[Proofs.slot] = chosenSlot
                                    it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                                    it[Proofs.responded] = false
                                } get Proofs.id)
                            }
                            TestCreatedDto(
                                id = idVal,
                                slot = chosenSlot.toInt(),
                                sentAt = fireAtZdt.toInstant().toString(),
                                mode = effectiveMode
                            )
                        }
                        else -> { // adhoc (idempotent)
                            val chosenSlot: Short = (slotParam ?: chooseSlotByTime(nowZdt.hour)).toShort()
                            val today = nowZdt.toLocalDate()
                            val existing = Proofs.select {
                                (Proofs.userId eq userId) and
                                (Proofs.projectId eq projectId) and
                                (Proofs.date eq today) and
                                (Proofs.slot eq chosenSlot)
                            }.singleOrNull()

                            val idVal = if (existing != null) {
                                Proofs.update({ Proofs.id eq existing[Proofs.id] }) {
                                    it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                                    it[Proofs.responded] = false
                                }
                                existing[Proofs.id]
                            } else {
                                (Proofs.insert {
                                    it[Proofs.userId] = userId
                                    it[Proofs.projectId] = projectId
                                    it[Proofs.latitude] = projLat
                                    it[Proofs.longitude] = projLng
                                    it[Proofs.radius] = 150
                                    it[Proofs.date] = nowZdt.toLocalDate()
                                    it[Proofs.slot] = chosenSlot
                                    it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                                    it[Proofs.responded] = false
                                } get Proofs.id)
                            }
                            TestCreatedDto(
                                id = idVal,
                                slot = chosenSlot.toInt(),
                                sentAt = fireAtZdt.toInstant().toString(),
                                mode = effectiveMode
                            )
                        }
                    }
                }

                call.respond(HttpStatusCode.OK, result)
            }
            // 🧪 POST /api/proofs/create-test — shorthand for adhoc test (defaults to debug mode)
            post("/create-test") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                val userId = principal.payload.getClaim("id").asString().toInt()
                val zone = resolveZone(call)
                val pr = autoResolveProject(call, userId, zone)
                if (pr.id == null) {
                    val body = if (pr.reason == "project_ambiguous") mapOf("error" to "project_ambiguous", "choices" to pr.choices) else mapOf("error" to "project_required")
                    return@post call.respond(if (pr.reason == "project_ambiguous") HttpStatusCode.Conflict else HttpStatusCode.BadRequest, body)
                }
                val projectId = pr.id
                call.response.headers.append("X-Resolved-Project-Id", projectId.toString())
                call.response.headers.append("X-Reason", pr.reason)

                // Load project + check membership
                val projectRow = transaction {
                    Projects
                        .slice(Projects.id, Projects.lat, Projects.lng)
                        .select { Projects.id eq projectId }
                        .singleOrNull()
                } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "project_not_found"))

                if (!isMember(userId, projectId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden_not_member"))
                }

                val projLat = projectRow[Projects.lat]
                val projLng = projectRow[Projects.lng]
                if (projLat == null || projLng == null) {
                    return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "project_location_missing"))
                }

                val nowZdt = ZonedDateTime.now(zone)
                val fireAtZdt = nowZdt.plusSeconds(15)

                fun chooseSlotByTime(h: Int): Short = when (h) {
                    in 9..11 -> 1
                    in 13..16 -> 2
                    else -> 1
                }.toShort()

                val slotParam = call.request.queryParameters["slot"]?.toIntOrNull()
                val chosenSlot: Short = (slotParam ?: chooseSlotByTime(nowZdt.hour)).toShort()
                val result: TestCreatedDto = transaction {
                    val today = nowZdt.toLocalDate()
                    val existing = Proofs.select {
                        (Proofs.userId eq userId) and
                        (Proofs.projectId eq projectId) and
                        (Proofs.date eq today) and
                        (Proofs.slot eq chosenSlot)
                    }.singleOrNull()

                    val idVal = if (existing != null) {
                        Proofs.update({ Proofs.id eq existing[Proofs.id] }) {
                            it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                            it[Proofs.responded] = false
                        }
                        existing[Proofs.id]
                    } else {
                        (Proofs.insert {
                            it[Proofs.userId] = userId
                            it[Proofs.projectId] = projectId
                            it[Proofs.latitude] = projLat
                            it[Proofs.longitude] = projLng
                            it[Proofs.radius] = 150
                            it[Proofs.date] = nowZdt.toLocalDate()
                            it[Proofs.slot] = chosenSlot
                            it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                            it[Proofs.responded] = false
                        } get Proofs.id)
                    }
                    TestCreatedDto(id = idVal, slot = chosenSlot.toInt(), sentAt = fireAtZdt.toInstant().toString(), mode = "adhoc")
                }

                call.respond(HttpStatusCode.OK, result)
            }
        }
        // ─────────────────────────────────────────────────────────────────
        // Aliases without /api prefix for backward/SDK compatibility
        // ─────────────────────────────────────────────────────────────────
        route("/proofs") {
            // POST /proofs/test — same logic as /api/proofs/test
            post("/test") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                val userId = principal.payload.getClaim("id").asString().toInt()
                val zone = resolveZone(call)
                val pr = autoResolveProject(call, userId, zone)
                if (pr.id == null) {
                    val body = if (pr.reason == "project_ambiguous") mapOf("error" to "project_ambiguous", "choices" to pr.choices) else mapOf("error" to "project_required")
                    return@post call.respond(if (pr.reason == "project_ambiguous") HttpStatusCode.Conflict else HttpStatusCode.BadRequest, body)
                }
                val projectId = pr.id
                call.response.headers.append("X-Resolved-Project-Id", projectId.toString())
                call.response.headers.append("X-Reason", pr.reason)

                val debug = (call.request.queryParameters["debug"] == "1") ||
                            (call.request.headers["X-Debug-Push"] == "1")
                if (!debug) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorDebugOnly())
                }

                val requestedMode = call.request.queryParameters["mode"]?.lowercase() ?: "adhoc"
                val slotParam = call.request.queryParameters["slot"]?.toIntOrNull()
                val fallback = call.request.queryParameters["fallback"]?.lowercase()
                var effectiveMode = requestedMode

                // Load project + check membership
                val projectRow = transaction {
                    Projects
                        .slice(Projects.id, Projects.lat, Projects.lng)
                        .select { Projects.id eq projectId }
                        .singleOrNull()
                } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "project_not_found"))

                if (!isMember(userId, projectId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden_not_member"))
                }

                val projLat = projectRow[Projects.lat]
                val projLng = projectRow[Projects.lng]
                if (projLat == null || projLng == null) {
                    return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "project_location_missing"))
                }

                val nowZdt = ZonedDateTime.now(zone)
                val fireAtZdt = nowZdt.plusSeconds(15)

                // If we are replacing a real slot, require an active shift today; allow safe downgrade in debug or when fallback=adhoc
                if (requestedMode == "replace") {
                    val shiftActive = isShiftActiveToday(userId, projectId, zone)
                    if (!shiftActive) {
                        val allowFallback = (fallback == "adhoc") || debug
                        if (allowFallback) {
                            effectiveMode = "adhoc"
                            call.response.headers.append("X-Mode-Downgraded", "replace→adhoc")
                        } else {
                            return@post call.respond(
                                HttpStatusCode.Conflict,
                                ErrorNoActiveShift(message = "Cannot replace slot when no IN log today", shiftActive = false)
                            )
                        }
                    }
                }
                call.response.headers.append("X-Effective-Mode", effectiveMode)

                fun chooseSlotByTime(h: Int): Short = when (h) {
                    in 9..11 -> 1
                    in 13..16 -> 2
                    else -> 1
                }.toShort()

                val result: TestCreatedDto = transaction {
                    when (effectiveMode) {
                        "replace" -> {
                            val chosenSlot: Short = (slotParam ?: chooseSlotByTime(nowZdt.hour)).toShort()
                            val today = nowZdt.toLocalDate()
                            val existing = Proofs.select {
                                (Proofs.userId eq userId) and
                                (Proofs.projectId eq projectId) and
                                (Proofs.date eq today) and
                                (Proofs.slot eq chosenSlot)
                            }.singleOrNull()

                            val idVal = if (existing != null) {
                                Proofs.update({ Proofs.id eq existing[Proofs.id] }) {
                                    it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                                }
                                existing[Proofs.id]
                            } else {
                                (Proofs.insert {
                                    it[Proofs.userId] = userId
                                    it[Proofs.projectId] = projectId
                                    it[Proofs.latitude] = projLat
                                    it[Proofs.longitude] = projLng
                                    it[Proofs.radius] = 150
                                    it[Proofs.date] = today
                                    it[Proofs.slot] = chosenSlot
                                    it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                                    it[Proofs.responded] = false
                                } get Proofs.id)
                            }
                            TestCreatedDto(
                                id = idVal,
                                slot = chosenSlot.toInt(),
                                sentAt = fireAtZdt.toInstant().toString(),
                                mode = effectiveMode
                            )
                        }
                        else -> { // adhoc (idempotent)
                            val chosenSlot: Short = (slotParam ?: chooseSlotByTime(nowZdt.hour)).toShort()
                            val today = nowZdt.toLocalDate()
                            val existing = Proofs.select {
                                (Proofs.userId eq userId) and
                                (Proofs.projectId eq projectId) and
                                (Proofs.date eq today) and
                                (Proofs.slot eq chosenSlot)
                            }.singleOrNull()

                            val idVal = if (existing != null) {
                                Proofs.update({ Proofs.id eq existing[Proofs.id] }) {
                                    it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                                    it[Proofs.responded] = false
                                }
                                existing[Proofs.id]
                            } else {
                                (Proofs.insert {
                                    it[Proofs.userId] = userId
                                    it[Proofs.projectId] = projectId
                                    it[Proofs.latitude] = projLat
                                    it[Proofs.longitude] = projLng
                                    it[Proofs.radius] = 150
                                    it[Proofs.date] = nowZdt.toLocalDate()
                                    it[Proofs.slot] = chosenSlot
                                    it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                                    it[Proofs.responded] = false
                                } get Proofs.id)
                            }
                            TestCreatedDto(
                                id = idVal,
                                slot = chosenSlot.toInt(),
                                sentAt = fireAtZdt.toInstant().toString(),
                                mode = effectiveMode
                            )
                        }
                    }
                }

                call.respond(HttpStatusCode.OK, result)
            }

            // POST /proofs/create-test — shorthand alias (defaults to adhoc)
            post("/create-test") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                val userId = principal.payload.getClaim("id").asString().toInt()
                val zone = resolveZone(call)
                val pr = autoResolveProject(call, userId, zone)
                if (pr.id == null) {
                    val body = if (pr.reason == "project_ambiguous") mapOf("error" to "project_ambiguous", "choices" to pr.choices) else mapOf("error" to "project_required")
                    return@post call.respond(if (pr.reason == "project_ambiguous") HttpStatusCode.Conflict else HttpStatusCode.BadRequest, body)
                }
                val projectId = pr.id
                call.response.headers.append("X-Resolved-Project-Id", projectId.toString())
                call.response.headers.append("X-Reason", pr.reason)

                val projectRow = transaction {
                    Projects
                        .slice(Projects.id, Projects.lat, Projects.lng)
                        .select { Projects.id eq projectId }
                        .singleOrNull()
                } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "project_not_found"))

                if (!isMember(userId, projectId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden_not_member"))
                }

                val projLat = projectRow[Projects.lat]
                val projLng = projectRow[Projects.lng]
                if (projLat == null || projLng == null) {
                    return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "project_location_missing"))
                }

                val nowZdt = ZonedDateTime.now(zone)
                val fireAtZdt = nowZdt.plusSeconds(15)

                fun chooseSlotByTime(h: Int): Short = when (h) {
                    in 9..11 -> 1
                    in 13..16 -> 2
                    else -> 1
                }.toShort()

                val slotParam = call.request.queryParameters["slot"]?.toIntOrNull()
                val chosenSlot: Short = (slotParam ?: chooseSlotByTime(nowZdt.hour)).toShort()
                val result: TestCreatedDto = transaction {
                    val today = nowZdt.toLocalDate()
                    val existing = Proofs.select {
                        (Proofs.userId eq userId) and
                        (Proofs.projectId eq projectId) and
                        (Proofs.date eq today) and
                        (Proofs.slot eq chosenSlot)
                    }.singleOrNull()

                    val idVal = if (existing != null) {
                        Proofs.update({ Proofs.id eq existing[Proofs.id] }) {
                            it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                            it[Proofs.responded] = false
                        }
                        existing[Proofs.id]
                    } else {
                        (Proofs.insert {
                            it[Proofs.userId] = userId
                            it[Proofs.projectId] = projectId
                            it[Proofs.latitude] = projLat
                            it[Proofs.longitude] = projLng
                            it[Proofs.radius] = 150
                            it[Proofs.date] = nowZdt.toLocalDate()
                            it[Proofs.slot] = chosenSlot
                            it[Proofs.sentAt] = fireAtZdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                            it[Proofs.responded] = false
                        } get Proofs.id)
                    }
                    TestCreatedDto(id = idVal, slot = chosenSlot.toInt(), sentAt = fireAtZdt.toInstant().toString(), mode = "adhoc")
                }

                call.respond(HttpStatusCode.OK, result)
            }
        }
    }
}

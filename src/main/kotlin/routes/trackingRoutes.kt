package com.yourcompany.zeiterfassung.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.exec
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
data class UserContext(
    val userId: Long,
    val companyId: Int,
    val isAdmin: Boolean
)

private fun ApplicationCall.requireUserId(): Long {
    val p = principal<JWTPrincipal>() ?: throw IllegalStateException("Missing JWT principal")
    val idStr = p.payload.getClaim("id").asString() ?: throw IllegalStateException("Missing claim: id")
    return idStr.toLongOrNull() ?: throw IllegalStateException("Invalid claim id: $idStr")
}

private fun loadUserContext(userId: Long): UserContext = transaction {
    exec(
        """
        SELECT 
          id,
          company_id,
          COALESCE(is_global_admin, false) OR COALESCE(is_company_admin, false) AS is_admin
        FROM users
        WHERE id = ?
        """.trimIndent(),
        listOf(userId)
    ) { rs ->
        if (!rs.next()) error("User not found: $userId")
        UserContext(
            userId = rs.getLong("id"),
            companyId = rs.getInt("company_id"),
            isAdmin = rs.getBoolean("is_admin")
        )
    }!!
}

private fun requireAdmin(ctx: UserContext) {
    if (!ctx.isAdmin) throw ForbiddenException("Admin only")
}

private fun requireSameCompany(sessionId: UUID, companyId: Int) = transaction {
    exec(
        "SELECT company_id FROM tracking_sessions WHERE id = ?",
        listOf(sessionId)
    ) { rs ->
        if (!rs.next()) throw NotFoundException("Session not found")
        val sessCompany = rs.getInt("company_id")
        if (sessCompany != companyId) throw ForbiddenException("Wrong company")
        true
    }
}!!

private fun requireOwnedActiveSession(sessionId: UUID, userId: Long): Unit = transaction {
    exec(
        """
        SELECT user_id, is_active
        FROM tracking_sessions
        WHERE id = ?
        """.trimIndent(),
        listOf(sessionId)
    ) { rs ->
        if (!rs.next()) throw NotFoundException("Session not found")
        val owner = rs.getLong("user_id")
        val active = rs.getBoolean("is_active")
        if (owner != userId) throw ForbiddenException("Not your session")
        if (!active) throw ConflictException("Session is not active")
        Unit
    }
}

private fun insertAdminWatchLog(adminId: Long, sessionId: UUID): Long = transaction {
    exec(
        """
        INSERT INTO admin_watch_logs (company_id, admin_user_id, session_id, opened_at)
        VALUES (0, ?, ?, now())
        RETURNING id
        """.trimIndent(),
        listOf(adminId, sessionId)
    ) { rs ->
        rs.next()
        rs.getLong("id")
    }!!
}

private fun closeAdminWatchLog(logId: Long) = transaction {
    exec(
        "UPDATE admin_watch_logs SET closed_at = now() WHERE id = ? AND closed_at IS NULL",
        listOf(logId)
    )
}

private object TrackingHub {
    // sessionId -> connected admin websockets
    private val subs = ConcurrentHashMap<UUID, MutableSet<DefaultWebSocketServerSession>>()

    fun subscribe(sessionId: UUID, ws: DefaultWebSocketServerSession) {
        val set = subs.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        set.add(ws)
    }

    fun unsubscribe(sessionId: UUID, ws: DefaultWebSocketServerSession) {
        subs[sessionId]?.remove(ws)
        if (subs[sessionId]?.isEmpty() == true) subs.remove(sessionId)
    }

    suspend fun broadcast(sessionId: UUID, msg: String) {
        val set = subs[sessionId]?.toList() ?: return
        for (ws in set) {
            try {
                ws.send(Frame.Text(msg))
            } catch (_: Throwable) {
                // ignore; connection will be cleaned up on next unsubscribe
            }
        }
    }
}

@Serializable
data class StartSessionResp(
    val sessionId: String,
    val startedAt: String
)

@Serializable
data class StopSessionResp(
    val sessionId: String,
    val endedAt: String
)

@Serializable
data class TrackPointReq(
    val sessionId: String,
    val lat: Double,
    val lon: Double,
    val speedMps: Float? = null,
    val headingDeg: Float? = null,
    val tsEpochSeconds: Long? = null
)

@Serializable
data class PointEvent(
    val type: String = "point",
    val sessionId: String,
    val userId: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float? = null,
    val headingDeg: Float? = null,
    val tsEpochSeconds: Long
)

@Serializable
data class StoppedEvent(
    val type: String = "stopped",
    val sessionId: String,
    val userId: Long,
    val tsEpochSeconds: Long
)

@Serializable
data class ActiveSessionDto(
    val sessionId: String,
    val userId: Long,
    val startedAt: String
)

fun Route.trackingRoutes() {
    route("/tracking") {

        /**
         * USER: start tracking (one active session per user enforced by DB)
         */
        post("/sessions/start") {
            val userId = call.requireUserId()
            val ctx = loadUserContext(userId)

            val existing = transaction {
                exec(
                    """
                    SELECT id, started_at
                    FROM tracking_sessions
                    WHERE user_id = ? AND is_active = TRUE
                    ORDER BY started_at DESC
                    LIMIT 1
                    """.trimIndent(),
                    listOf(userId)
                ) { rs ->
                    if (!rs.next()) return@exec null
                    Pair(rs.getObject("id", UUID::class.java), rs.getString("started_at"))
                }
            }

            if (existing != null) {
                call.respond(StartSessionResp(existing.first.toString(), existing.second))
                return@post
            }

            val created = transaction {
                exec(
                    """
                    INSERT INTO tracking_sessions (company_id, user_id, started_at, is_active)
                    VALUES (?, ?, now(), TRUE)
                    RETURNING id, started_at
                    """.trimIndent(),
                    listOf(ctx.companyId, userId)
                ) { rs ->
                    rs.next()
                    Pair(rs.getObject("id", UUID::class.java), rs.getString("started_at"))
                }!!
            }

            call.respond(StartSessionResp(created.first.toString(), created.second))
        }

        /**
         * USER: stop tracking
         */
        post("/sessions/{id}/stop") {
            val userId = call.requireUserId()
            val sessionId = UUID.fromString(call.parameters["id"] ?: throw BadRequestException("Missing id"))

            // must own & active
            requireOwnedActiveSession(sessionId, userId)

            val endedAt = transaction {
                exec(
                    """
                    UPDATE tracking_sessions
                    SET is_active = FALSE, ended_at = now()
                    WHERE id = ? AND user_id = ? AND is_active = TRUE
                    RETURNING ended_at
                    """.trimIndent(),
                    listOf(sessionId, userId)
                ) { rs ->
                    rs.next()
                    rs.getString("ended_at")
                }!!
            }

            val evt = StoppedEvent(
                sessionId = sessionId.toString(),
                userId = userId,
                tsEpochSeconds = Instant.now().epochSecond
            )
            TrackingHub.broadcast(sessionId, json.encodeToString(evt))

            call.respond(StopSessionResp(sessionId.toString(), endedAt))
        }

        /**
         * USER: send point
         */
        post("/points") {
            val userId = call.requireUserId()
            val req = call.receive<TrackPointReq>()
            val sessionId = UUID.fromString(req.sessionId)

            // must own & active
            requireOwnedActiveSession(sessionId, userId)

            val ts = req.tsEpochSeconds ?: Instant.now().epochSecond
            val tsIso = Instant.ofEpochSecond(ts).toString()

            transaction {
                exec(
                    """
                    INSERT INTO tracking_points (session_id, latitude, longitude, speed_mps, heading_deg, ts)
                    VALUES (?, ?, ?, ?, ?, ?::timestamptz)
                    """.trimIndent(),
                    listOf(
                        sessionId,
                        req.lat,
                        req.lon,
                        req.speedMps,
                        req.headingDeg,
                        tsIso
                    )
                )
            }

            val evt = PointEvent(
                sessionId = sessionId.toString(),
                userId = userId,
                lat = req.lat,
                lon = req.lon,
                speedMps = req.speedMps,
                headingDeg = req.headingDeg,
                tsEpochSeconds = ts
            )
            TrackingHub.broadcast(sessionId, json.encodeToString(evt))

            call.respond(HttpStatusCode.OK)
        }

        /**
         * ADMIN: list active sessions in same company
         */
        get("/sessions/active") {
            val userId = call.requireUserId()
            val ctx = loadUserContext(userId)
            requireAdmin(ctx)

            val rows = transaction {
                exec(
                    """
                    SELECT id, user_id, started_at
                    FROM tracking_sessions
                    WHERE company_id = ? AND is_active = TRUE
                    ORDER BY started_at DESC
                    """.trimIndent(),
                    listOf(ctx.companyId)
                ) { rs ->
                    val out = mutableListOf<ActiveSessionDto>()
                    while (rs.next()) {
                        out.add(
                            ActiveSessionDto(
                                sessionId = rs.getObject("id", UUID::class.java).toString(),
                                userId = rs.getLong("user_id"),
                                startedAt = rs.getString("started_at")
                            )
                        )
                    }
                    out
                }!!
            }

            call.respond(rows)
        }
    }

    /**
     * ADMIN: WebSocket stream for a specific sessionId
     * GET /ws/admin-tracking?sessionId=...
     */
    webSocket("/ws/admin-tracking") {
        val sessionIdStr = call.request.queryParameters["sessionId"]
            ?: throw BadRequestException("Missing sessionId")
        val sessionId = UUID.fromString(sessionIdStr)

        val adminId = call.requireUserId()
        val ctx = loadUserContext(adminId)
        requireAdmin(ctx)

        // enforce same company
        requireSameCompany(sessionId, ctx.companyId)

        val logId = insertAdminWatchLog(adminId, sessionId)
        TrackingHub.subscribe(sessionId, this)

        try {
            // keep WS open; we don't require client messages, but we'll consume to detect close
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            TrackingHub.unsubscribe(sessionId, this)
            closeAdminWatchLog(logId)
        }
    }
}
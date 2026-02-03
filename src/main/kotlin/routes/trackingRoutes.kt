package com.yourcompany.zeiterfassung.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

// --- Simple HTTP exceptions (map via StatusPages in Application.module) -----
class HttpStatusException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

private fun forbidden(msg: String = "Forbidden"): Nothing = throw HttpStatusException(HttpStatusCode.Forbidden, msg)
private fun notFound(msg: String = "Not found"): Nothing = throw HttpStatusException(HttpStatusCode.NotFound, msg)
private fun conflict(msg: String = "Conflict"): Nothing = throw HttpStatusException(HttpStatusCode.Conflict, msg)

private val NULL_TYPE = TextColumnType()

// --- Minimal JDBC helpers inside Exposed transaction ------------------------
private inline fun <T> Transaction.queryOne(
    sql: String,
    params: List<Any?>,
    crossinline mapper: (ResultSet) -> T
): T? {
    val stmt: PreparedStatementApi = connection.prepareStatement(sql, false)
    try {
        params.forEachIndexed { idx, v ->
            if (v == null) stmt.setNull(idx + 1, NULL_TYPE) else stmt.set(idx + 1, v)
        }
        val rs = stmt.executeQuery()
        rs.use {
            return if (it.next()) mapper(it) else null
        }
    } finally {
        stmt.closeIfPossible()
    }
}

private fun Transaction.execUpdate(sql: String, params: List<Any?>) {
    val stmt: PreparedStatementApi = connection.prepareStatement(sql, false)
    try {
        params.forEachIndexed { idx, v ->
            if (v == null) stmt.setNull(idx + 1, NULL_TYPE) else stmt.set(idx + 1, v)
        }
        stmt.executeUpdate()
    } finally {
        stmt.closeIfPossible()
    }
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
    queryOne(
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
        UserContext(
            userId = rs.getLong("id"),
            companyId = rs.getInt("company_id"),
            isAdmin = rs.getBoolean("is_admin")
        )
    } ?: error("User not found: $userId")
}

private fun requireAdmin(ctx: UserContext) {
    if (!ctx.isAdmin) forbidden("Admin only")
}

private fun requireSameCompany(sessionId: UUID, companyId: Int) = transaction {
    val sessCompany = queryOne(
        "SELECT company_id FROM tracking_sessions WHERE id = ?",
        listOf(sessionId)
    ) { rs -> rs.getInt("company_id") } ?: notFound("Session not found")

    if (sessCompany != companyId) forbidden("Wrong company")
    true
}

private fun requireOwnedActiveSession(sessionId: UUID, userId: Long): Unit = transaction {
    val row = queryOne(
        """
        SELECT user_id, is_active
        FROM tracking_sessions
        WHERE id = ?
        """.trimIndent(),
        listOf(sessionId)
    ) { rs -> Pair(rs.getLong("user_id"), rs.getBoolean("is_active")) } ?: notFound("Session not found")

    val owner = row.first
    val active = row.second

    if (owner != userId) forbidden("Not your session")
    if (!active) conflict("Session is not active")
}

private fun insertAdminWatchLog(adminId: Long, sessionId: UUID): Long = transaction {
    queryOne(
        """
        INSERT INTO admin_watch_logs (company_id, admin_user_id, session_id, opened_at)
        VALUES (0, ?, ?, now())
        RETURNING id
        """.trimIndent(),
        listOf(adminId, sessionId)
    ) { rs -> rs.getLong("id") }!!
}

private fun closeAdminWatchLog(logId: Long) = transaction {
    execUpdate(
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

        // USER: start tracking (one active session per user enforced by DB)
        post("/sessions/start") {
            val userId = call.requireUserId()
            val ctx = loadUserContext(userId)

            val existing = transaction {
                queryOne(
                    """
                    SELECT id, started_at
                    FROM tracking_sessions
                    WHERE user_id = ? AND is_active = TRUE
                    ORDER BY started_at DESC
                    LIMIT 1
                    """.trimIndent(),
                    listOf(userId)
                ) { rs ->
                    Pair(rs.getObject("id", UUID::class.java), rs.getString("started_at"))
                }
            }

            if (existing != null) {
                call.respond(StartSessionResp(existing.first.toString(), existing.second))
                return@post
            }

            val created = transaction {
                queryOne(
                    """
                    INSERT INTO tracking_sessions (company_id, user_id, started_at, is_active)
                    VALUES (?, ?, now(), TRUE)
                    RETURNING id, started_at
                    """.trimIndent(),
                    listOf(ctx.companyId, userId)
                ) { rs ->
                    Pair(rs.getObject("id", UUID::class.java), rs.getString("started_at"))
                }!!
            }

            call.respond(StartSessionResp(created.first.toString(), created.second))
        }

        // USER: stop tracking
        post("/sessions/{id}/stop") {
            val userId = call.requireUserId()
            val sessionId = UUID.fromString(call.parameters["id"] ?: throw BadRequestException("Missing id"))

            requireOwnedActiveSession(sessionId, userId)

            val endedAt = transaction {
                queryOne(
                    """
                    UPDATE tracking_sessions
                    SET is_active = FALSE, ended_at = now()
                    WHERE id = ? AND user_id = ? AND is_active = TRUE
                    RETURNING ended_at
                    """.trimIndent(),
                    listOf(sessionId, userId)
                ) { rs -> rs.getString("ended_at") } ?: conflict("Session already stopped")
            }

            val evt = StoppedEvent(
                sessionId = sessionId.toString(),
                userId = userId,
                tsEpochSeconds = Instant.now().epochSecond
            )
            TrackingHub.broadcast(sessionId, json.encodeToString(evt))

            call.respond(StopSessionResp(sessionId.toString(), endedAt))
        }

        // USER: send point
        post("/points") {
            val userId = call.requireUserId()
            val req = call.receive<TrackPointReq>()
            val sessionId = UUID.fromString(req.sessionId)

            requireOwnedActiveSession(sessionId, userId)

            val ts = req.tsEpochSeconds ?: Instant.now().epochSecond
            val tsIso = Instant.ofEpochSecond(ts).toString()

            transaction {
                execUpdate(
                    """
                    INSERT INTO tracking_points (session_id, latitude, longitude, speed_mps, heading_deg, ts)
                    VALUES (?, ?, ?, ?::real, ?::real, ?::timestamptz)
                    """.trimIndent(),
                    listOf(sessionId, req.lat, req.lon, req.speedMps, req.headingDeg, tsIso)
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

        // ADMIN: list active sessions in same company
        get("/sessions/active") {
            val userId = call.requireUserId()
            val ctx = loadUserContext(userId)
            requireAdmin(ctx)

            val rows = transaction {
                val stmt = connection.prepareStatement(
                    """
                    SELECT id, user_id, started_at
                    FROM tracking_sessions
                    WHERE company_id = ? AND is_active = TRUE
                    ORDER BY started_at DESC
                    """.trimIndent(),
                    false
                )
                try {
                    stmt.set(1, ctx.companyId)
                    val rs = stmt.executeQuery()
                    rs.use {
                        val out = mutableListOf<ActiveSessionDto>()
                        while (it.next()) {
                            out.add(
                                ActiveSessionDto(
                                    sessionId = it.getObject("id", UUID::class.java).toString(),
                                    userId = it.getLong("user_id"),
                                    startedAt = it.getString("started_at")
                                )
                            )
                        }
                        out
                    }
                } finally {
                    stmt.closeIfPossible()
                }
            }

            call.respond(rows)
        }
    }

    // ADMIN: WebSocket stream for a specific sessionId
    webSocket("/ws/admin-tracking") {
        val sessionIdStr = call.request.queryParameters["sessionId"]
            ?: throw BadRequestException("Missing sessionId")
        val sessionId = UUID.fromString(sessionIdStr)

        val adminId = call.requireUserId()
        val ctx = loadUserContext(adminId)
        requireAdmin(ctx)

        requireSameCompany(sessionId, ctx.companyId)

        val logId = insertAdminWatchLog(adminId, sessionId)
        TrackingHub.subscribe(sessionId, this)

        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            TrackingHub.unsubscribe(sessionId, this)
            closeAdminWatchLog(logId)
        }
    }
}
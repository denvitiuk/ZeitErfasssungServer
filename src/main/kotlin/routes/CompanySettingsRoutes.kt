
package com.yourcompany.zeiterfassung.routes

import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.ZoneId
import javax.sql.DataSource

@Serializable
data class EasyWorkerFlowResponse(
    val companyId: Int,
    val simplifiedWorkerFlowEnabled: Boolean
)

@Serializable
data class UpdateEasyWorkerFlowRequest(
    val simplifiedWorkerFlowEnabled: Boolean
)

@Serializable
data class CompanyPauseSettingsResponse(
    val companyId: Int,
    val pauseStartTime: String,
    val pauseEndTime: String,
    val pauseDurationMinutes: Int,
    val timezone: String = DEFAULT_PAUSE_TIMEZONE
)

@Serializable
data class UpdateCompanyPauseSettingsRequest(
    val pauseStartTime: String? = null,
    val pauseEndTime: String? = null,
    val pauseDurationMinutes: Int? = null,
    val timezone: String? = null
)

@Serializable
data class CompanySettingsError(
    val error: String
)

private const val DEFAULT_PAUSE_START_TIME = "12:00"
private const val DEFAULT_PAUSE_END_TIME = "13:00"
private const val DEFAULT_PAUSE_DURATION_MINUTES = 60
private const val MAX_PAUSE_DURATION_MINUTES = 240
private const val DEFAULT_PAUSE_TIMEZONE = "Europe/Berlin"

fun Route.companySettingsRoutes(dataSource: DataSource) {
    route("/admin/company/easy-worker-flow") {
        get {
            val adminUserId = call.currentUserIdOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    CompanySettingsError("unauthorized")
                )

            dataSource.connection.use { connection ->
                val admin = connection.prepareStatement(
                    """
                    SELECT id, company_id, is_company_admin, is_global_admin
                    FROM users
                    WHERE id = ?
                      AND is_active = true
                      AND deleted_at IS NULL
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, adminUserId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@use null

                        AdminCompanyAccess(
                            companyId = rs.getInt("company_id"),
                            isCompanyAdmin = rs.getBoolean("is_company_admin"),
                            isGlobalAdmin = rs.getBoolean("is_global_admin")
                        )
                    }
                } ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    CompanySettingsError("unauthorized")
                )

                if (!admin.isCompanyAdmin && !admin.isGlobalAdmin) {
                    return@get call.respond(
                        HttpStatusCode.Forbidden,
                        CompanySettingsError("company_admin_required")
                    )
                }

                val enabled = connection.prepareStatement(
                    """
                    SELECT COALESCE(simplified_worker_flow_enabled, false) AS simplified_worker_flow_enabled
                    FROM company_join_settings
                    WHERE company_id = ?
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, admin.companyId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getBoolean("simplified_worker_flow_enabled") else false
                    }
                }

                call.respond(
                    EasyWorkerFlowResponse(
                        companyId = admin.companyId,
                        simplifiedWorkerFlowEnabled = enabled
                    )
                )
            }
        }

        patch {
            val adminUserId = call.currentUserIdOrNull()
                ?: return@patch call.respond(
                    HttpStatusCode.Unauthorized,
                    CompanySettingsError("unauthorized")
                )

            val request = call.receive<UpdateEasyWorkerFlowRequest>()

            dataSource.connection.use { connection ->
                val admin = connection.prepareStatement(
                    """
                    SELECT id, company_id, is_company_admin, is_global_admin
                    FROM users
                    WHERE id = ?
                      AND is_active = true
                      AND deleted_at IS NULL
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, adminUserId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@use null

                        AdminCompanyAccess(
                            companyId = rs.getInt("company_id"),
                            isCompanyAdmin = rs.getBoolean("is_company_admin"),
                            isGlobalAdmin = rs.getBoolean("is_global_admin")
                        )
                    }
                } ?: return@patch call.respond(
                    HttpStatusCode.Unauthorized,
                    CompanySettingsError("unauthorized")
                )

                if (!admin.isCompanyAdmin && !admin.isGlobalAdmin) {
                    return@patch call.respond(
                        HttpStatusCode.Forbidden,
                        CompanySettingsError("company_admin_required")
                    )
                }

                connection.prepareStatement(
                    """
                    INSERT INTO company_join_settings (
                        company_id,
                        simple_worker_join_enabled,
                        requires_admin_approval,
                        auto_approve_expected_employees,
                        company_search_enabled,
                        simplified_worker_flow_enabled,
                        created_at,
                        updated_at
                    )
                    VALUES (?, false, false, false, false, ?, now(), now())
                    ON CONFLICT (company_id)
                    DO UPDATE SET
                        simplified_worker_flow_enabled = EXCLUDED.simplified_worker_flow_enabled,
                        updated_at = now()
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, admin.companyId)
                    statement.setBoolean(2, request.simplifiedWorkerFlowEnabled)
                    statement.executeUpdate()
                }

                call.respond(
                    EasyWorkerFlowResponse(
                        companyId = admin.companyId,
                        simplifiedWorkerFlowEnabled = request.simplifiedWorkerFlowEnabled
                    )
                )
            }
        }
    }

    route("/admin/pause/settings") {
        get {
            val adminUserId = call.currentUserIdOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    CompanySettingsError("unauthorized")
                )

            dataSource.connection.use { connection ->
                val admin = loadAdminCompanyAccess(connection, adminUserId)
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        CompanySettingsError("unauthorized")
                    )

                if (!admin.isCompanyAdmin && !admin.isGlobalAdmin) {
                    return@get call.respond(
                        HttpStatusCode.Forbidden,
                        CompanySettingsError("company_admin_required")
                    )
                }

                val settings = connection.prepareStatement(
                    """
                    SELECT
                        COALESCE(pause_start_time::text, ?) AS pause_start_time,
                        COALESCE(pause_end_time::text, ?) AS pause_end_time,
                        COALESCE(pause_duration_minutes, ?) AS pause_duration_minutes,
                        COALESCE(timezone, ?) AS timezone
                    FROM company_pause_settings
                    WHERE company_id = ?
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, DEFAULT_PAUSE_START_TIME)
                    statement.setString(2, DEFAULT_PAUSE_END_TIME)
                    statement.setInt(3, DEFAULT_PAUSE_DURATION_MINUTES)
                    statement.setString(4, DEFAULT_PAUSE_TIMEZONE)
                    statement.setInt(5, admin.companyId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            CompanyPauseSettingsResponse(
                                companyId = admin.companyId,
                                pauseStartTime = normalizePauseTimeForResponse(
                                    value = rs.getString("pause_start_time"),
                                    fallback = DEFAULT_PAUSE_START_TIME
                                ),
                                pauseEndTime = normalizePauseTimeForResponse(
                                    value = rs.getString("pause_end_time"),
                                    fallback = DEFAULT_PAUSE_END_TIME
                                ),
                                pauseDurationMinutes = rs.getInt("pause_duration_minutes").takeIf { !rs.wasNull() }
                                    ?: DEFAULT_PAUSE_DURATION_MINUTES,
                                timezone = rs.getString("timezone") ?: DEFAULT_PAUSE_TIMEZONE
                            )
                        } else {
                            CompanyPauseSettingsResponse(
                                companyId = admin.companyId,
                                pauseStartTime = DEFAULT_PAUSE_START_TIME,
                                pauseEndTime = DEFAULT_PAUSE_END_TIME,
                                pauseDurationMinutes = DEFAULT_PAUSE_DURATION_MINUTES,
                                timezone = DEFAULT_PAUSE_TIMEZONE
                            )
                        }
                    }
                }

                call.respond(settings)
            }
        }

        patch {
            val adminUserId = call.currentUserIdOrNull()
                ?: return@patch call.respond(
                    HttpStatusCode.Unauthorized,
                    CompanySettingsError("unauthorized")
                )

            val request = call.receive<UpdateCompanyPauseSettingsRequest>()

            dataSource.connection.use { connection ->
                val admin = loadAdminCompanyAccess(connection, adminUserId)
                    ?: return@patch call.respond(
                        HttpStatusCode.Unauthorized,
                        CompanySettingsError("unauthorized")
                    )

                if (!admin.isCompanyAdmin && !admin.isGlobalAdmin) {
                    return@patch call.respond(
                        HttpStatusCode.Forbidden,
                        CompanySettingsError("company_admin_required")
                    )
                }

                val current = connection.prepareStatement(
                    """
                    SELECT
                        COALESCE(pause_start_time::text, ?) AS pause_start_time,
                        COALESCE(pause_end_time::text, ?) AS pause_end_time,
                        COALESCE(pause_duration_minutes, ?) AS pause_duration_minutes,
                        COALESCE(timezone, ?) AS timezone
                    FROM company_pause_settings
                    WHERE company_id = ?
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, DEFAULT_PAUSE_START_TIME)
                    statement.setString(2, DEFAULT_PAUSE_END_TIME)
                    statement.setInt(3, DEFAULT_PAUSE_DURATION_MINUTES)
                    statement.setString(4, DEFAULT_PAUSE_TIMEZONE)
                    statement.setInt(5, admin.companyId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            CompanyPauseSettingsResponse(
                                companyId = admin.companyId,
                                pauseStartTime = normalizePauseTimeForResponse(
                                    value = rs.getString("pause_start_time"),
                                    fallback = DEFAULT_PAUSE_START_TIME
                                ),
                                pauseEndTime = normalizePauseTimeForResponse(
                                    value = rs.getString("pause_end_time"),
                                    fallback = DEFAULT_PAUSE_END_TIME
                                ),
                                pauseDurationMinutes = rs.getInt("pause_duration_minutes").takeIf { !rs.wasNull() }
                                    ?: DEFAULT_PAUSE_DURATION_MINUTES,
                                timezone = rs.getString("timezone") ?: DEFAULT_PAUSE_TIMEZONE
                            )
                        } else {
                            CompanyPauseSettingsResponse(
                                companyId = admin.companyId,
                                pauseStartTime = DEFAULT_PAUSE_START_TIME,
                                pauseEndTime = DEFAULT_PAUSE_END_TIME,
                                pauseDurationMinutes = DEFAULT_PAUSE_DURATION_MINUTES,
                                timezone = DEFAULT_PAUSE_TIMEZONE
                            )
                        }
                    }
                }

                val normalizedPauseStartTime = request.pauseStartTime?.trim()?.takeIf { it.isNotBlank() }
                    ?: current.pauseStartTime
                val normalizedPauseEndTime = request.pauseEndTime?.trim()?.takeIf { it.isNotBlank() }
                    ?: current.pauseEndTime
                val normalizedPauseDurationMinutes = request.pauseDurationMinutes
                    ?: current.pauseDurationMinutes

                val normalizedTimezone = request.timezone?.trim()?.takeIf { it.isNotBlank() }
                    ?: current.timezone

                if (!isValidPauseTime(normalizedPauseStartTime)) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        CompanySettingsError("invalid_pause_start_time")
                    )
                }

                if (!isValidPauseTime(normalizedPauseEndTime)) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        CompanySettingsError("invalid_pause_end_time")
                    )
                }

                if (normalizedPauseDurationMinutes !in 1..MAX_PAUSE_DURATION_MINUTES) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        CompanySettingsError("invalid_pause_duration_minutes")
                    )
                }

                if (!isValidTimezone(normalizedTimezone)) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        CompanySettingsError("invalid_pause_timezone")
                    )
                }

                if (normalizedPauseStartTime >= normalizedPauseEndTime) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        CompanySettingsError("invalid_pause_time_range")
                    )
                }

                connection.prepareStatement(
                    """
                    INSERT INTO company_pause_settings (
                        company_id,
                        pause_start_time,
                        pause_end_time,
                        pause_duration_minutes,
                        timezone,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?::time, ?::time, ?, ?, now(), now())
                    ON CONFLICT (company_id)
                    DO UPDATE SET
                        pause_start_time = EXCLUDED.pause_start_time,
                        pause_end_time = EXCLUDED.pause_end_time,
                        pause_duration_minutes = EXCLUDED.pause_duration_minutes,
                        timezone = EXCLUDED.timezone,
                        updated_at = now()
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, admin.companyId)
                    statement.setString(2, normalizedPauseStartTime)
                    statement.setString(3, normalizedPauseEndTime)
                    statement.setInt(4, normalizedPauseDurationMinutes)
                    statement.setString(5, normalizedTimezone)
                    statement.executeUpdate()
                }

                call.respond(
                    CompanyPauseSettingsResponse(
                        companyId = admin.companyId,
                        pauseStartTime = normalizedPauseStartTime,
                        pauseEndTime = normalizedPauseEndTime,
                        pauseDurationMinutes = normalizedPauseDurationMinutes,
                        timezone = normalizedTimezone
                    )
                )
            }
        }
    }
}
private fun loadAdminCompanyAccess(
    connection: java.sql.Connection,
    adminUserId: Int
): AdminCompanyAccess? {
    return connection.prepareStatement(
        """
        SELECT id, company_id, is_company_admin, is_global_admin
        FROM users
        WHERE id = ?
          AND is_active = true
          AND deleted_at IS NULL
        LIMIT 1
        """.trimIndent()
    ).use { statement ->
        statement.setInt(1, adminUserId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) return@use null

            AdminCompanyAccess(
                companyId = rs.getInt("company_id"),
                isCompanyAdmin = rs.getBoolean("is_company_admin"),
                isGlobalAdmin = rs.getBoolean("is_global_admin")
            )
        }
    }
}

private fun normalizePauseTimeForResponse(value: String?, fallback: String): String {
    return value
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(5)
        ?: fallback
}

private fun isValidPauseTime(value: String): Boolean {
    return Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value)
}

private fun isValidTimezone(value: String): Boolean {
    return runCatching { ZoneId.of(value) }.isSuccess
}

private data class AdminCompanyAccess(
    val companyId: Int,
    val isCompanyAdmin: Boolean,
    val isGlobalAdmin: Boolean
)

private fun io.ktor.server.application.ApplicationCall.currentUserIdOrNull(): Int? {
    val principal = principal<JWTPrincipal>() ?: return null
    val payload = principal.payload

    return payload.getClaim("userId").asInt()
        ?: payload.getClaim("userId").asString()?.toIntOrNull()
        ?: payload.getClaim("id").asInt()
        ?: payload.getClaim("id").asString()?.toIntOrNull()
        ?: payload.subject?.toIntOrNull()
}


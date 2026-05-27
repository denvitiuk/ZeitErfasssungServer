package com.yourcompany.zeiterfassung.routes

import com.yourcompany.zeiterfassung.service.EmailService
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom

@Serializable
private data class SendExpectedEmployeeInviteResponse(
    val employeeId: Int,
    val email: String,
    val inviteStatus: String,
    val message: String
)

@Serializable
private data class BulkSendExpectedEmployeeInvitesResponse(
    val sentCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val sentIds: List<Int>,
    val failedIds: List<Int>,
    val skippedIds: List<Int>
)

private data class ExpectedEmployeeInviteTarget(
    val id: Int,
    val firstName: String?,
    val lastName: String?,
    val email: String
)

private data class CompanyInviteContext(
    val companyId: Int,
    val companyName: String,
    val companyCode: String?,
    val joinLinkToken: String,
    val joinUrl: String
)

private val expectedEmployeeInviteRandom = SecureRandom()

private fun expectedEmployeeInviteJwtCompanyId(principal: JWTPrincipal): Int =
    principal.payload.getClaim("companyId").asInt() ?: 0

private fun expectedEmployeeInviteJwtUserId(principal: JWTPrincipal): Int? =
    principal.payload.getClaim("id").asString()?.toIntOrNull()
        ?: principal.payload.getClaim("id").asInt()

private fun expectedEmployeeInviteIsAdminForJwtCompany(principal: JWTPrincipal, companyId: Int): Boolean {
    val isCompanyAdmin = principal.payload.getClaim("isCompanyAdmin").asBoolean() ?: false
    val isGlobalAdmin = principal.payload.getClaim("isGlobalAdmin").asBoolean() ?: false
    val tokenCompanyId = principal.payload.getClaim("companyId").asInt() ?: 0
    return isGlobalAdmin || (isCompanyAdmin && tokenCompanyId == companyId)
}

private fun expectedEmployeeInviteSqlStringOrNull(value: String?): String =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace("'", "''")
        ?.let { "'$it'" }
        ?: "NULL"

private fun generateExpectedEmployeeInviteToken(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val body = buildString {
        repeat(40) {
            append(alphabet[expectedEmployeeInviteRandom.nextInt(alphabet.length)])
        }
    }
    return "jn_$body"
}

private fun buildExpectedEmployeeJoinUrl(protocol: String, host: String, token: String): String =
    "$protocol://$host/join/$token"

private fun buildExpectedEmployeeInviteHtml(
    employee: ExpectedEmployeeInviteTarget,
    context: CompanyInviteContext
): String {
    val displayName = listOfNotNull(employee.firstName, employee.lastName)
        .joinToString(" ")
        .trim()
        .takeIf { it.isNotEmpty() }
        ?: "Hallo"

    val companyCodeBlock = context.companyCode
        ?.takeIf { it.isNotBlank() }
        ?.let {
            """
            <p style="margin:16px 0 0;color:#374151;font-size:15px;line-height:1.6;">
              Falls der Link nicht funktioniert, kannst du diesen Firmen-Code eingeben:
              <strong style="font-size:16px;">$it</strong>
            </p>
            """.trimIndent()
        }
        ?: ""

    return """
        <!doctype html>
        <html lang="de">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>Einladung zur Zeiterfassung</title>
        </head>
        <body style="margin:0;background:#f3f4f6;font-family:Arial,Helvetica,sans-serif;color:#111827;">
          <div style="max-width:640px;margin:0 auto;padding:32px 16px;">
            <div style="background:#ffffff;border-radius:18px;padding:28px;border:1px solid #e5e7eb;">
              <h1 style="margin:0 0 16px;font-size:24px;line-height:1.25;color:#111827;">
                Einladung zur Zeiterfassung
              </h1>

              <p style="margin:0 0 14px;color:#374151;font-size:15px;line-height:1.6;">
                $displayName,
              </p>

              <p style="margin:0 0 18px;color:#374151;font-size:15px;line-height:1.6;">
                du wurdest eingeladen, der Firma <strong>${context.companyName}</strong> in der Zeiterfassung-App beizutreten.
              </p>

              <p style="margin:0 0 22px;color:#374151;font-size:15px;line-height:1.6;">
                Öffne den folgenden Link, um deine Anfrage zu senden:
              </p>

              <p style="margin:0 0 22px;">
                <a href="${context.joinUrl}"
                   style="display:inline-block;background:#111827;color:#ffffff;text-decoration:none;padding:12px 18px;border-radius:12px;font-weight:700;font-size:15px;">
                  Beitreten
                </a>
              </p>

              <p style="margin:0;color:#6b7280;font-size:13px;line-height:1.6;word-break:break-all;">
                ${context.joinUrl}
              </p>

              $companyCodeBlock

              <p style="margin:22px 0 0;color:#6b7280;font-size:13px;line-height:1.6;">
                Nach deiner Anfrage kann ein Admin deinen Zugang bestätigen.
              </p>
            </div>
          </div>
        </body>
        </html>
    """.trimIndent()
}

private fun Transaction.loadOrCreateCompanyInviteContext(
    companyId: Int,
    adminUserId: Int?,
    protocol: String,
    host: String
): CompanyInviteContext {
    var companyName: String? = null
    var companyCode: String? = null
    var joinLinkToken: String? = null

    exec(
        """
        SELECT name, invite_code
        FROM companies
        WHERE id = $companyId
        LIMIT 1
        """.trimIndent()
    ) { rs ->
        if (rs.next()) {
            companyName = rs.getString("name")
            companyCode = rs.getString("invite_code")
        }
    }

    if (companyName == null) error("company_not_found")

    exec(
        """
        SELECT token
        FROM join_links
        WHERE company_id = $companyId
          AND type = 'company_join'
          AND is_active = true
          AND (expires_at IS NULL OR expires_at > now())
          AND (max_uses IS NULL OR used_count < max_uses)
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """.trimIndent()
    ) { rs ->
        if (rs.next()) {
            joinLinkToken = rs.getString("token")
        }
    }

    if (joinLinkToken == null) {
        var token = generateExpectedEmployeeInviteToken()
        var attempts = 0

        while (attempts < 5) {
            try {
                exec(
                    """
                    INSERT INTO join_links (
                        company_id,
                        project_id,
                        token,
                        type,
                        is_active,
                        requires_approval,
                        default_role,
                        max_uses,
                        used_count,
                        expires_at,
                        created_by_user_id,
                        created_at,
                        updated_at
                    )
                    VALUES (
                        $companyId,
                        NULL,
                        '$token',
                        'company_join',
                        true,
                        true,
                        'worker',
                        NULL,
                        0,
                        NULL,
                        ${adminUserId ?: "NULL"},
                        now(),
                        now()
                    )
                    """.trimIndent()
                )
                joinLinkToken = token
                break
            } catch (e: Exception) {
                attempts += 1
                if (attempts >= 5) throw e
                token = generateExpectedEmployeeInviteToken()
            }
        }
    }

    val finalToken = joinLinkToken ?: error("join_link_not_created")
    return CompanyInviteContext(
        companyId = companyId,
        companyName = companyName ?: error("company_not_found"),
        companyCode = companyCode,
        joinLinkToken = finalToken,
        joinUrl = buildExpectedEmployeeJoinUrl(protocol, host, finalToken)
    )
}

fun Route.expectedEmployeeInviteRoutes(env: Dotenv) {
    route("/companies/self/expected-employees") {
        post("/{id}/send-invite") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = expectedEmployeeInviteJwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!expectedEmployeeInviteIsAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val employeeId = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("id_must_be_positive_int", "Employee id must be a positive integer"))
            if (employeeId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("id_must_be_positive_int", "Employee id must be a positive integer"))
            }

            val adminUserId = expectedEmployeeInviteJwtUserId(principal)
            val protocol = call.request.headers["X-Forwarded-Proto"] ?: "http"
            val host = call.request.headers["X-Forwarded-Host"]
                ?: call.request.headers["Host"]
                ?: "localhost:8080"

            try {
                val employee = transaction {
                    var target: ExpectedEmployeeInviteTarget? = null
                    exec(
                        """
                        SELECT id, first_name, last_name, email
                        FROM expected_employees
                        WHERE id = $employeeId
                          AND company_id = $companyId
                        LIMIT 1
                        """.trimIndent()
                    ) { rs ->
                        if (rs.next()) {
                            val email = rs.getString("email")?.trim().orEmpty()
                            if (email.isNotEmpty()) {
                                target = ExpectedEmployeeInviteTarget(
                                    id = rs.getInt("id"),
                                    firstName = rs.getString("first_name"),
                                    lastName = rs.getString("last_name"),
                                    email = email
                                )
                            }
                        }
                    }
                    target
                }

                if (employee == null) {
                    return@post call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("expected_employee_not_found_or_no_email", "Expected employee was not found or has no email")
                    )
                }

                val context = transaction {
                    loadOrCreateCompanyInviteContext(companyId, adminUserId, protocol, host)
                }
                val html = buildExpectedEmployeeInviteHtml(employee, context)

                try {
                    EmailService.send(
                        to = employee.email,
                        subject = "Einladung zur Zeiterfassung - ${context.companyName}",
                        body = html,
                        env = env
                    )

                    transaction {
                        exec(
                            """
                            UPDATE expected_employees
                            SET invite_status = 'sent',
                                invited_at = now(),
                                updated_at = now()
                            WHERE id = ${employee.id}
                              AND company_id = $companyId
                            """.trimIndent()
                        )
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        SendExpectedEmployeeInviteResponse(
                            employeeId = employee.id,
                            email = employee.email,
                            inviteStatus = "sent",
                            message = "invite_sent"
                        )
                    )
                } catch (e: Exception) {
                    transaction {
                        exec(
                            """
                            UPDATE expected_employees
                            SET invite_status = 'failed',
                                updated_at = now()
                            WHERE id = ${employee.id}
                              AND company_id = $companyId
                            """.trimIndent()
                        )
                    }

                    call.application.log.error("Failed to send expected employee invite to ${employee.email}", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "email_delivery_failed", "message" to (e.message ?: e::class.simpleName.orEmpty()))
                    )
                }
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                when {
                    message.contains("company_not_found") -> call.respond(HttpStatusCode.NotFound, ApiError("company_not_found", "Company was not found"))
                    else -> {
                        call.application.log.error("Failed to send invite for expected employee $employeeId", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
                    }
                }
            }
        }

        post("/send-invites") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = expectedEmployeeInviteJwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!expectedEmployeeInviteIsAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val adminUserId = expectedEmployeeInviteJwtUserId(principal)
            val protocol = call.request.headers["X-Forwarded-Proto"] ?: "http"
            val host = call.request.headers["X-Forwarded-Host"]
                ?: call.request.headers["Host"]
                ?: "localhost:8080"

            try {
                val employees = transaction {
                    val rows = mutableListOf<ExpectedEmployeeInviteTarget>()
                    exec(
                        """
                        SELECT id, first_name, last_name, email
                        FROM expected_employees
                        WHERE company_id = $companyId
                          AND email IS NOT NULL
                          AND trim(email) <> ''
                          AND status IN ('expected', 'invited')
                        ORDER BY id ASC
                        LIMIT 200
                        """.trimIndent()
                    ) { rs ->
                        while (rs.next()) {
                            rows.add(
                                ExpectedEmployeeInviteTarget(
                                    id = rs.getInt("id"),
                                    firstName = rs.getString("first_name"),
                                    lastName = rs.getString("last_name"),
                                    email = rs.getString("email").trim()
                                )
                            )
                        }
                    }
                    rows
                }

                if (employees.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.OK,
                        BulkSendExpectedEmployeeInvitesResponse(
                            sentCount = 0,
                            failedCount = 0,
                            skippedCount = 0,
                            sentIds = emptyList(),
                            failedIds = emptyList(),
                            skippedIds = emptyList()
                        )
                    )
                }

                val context = transaction {
                    loadOrCreateCompanyInviteContext(companyId, adminUserId, protocol, host)
                }

                val sentIds = mutableListOf<Int>()
                val failedIds = mutableListOf<Int>()

                employees.forEach { employee ->
                    try {
                        val html = buildExpectedEmployeeInviteHtml(employee, context)
                        EmailService.send(
                            to = employee.email,
                            subject = "Einladung zur Zeiterfassung - ${context.companyName}",
                            body = html,
                            env = env
                        )

                        transaction {
                            exec(
                                """
                                UPDATE expected_employees
                                SET invite_status = 'sent',
                                    invited_at = now(),
                                    updated_at = now()
                                WHERE id = ${employee.id}
                                  AND company_id = $companyId
                                """.trimIndent()
                            )
                        }
                        sentIds.add(employee.id)
                    } catch (e: Exception) {
                        transaction {
                            exec(
                                """
                                UPDATE expected_employees
                                SET invite_status = 'failed',
                                    updated_at = now()
                                WHERE id = ${employee.id}
                                  AND company_id = $companyId
                                """.trimIndent()
                            )
                        }
                        failedIds.add(employee.id)
                        call.application.log.error("Failed to send expected employee invite to ${employee.email}", e)
                    }
                }

                call.respond(
                    HttpStatusCode.OK,
                    BulkSendExpectedEmployeeInvitesResponse(
                        sentCount = sentIds.size,
                        failedCount = failedIds.size,
                        skippedCount = 0,
                        sentIds = sentIds,
                        failedIds = failedIds,
                        skippedIds = emptyList()
                    )
                )
            } catch (e: Exception) {
                call.application.log.error("Failed to bulk send expected employee invites for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (e.message ?: e::class.simpleName.orEmpty())))
            }
        }
    }
}

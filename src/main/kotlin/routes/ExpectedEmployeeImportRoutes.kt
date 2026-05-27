
package com.yourcompany.zeiterfassung.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
private data class ExpectedEmployeeImportRowRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val personnelNumber: String? = null,
    val defaultRole: String? = null
)

@Serializable
private data class ExpectedEmployeeImportPreviewRequest(
    val rows: List<ExpectedEmployeeImportRowRequest>
)

@Serializable
private data class ExpectedEmployeeImportPreviewRowResponse(
    val rowNumber: Int,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phone: String?,
    val personnelNumber: String?,
    val defaultRole: String,
    val valid: Boolean,
    val duplicateInImport: Boolean,
    val duplicateInDatabase: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)

@Serializable
private data class ExpectedEmployeeImportPreviewResponse(
    val totalRows: Int,
    val validRows: Int,
    val invalidRows: Int,
    val duplicateRows: Int,
    val rows: List<ExpectedEmployeeImportPreviewRowResponse>
)

@Serializable
private data class ExpectedEmployeeImportConfirmRequest(
    val rows: List<ExpectedEmployeeImportRowRequest>
)

@Serializable
private data class ExpectedEmployeeImportConfirmResponse(
    val createdCount: Int,
    val skippedCount: Int,
    val createdIds: List<Int>,
    val errors: List<String>
)

@Serializable
private data class ExpectedEmployeeImportTemplateResponse(
    val columns: List<String>,
    val requiredColumns: List<String>,
    val allowedRoles: List<String>,
    val example: ExpectedEmployeeImportRowRequest
)

private fun importJwtCompanyId(principal: JWTPrincipal): Int =
    principal.payload.getClaim("companyId").asInt() ?: 0

private fun importIsAdminForJwtCompany(principal: JWTPrincipal, companyId: Int): Boolean {
    val isCompanyAdmin = principal.payload.getClaim("isCompanyAdmin").asBoolean() ?: false
    val isGlobalAdmin = principal.payload.getClaim("isGlobalAdmin").asBoolean() ?: false
    val tokenCompanyId = principal.payload.getClaim("companyId").asInt() ?: 0
    return isGlobalAdmin || (isCompanyAdmin && tokenCompanyId == companyId)
}

private fun cleanImportText(value: String?): String? =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun normalizeImportRole(value: String?): String =
    when (value?.trim()?.lowercase()) {
        "admin" -> "admin"
        "manager" -> "manager"
        "worker", null, "" -> "worker"
        else -> "worker"
    }

private fun importSqlStringOrNull(value: String?): String =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace("'", "''")
        ?.let { "'$it'" }
        ?: "NULL"

private fun importSqlRole(value: String?): String =
    "'${normalizeImportRole(value)}'"

private fun importIdentityKey(row: ExpectedEmployeeImportRowRequest): String? {
    val email = cleanImportText(row.email)?.lowercase()
    val phone = cleanImportText(row.phone)
    val personnelNumber = cleanImportText(row.personnelNumber)?.lowercase()

    return when {
        email != null -> "email:$email"
        phone != null -> "phone:$phone"
        personnelNumber != null -> "personnel:$personnelNumber"
        else -> null
    }
}

private fun validateImportRow(row: ExpectedEmployeeImportRowRequest): Pair<List<String>, List<String>> {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    val firstName = cleanImportText(row.firstName)
    val lastName = cleanImportText(row.lastName)
    val email = cleanImportText(row.email)
    val phone = cleanImportText(row.phone)
    val personnelNumber = cleanImportText(row.personnelNumber)
    val role = row.defaultRole?.trim()?.lowercase()

    if (firstName == null && lastName == null) {
        errors.add("name_required")
    }

    if (email == null && phone == null && personnelNumber == null) {
        errors.add("identity_required")
    }

    if (email != null && !email.contains("@")) {
        errors.add("invalid_email")
    }

    if (role != null && role.isNotEmpty() && role !in listOf("worker", "manager", "admin")) {
        warnings.add("unknown_role_defaulted_to_worker")
    }

    return errors to warnings
}

fun Route.expectedEmployeeImportRoutes() {
    route("/companies/self/expected-employees/import") {
        get("/template") {
            call.respond(
                HttpStatusCode.OK,
                ExpectedEmployeeImportTemplateResponse(
                    columns = listOf("firstName", "lastName", "email", "phone", "personnelNumber", "defaultRole"),
                    requiredColumns = listOf("firstName or lastName", "email or phone or personnelNumber"),
                    allowedRoles = listOf("worker", "manager", "admin"),
                    example = ExpectedEmployeeImportRowRequest(
                        firstName = "Max",
                        lastName = "Mustermann",
                        email = "max.mustermann@example.com",
                        phone = "+491701234567",
                        personnelNumber = "EMP-001",
                        defaultRole = "worker"
                    )
                )
            )
        }

        post("/preview") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = importJwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!importIsAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val body = try {
                call.receive<ExpectedEmployeeImportPreviewRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Invalid import preview payload"))
            }

            if (body.rows.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("empty_rows", "rows must not be empty"))
            }
            if (body.rows.size > 500) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("too_many_rows", "Maximum 500 rows per import"))
            }

            try {
                val response = transaction {
                    val importKeyCounts = body.rows
                        .mapNotNull { importIdentityKey(it) }
                        .groupingBy { it }
                        .eachCount()

                    val databaseKeys = mutableSetOf<String>()
                    exec(
                        """
                        SELECT email, phone, personnel_number
                        FROM expected_employees
                        WHERE company_id = $companyId
                        """.trimIndent()
                    ) { rs ->
                        while (rs.next()) {
                            cleanImportText(rs.getString("email"))?.lowercase()?.let { databaseKeys.add("email:$it") }
                            cleanImportText(rs.getString("phone"))?.let { databaseKeys.add("phone:$it") }
                            cleanImportText(rs.getString("personnel_number"))?.lowercase()?.let { databaseKeys.add("personnel:$it") }
                        }
                    }

                    val previewRows = body.rows.mapIndexed { index, row ->
                        val rowNumber = index + 1
                        val identityKey = importIdentityKey(row)
                        val duplicateInImport = identityKey != null && (importKeyCounts[identityKey] ?: 0) > 1
                        val duplicateInDatabase = identityKey != null && identityKey in databaseKeys
                        val validation = validateImportRow(row)
                        val errors = validation.first.toMutableList()
                        val warnings = validation.second.toMutableList()

                        if (duplicateInImport) warnings.add("duplicate_in_import")
                        if (duplicateInDatabase) warnings.add("duplicate_in_database")

                        ExpectedEmployeeImportPreviewRowResponse(
                            rowNumber = rowNumber,
                            firstName = cleanImportText(row.firstName),
                            lastName = cleanImportText(row.lastName),
                            email = cleanImportText(row.email),
                            phone = cleanImportText(row.phone),
                            personnelNumber = cleanImportText(row.personnelNumber),
                            defaultRole = normalizeImportRole(row.defaultRole),
                            valid = errors.isEmpty() && !duplicateInImport && !duplicateInDatabase,
                            duplicateInImport = duplicateInImport,
                            duplicateInDatabase = duplicateInDatabase,
                            errors = errors,
                            warnings = warnings
                        )
                    }

                    ExpectedEmployeeImportPreviewResponse(
                        totalRows = previewRows.size,
                        validRows = previewRows.count { it.valid },
                        invalidRows = previewRows.count { !it.valid },
                        duplicateRows = previewRows.count { it.duplicateInImport || it.duplicateInDatabase },
                        rows = previewRows
                    )
                }

                call.respond(HttpStatusCode.OK, response)
            } catch (e: Exception) {
                call.application.log.error("Failed to preview expected employees import for company $companyId", e)
                call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
            }
        }

        post("/confirm") {
            val principal = call.principal<JWTPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing token"))

            val companyId = importJwtCompanyId(principal)
            if (companyId <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("no_company", "Token has no companyId"))
            }
            if (!importIsAdminForJwtCompany(principal, companyId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Admin rights required for this company"))
            }

            val body = try {
                call.receive<ExpectedEmployeeImportConfirmRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Invalid import confirm payload"))
            }

            if (body.rows.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("empty_rows", "rows must not be empty"))
            }
            if (body.rows.size > 500) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("too_many_rows", "Maximum 500 rows per import"))
            }

            try {
                val response = transaction {
                    val importKeyCounts = body.rows
                        .mapNotNull { importIdentityKey(it) }
                        .groupingBy { it }
                        .eachCount()

                    val databaseKeys = mutableSetOf<String>()
                    exec(
                        """
                        SELECT email, phone, personnel_number
                        FROM expected_employees
                        WHERE company_id = $companyId
                        """.trimIndent()
                    ) { rs ->
                        while (rs.next()) {
                            cleanImportText(rs.getString("email"))?.lowercase()?.let { databaseKeys.add("email:$it") }
                            cleanImportText(rs.getString("phone"))?.let { databaseKeys.add("phone:$it") }
                            cleanImportText(rs.getString("personnel_number"))?.lowercase()?.let { databaseKeys.add("personnel:$it") }
                        }
                    }

                    val createdIds = mutableListOf<Int>()
                    val errors = mutableListOf<String>()
                    var skippedCount = 0

                    body.rows.forEachIndexed { index, row ->
                        val rowNumber = index + 1
                        val identityKey = importIdentityKey(row)
                        val duplicateInImport = identityKey != null && (importKeyCounts[identityKey] ?: 0) > 1
                        val duplicateInDatabase = identityKey != null && identityKey in databaseKeys
                        val validationErrors = validateImportRow(row).first

                        if (validationErrors.isNotEmpty() || duplicateInImport || duplicateInDatabase) {
                            skippedCount += 1
                            val reason = when {
                                validationErrors.isNotEmpty() -> validationErrors.joinToString("|")
                                duplicateInImport -> "duplicate_in_import"
                                duplicateInDatabase -> "duplicate_in_database"
                                else -> "invalid_row"
                            }
                            errors.add("row_$rowNumber:$reason")
                            return@forEachIndexed
                        }

                        exec(
                            """
                            INSERT INTO expected_employees (
                                company_id,
                                first_name,
                                last_name,
                                email,
                                phone,
                                personnel_number,
                                default_role,
                                status,
                                invite_status,
                                created_at,
                                updated_at
                            )
                            VALUES (
                                $companyId,
                                ${importSqlStringOrNull(row.firstName)},
                                ${importSqlStringOrNull(row.lastName)},
                                ${importSqlStringOrNull(row.email)},
                                ${importSqlStringOrNull(row.phone)},
                                ${importSqlStringOrNull(row.personnelNumber)},
                                ${importSqlRole(row.defaultRole)},
                                'expected',
                                'not_sent',
                                now(),
                                now()
                            )
                            """.trimIndent()
                        )

                        exec(
                            """
                            SELECT id
                            FROM expected_employees
                            WHERE company_id = $companyId
                              AND (
                                  (${importSqlStringOrNull(row.email)} IS NOT NULL AND email = ${importSqlStringOrNull(row.email)})
                                  OR (${importSqlStringOrNull(row.phone)} IS NOT NULL AND phone = ${importSqlStringOrNull(row.phone)})
                                  OR (${importSqlStringOrNull(row.personnelNumber)} IS NOT NULL AND personnel_number = ${importSqlStringOrNull(row.personnelNumber)})
                              )
                            ORDER BY id DESC
                            LIMIT 1
                            """.trimIndent()
                        ) { rs ->
                            if (rs.next()) {
                                val createdId = rs.getInt("id")
                                createdIds.add(createdId)
                                identityKey?.let { databaseKeys.add(it) }
                            }
                        }
                    }

                    ExpectedEmployeeImportConfirmResponse(
                        createdCount = createdIds.size,
                        skippedCount = skippedCount,
                        createdIds = createdIds,
                        errors = errors
                    )
                }

                call.respond(HttpStatusCode.OK, response)
            } catch (e: Exception) {
                call.application.log.error("Failed to confirm expected employees import for company $companyId", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "error" to "internal_error",
                        "message" to (e.message ?: e::class.simpleName.orEmpty())
                    )
                )
            }
        }
    }
}


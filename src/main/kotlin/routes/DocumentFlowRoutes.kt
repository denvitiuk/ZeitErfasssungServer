package com.yourcompany.zeiterfassung.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import io.ktor.http.content.*
import java.io.ByteArrayOutputStream
import java.io.InputStream




// --- Public DTOs & Enums ---

@Serializable
enum class RequestType {
    KRANKMELDUNG, // больничный
    URLAUB,       // отпуск
    TERMIN,       // Termin/Behörde
    VERSPAETUNG,  // опоздание/объяснение
    SONSTIGES     // прочее
}

@Serializable
enum class RequestStatus {
    EINGEREICHT,  // отправлено
    ANGENOMMEN,   // одобрено
    ABGELEHNT     // отклонено
}

@Serializable
enum class DocumentSignatureRole {
    WORKER,
    ADMIN
}

@Serializable
enum class DocumentRequestEventType {
    REQUEST_CREATED,
    WORKER_SIGNED,
    ADMIN_SIGNED,
    STATUS_CHANGED
}

@Serializable
data class TemplateDTO(
    val id: Long,
    val title: String,
    val type: RequestType,
    val locale: String,
    val storageKey: String,      // ключ в хранилище (S3/PG/CDN), не URL
    val sha256: String? = null,
    val version: Int = 1,
    val companyId: Long? = null,
    val updatedAt: Long
)

@Serializable
data class AttachmentRef(
    val objectKey: String,      // ключ в хранилище (S3/GCS) — не URL
    val fileName: String,
    val contentType: String,
    val size: Long
)

@Serializable
data class CreateRequestPayload(
    val type: RequestType,
    val dateFrom: String,       // ISO yyyy-MM-dd
    val dateTo: String,         // ISO yyyy-MM-dd
    val halfDayStart: Boolean? = null,
    val halfDayEnd: Boolean? = null,
    val note: String? = null,
    val attachments: List<AttachmentRef> = emptyList()
)

@Serializable
data class RequestDTO(
    val id: Long,
    val userId: Long,
    val companyId: Long,
    val type: RequestType,
    val status: RequestStatus,
    val dateFrom: String,
    val dateTo: String,
    val halfDayStart: Boolean? = null,
    val halfDayEnd: Boolean? = null,
    val note: String? = null,
    val attachments: List<AttachmentRef> = emptyList(),
    val signatures: List<DocumentRequestSignatureDTO> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val declineReason: String? = null
)

@Serializable
data class LeaveBalanceDTO(
    val totalDaysPerYear: Double,
    val usedDays: Double,
    val pendingDays: Double,
    val remainingDays: Double
)

@Serializable
data class AdjustLeavePayload(
    val deltaDays: Int,
    val reason: String? = null,
    val year: Int? = null
)

@Serializable
data class SetStatusPayload(
    val status: RequestStatus,  // ANGENOMMEN или ABGELEHNT
    val reason: String? = null
)

@Serializable
data class SubmitDocumentSignaturePayload(
    val signerRole: DocumentSignatureRole,
    val signatureImageBase64: String,
    val deviceInfo: String? = null
)

@Serializable
data class DocumentRequestSignatureDTO(
    val id: Long,
    val requestId: Long,
    val companyId: Long,
    val signerUserId: Long,
    val signerRole: DocumentSignatureRole,
    val signatureImageBlobId: Long? = null,
    val signatureImageUrl: String? = null,
    val documentSnapshotHash: String? = null,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val deviceInfo: String? = null,
    val signedAt: Long,
    val createdAt: Long
)

@Serializable
data class DocumentRequestEventDTO(
    val id: Long,
    val requestId: Long,
    val companyId: Long,
    val actorUserId: Long? = null,
    val eventType: DocumentRequestEventType,
    val metadata: String? = null,
    val createdAt: Long
)

@Serializable
data class DocumentSignatureSettingsDTO(
    val companyId: Long,
    val signaturesEnabled: Boolean,
    val signaturesRequired: Boolean,
    val workerSignatureRequired: Boolean,
    val adminSignatureRequired: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class UpdateDocumentSignatureSettingsPayload(
    val signaturesEnabled: Boolean? = null,
    val signaturesRequired: Boolean? = null,
    val workerSignatureRequired: Boolean? = null,
    val adminSignatureRequired: Boolean? = null
)

@Serializable
enum class UploadPurpose { REQUEST_ATTACHMENT, TEMPLATE }

@Serializable
data class PresignRequest(
    val fileName: String,
    val contentType: String,
    val size: Long,
    val purpose: UploadPurpose
)

@Serializable
data class PresignResponse(
    val uploadUrl: String,
    val objectKey: String,
    val expiresInSeconds: Long,
    val method: String = "PUT",
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class TemplateQuery(
    val locale: String? = null,
    val type: RequestType? = null,
    val includeCompanySpecific: Boolean = true
)

// Binary blob returned from storage (e.g., Postgres large object / bytea)
data class DownloadedObject(
    val bytes: ByteArray,
    val contentType: String = "application/pdf",
    val fileName: String? = null
)

private fun readBytesWithLimit(input: InputStream, maxBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0

    while (true) {
        val read = input.read(buffer)
        if (read < 0) break

        total += read
        if (total > maxBytes) return null

        output.write(buffer, 0, read)
    }

    return output.toByteArray()
}

// --- Error body (единый формат ошибок) ---

@Serializable
data class ErrorBody(val error: String)

// --- Claims helper ---

private data class UserClaims(
    val userId: Long,
    val companyId: Long?,
    val isCompanyAdmin: Boolean,
    val isGlobalAdmin: Boolean
)

private fun ApplicationCall.userClaimsOrNull(): UserClaims? {
    val jwt = principal<JWTPrincipal>() ?: return null
    val idStr = jwt.getClaim("id", String::class) ?: return null
    val userId = idStr.toLongOrNull() ?: return null
    val companyId = jwt.getClaim("companyId", Int::class)?.toLong()
    val isCompanyAdmin = jwt.getClaim("isCompanyAdmin", Boolean::class) ?: false
    val isGlobalAdmin = jwt.getClaim("isGlobalAdmin", Boolean::class) ?: false
    return UserClaims(userId, companyId, isCompanyAdmin, isGlobalAdmin)
}

private suspend fun ApplicationCall.requireAuthOrRespond(): UserClaims? {
    val claims = userClaimsOrNull()
    if (claims == null) {
        respond(HttpStatusCode.Unauthorized, ErrorBody("unauthorized"))
        return null
    }
    return claims
}

private suspend fun ApplicationCall.requireCompanyAdminOrRespond(): UserClaims? {
    val claims = requireAuthOrRespond() ?: return null
    if (!(claims.isCompanyAdmin || claims.isGlobalAdmin) || claims.companyId == null) {
        respond(HttpStatusCode.Forbidden, ErrorBody("forbidden"))
        return null
    }
    return claims
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respond(status, ErrorBody(message))
}

// --- Ports (интерфейсы сервисов/хранилищ). Реализацию даёте в своём модуле сервисов. ---

interface DocumentTemplateStorage {
    suspend fun listTemplates(companyId: Long?, query: TemplateQuery): List<TemplateDTO>
    suspend fun getTemplate(id: Long): TemplateDTO?
    suspend fun upsertTemplate(meta: TemplateDTO): TemplateDTO
    // Download binary by Postgres id (for storageKey like "pg:{id}")
}

interface DocumentRequestService {
    suspend fun create(userId: Long, companyId: Long, payload: CreateRequestPayload): RequestDTO
    suspend fun listOwn(userId: Long): List<RequestDTO>
    suspend fun listForCompany(companyId: Long, status: RequestStatus?): List<RequestDTO>
    suspend fun setStatus(adminId: Long, companyId: Long, requestId: Long, payload: SetStatusPayload): RequestDTO
    suspend fun signRequest(
        signerUserId: Long,
        companyId: Long,
        requestId: Long,
        payload: SubmitDocumentSignaturePayload,
        ipAddress: String?,
        userAgent: String?
    ): DocumentRequestSignatureDTO

    suspend fun listSignatures(
        userId: Long,
        companyId: Long,
        requestId: Long
    ): List<DocumentRequestSignatureDTO>

    suspend fun listEvents(
        userId: Long,
        companyId: Long,
        requestId: Long
    ): List<DocumentRequestEventDTO>

    suspend fun getSignatureSettings(companyId: Long): DocumentSignatureSettingsDTO

    suspend fun updateSignatureSettings(
        adminId: Long,
        companyId: Long,
        payload: UpdateDocumentSignatureSettingsPayload
    ): DocumentSignatureSettingsDTO

    suspend fun leaveBalance(userId: Long, companyId: Long): LeaveBalanceDTO
    suspend fun adminLeaveBalance(
        targetUserId: Long,
        companyId: Long,
        year: Int
    ): LeaveBalanceDTO
    suspend fun adjustLeaveEntitlement(
        adminId: Long,
        companyId: Long,
        targetUserId: Long,
        year: Int,
        deltaDays: Int,
        reason: String?
    ): LeaveBalanceDTO
}
interface DocumentUploadService {
    suspend fun presign(userId: Long, companyId: Long?, req: PresignRequest): PresignResponse

    suspend fun upload(
        userId: Long,
        companyId: Long,
        purpose: UploadPurpose,
        fileName: String,
        contentType: String,
        bytes: ByteArray
    ): AttachmentRef

    suspend fun downloadPgObject(id: Long): DownloadedObject?
}

// --- Route registration ---

/**
 * Регистрация роутов документопотока:
 * - /templates
 * - /requests
 * - /admin/requests
 * - /admin/requests/{id}/status
 * - /leave/balance
 * - /uploads/presign
 * - /files/pg/{id}
 */
fun Route.registerDocumentFlowRoutes(
    templateStorage: DocumentTemplateStorage,
    requestService: DocumentRequestService,
    uploadService: DocumentUploadService
) {
    authenticate("bearerAuth") {
        route("/templates") {
            // GET /templates?locale=de&type=URLAUB&company=true
            get {
                val claims = call.requireAuthOrRespond() ?: return@get
                val locale = call.request.queryParameters["locale"]
                val type = call.request.queryParameters["type"]?.let {
                    runCatching { RequestType.valueOf(it.uppercase()) }.getOrNull()
                }
                val includeCompany = call.request.queryParameters["company"]?.toBooleanStrictOrNull() ?: true
                val q = TemplateQuery(locale = locale, type = type, includeCompanySpecific = includeCompany)
                val list = templateStorage.listTemplates(claims.companyId, q)
                call.respond(list)
            }

            // GET /templates/{id}
            get("/{id}") {
                val claims = call.requireAuthOrRespond() ?: return@get
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_id")

                val template = templateStorage.getTemplate(id)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "not_found")

                val allowedTemplates = templateStorage.listTemplates(
                    claims.companyId,
                    TemplateQuery(includeCompanySpecific = true)
                )
                val canReadTemplate = allowedTemplates.any { it.id == template.id }
                if (!canReadTemplate) {
                    return@get call.respondError(HttpStatusCode.NotFound, "not_found")
                }

                call.respond(template)
            }
            // POST /templates (только админ). Тело — TemplateDTO (meta); сам файл загружается через /uploads/presign + PUT в сторедж.
            post {
                val claims = call.requireCompanyAdminOrRespond() ?: return@post
                val incoming = call.receive<TemplateDTO>()
                val meta = incoming.copy(companyId = claims.companyId)
                val saved = templateStorage.upsertTemplate(meta)
                call.respond(HttpStatusCode.Created, saved)
            }
        }

        // User requests
        route("/requests") {
            // POST /requests — создать заявку (Krank/Urlaub/…)
            post {
                val claims = call.requireAuthOrRespond() ?: return@post
                val companyId = claims.companyId
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "no_company")
                val payload = call.receive<CreateRequestPayload>()

                // Простая бизнес-проверка: даты
                if (payload.dateFrom.isBlank() || payload.dateTo.isBlank()) {
                    return@post call.respondError(HttpStatusCode.UnprocessableEntity, "date_required")
                }

                val created = requestService.create(claims.userId, companyId, payload)
                call.respond(HttpStatusCode.Created, created)
            }

            // GET /requests — список своих заявок
            get {
                val claims = call.requireAuthOrRespond() ?: return@get
                val list = requestService.listOwn(claims.userId)
                call.respond(list)
            }

            // GET /requests/{id}/signatures — optional SES signatures for one request
            get("/{id}/signatures") {
                val claims = call.requireAuthOrRespond() ?: return@get
                val companyId = claims.companyId
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "no_company")
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_id")

                val signatures = requestService.listSignatures(
                    userId = claims.userId,
                    companyId = companyId,
                    requestId = id
                )
                call.respond(signatures)
            }

            // GET /requests/{id}/events — audit timeline for one request
            get("/{id}/events") {
                val claims = call.requireAuthOrRespond() ?: return@get
                val companyId = claims.companyId
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "no_company")
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_id")

                val events = requestService.listEvents(
                    userId = claims.userId,
                    companyId = companyId,
                    requestId = id
                )
                call.respond(events)
            }

            // POST /requests/{id}/signatures — optional SES signature, does not change request status
            post("/{id}/signatures") {
                val claims = call.requireAuthOrRespond() ?: return@post
                val companyId = claims.companyId
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "no_company")
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_id")
                val payload = call.receive<SubmitDocumentSignaturePayload>()

                if (payload.signatureImageBase64.isBlank()) {
                    return@post call.respondError(HttpStatusCode.UnprocessableEntity, "signature_required")
                }

                if (payload.signerRole == DocumentSignatureRole.ADMIN && !(claims.isCompanyAdmin || claims.isGlobalAdmin)) {
                    return@post call.respondError(HttpStatusCode.Forbidden, "forbidden")
                }

                val signed = try {
                    requestService.signRequest(
                        signerUserId = claims.userId,
                        companyId = companyId,
                        requestId = id,
                        payload = payload,
                        ipAddress = call.request.origin.remoteHost,
                        userAgent = call.request.headers[HttpHeaders.UserAgent]
                    )
                } catch (e: IllegalStateException) {
                    when {
                        e.message?.contains("signature already exists", ignoreCase = true) == true -> {
                            return@post call.respondError(HttpStatusCode.Conflict, "signature_already_exists")
                        }
                        e.message?.contains("request not found", ignoreCase = true) == true -> {
                            return@post call.respondError(HttpStatusCode.NotFound, "request_not_found")
                        }
                        e.message?.contains("worker can only sign own request", ignoreCase = true) == true -> {
                            return@post call.respondError(HttpStatusCode.Forbidden, "worker_can_only_sign_own_request")
                        }
                        e.message?.contains("request does not belong to company", ignoreCase = true) == true -> {
                            return@post call.respondError(HttpStatusCode.Forbidden, "request_does_not_belong_to_company")
                        }
                        e.message?.contains("invalid signature image", ignoreCase = true) == true -> {
                            return@post call.respondError(HttpStatusCode.UnprocessableEntity, "invalid_signature_image")
                        }
                        e.message?.contains("signature image is empty", ignoreCase = true) == true -> {
                            return@post call.respondError(HttpStatusCode.UnprocessableEntity, "signature_required")
                        }
                        else -> throw e
                    }
                }

                call.respond(HttpStatusCode.Created, signed)
            }
        }

        // Admin moderation
        route("/admin/requests") {
            // GET /admin/requests?status=EINGEREICHT
            get {
                val claims = call.requireCompanyAdminOrRespond() ?: return@get
                val status = call.request.queryParameters["status"]?.let {
                    runCatching { RequestStatus.valueOf(it.uppercase()) }.getOrNull()
                }
                val list = requestService.listForCompany(claims.companyId!!, status)
                call.respond(list)
            }

            // PUT /admin/requests/{id}/status
            put("/{id}/status") {
                val claims = call.requireCompanyAdminOrRespond() ?: return@put
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_id")
                val payload = call.receive<SetStatusPayload>()

                if (payload.status != RequestStatus.ANGENOMMEN && payload.status != RequestStatus.ABGELEHNT) {
                    return@put call.respondError(HttpStatusCode.UnprocessableEntity, "invalid_status")
                }

                val updated = requestService.setStatus(
                    adminId = claims.userId,
                    companyId = claims.companyId!!,
                    requestId = id,
                    payload = payload
                )
                call.respond(updated)
            }

            // GET /admin/requests/{id}/events — admin audit timeline for one request
            get("/{id}/events") {
                val claims = call.requireCompanyAdminOrRespond() ?: return@get
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_id")

                val events = requestService.listEvents(
                    userId = claims.userId,
                    companyId = claims.companyId!!,
                    requestId = id
                )
                call.respond(events)
            }
        }

        route("/admin/document-signature-settings") {
            // GET /admin/document-signature-settings
            get {
                val claims = call.requireCompanyAdminOrRespond() ?: return@get
                val settings = requestService.getSignatureSettings(claims.companyId!!)
                call.respond(settings)
            }

            // PUT /admin/document-signature-settings
            put {
                val claims = call.requireCompanyAdminOrRespond() ?: return@put
                val payload = call.receive<UpdateDocumentSignatureSettingsPayload>()
                val updated = requestService.updateSignatureSettings(
                    adminId = claims.userId,
                    companyId = claims.companyId!!,
                    payload = payload
                )
                call.respond(updated)
            }
        }

        route("/admin/users") {
            // GET /admin/users/{id}/leave/balance?year=YYYY
            get("/{id}/leave/balance") {
                val claims = call.requireCompanyAdminOrRespond() ?: return@get
                val targetId = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_id")
                val year = call.request.queryParameters["year"]?.toIntOrNull()
                    ?: java.time.LocalDate.now().year
                val bal = try {
                    requestService.adminLeaveBalance(
                        targetUserId = targetId,
                        companyId = claims.companyId!!,
                        year = year
                    )
                } catch (e: IllegalStateException) {
                    if (e.message == "user_not_found_in_company") {
                        return@get call.respondError(HttpStatusCode.NotFound, "not_found")
                    }
                    throw e
                }
                call.respond(bal)
            }

            // POST /admin/users/{id}/leave/adjust
            post("/{id}/leave/adjust") {
                val claims = call.requireCompanyAdminOrRespond() ?: return@post
                val targetId = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_id")
                val payload = call.receive<AdjustLeavePayload>()
                if (payload.deltaDays == 0) {
                    return@post call.respondError(HttpStatusCode.UnprocessableEntity, "delta_zero_not_allowed")
                }
                val year = payload.year ?: java.time.LocalDate.now().year
                val newBal = requestService.adjustLeaveEntitlement(
                    adminId = claims.userId,
                    companyId = claims.companyId!!,
                    targetUserId = targetId,
                    year = year,
                    deltaDays = payload.deltaDays,
                    reason = payload.reason
                )
                call.respond(HttpStatusCode.OK, newBal)
            }
        }

        // Leave balance for current user
        get("/leave/balance") {
            val claims = call.requireAuthOrRespond() ?: return@get
            val companyId = claims.companyId
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "no_company")
            val balance = requestService.leaveBalance(
                userId = claims.userId,
                companyId = companyId
            )
            call.respond(balance)
        }

        // Presign upload (для вложений к заявке; для шаблонов — только админ)
        post("/uploads/presign") {
            val claims = call.requireAuthOrRespond() ?: return@post
            val req = call.receive<PresignRequest>()

            if (req.purpose == UploadPurpose.TEMPLATE) {
                // Требуется админ
                if (!(claims.isCompanyAdmin || claims.isGlobalAdmin)) {
                    return@post call.respondError(HttpStatusCode.Forbidden, "forbidden")
                }
            }

            if (req.fileName.isBlank() || req.size <= 0) {
                return@post call.respondError(HttpStatusCode.UnprocessableEntity, "invalid_file_meta")
            }

            val presigned = uploadService.presign(
                userId = claims.userId,
                companyId = claims.companyId,
                req = req
            )
            call.respond(HttpStatusCode.Created, presigned)
        }

        // Universal storage download by storageKey (supports keys like "pg:6")
        // Variant 1: /storage?key=pg:6
        get("/storage") {
            val claims = call.requireCompanyAdminOrRespond() ?: return@get
            val raw = call.request.queryParameters["key"]
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_key")
            call.respondError(HttpStatusCode.Gone, "direct_storage_download_disabled")
        }

        // Variant 2: /storage/{key...} — captures the whole tail (e.g., "pg:6")
        get("/storage/{key...}") {
            val claims = call.requireCompanyAdminOrRespond() ?: return@get
            call.respondError(HttpStatusCode.Gone, "direct_storage_download_disabled")
        }

        // File download for Postgres-backed template blobs, e.g. storageKey = "pg:{id}"
        get("/files/pg/{id}") {
            val claims = call.requireCompanyAdminOrRespond() ?: return@get
            call.respondError(HttpStatusCode.Gone, "direct_storage_download_disabled")
        }
    }
}


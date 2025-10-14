package com.yourcompany.zeiterfassung.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// --- Public DTOs & Enums ---

enum class RequestType {
    KRANKMELDUNG, // больничный
    URLAUB,       // отпуск
    TERMIN,       // Termin/Behörde
    VERSPAETUNG,  // опоздание/объяснение
    SONSTIGES     // прочее
}

enum class RequestStatus {
    EINGEREICHT,  // отправлено
    ANGENOMMEN,   // одобрено
    ABGELEHNT     // отклонено
}

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

data class AttachmentRef(
    val objectKey: String,      // ключ в хранилище (S3/GCS) — не URL
    val fileName: String,
    val contentType: String,
    val size: Long
)

data class CreateRequestPayload(
    val type: RequestType,
    val dateFrom: String,       // ISO yyyy-MM-dd
    val dateTo: String,         // ISO yyyy-MM-dd
    val halfDayStart: Boolean? = null,
    val halfDayEnd: Boolean? = null,
    val note: String? = null,
    val attachments: List<AttachmentRef> = emptyList()
)

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
    val createdAt: Long,
    val updatedAt: Long,
    val declineReason: String? = null
)

data class LeaveBalanceDTO(
    val totalDaysPerYear: Double,
    val usedDays: Double,
    val pendingDays: Double,
    val remainingDays: Double
)

data class SetStatusPayload(
    val status: RequestStatus,  // ANGENOMMEN или ABGELEHNT
    val reason: String? = null
)

enum class UploadPurpose { REQUEST_ATTACHMENT, TEMPLATE }

data class PresignRequest(
    val fileName: String,
    val contentType: String,
    val size: Long,
    val purpose: UploadPurpose
)

data class PresignResponse(
    val uploadUrl: String,
    val objectKey: String,
    val expiresInSeconds: Long,
    val method: String = "PUT",
    val headers: Map<String, String> = emptyMap()
)

data class TemplateQuery(
    val locale: String? = null,
    val type: RequestType? = null,
    val includeCompanySpecific: Boolean = true
)

// --- Error body (единый формат ошибок) ---

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
}

interface DocumentRequestService {
    suspend fun create(userId: Long, companyId: Long, payload: CreateRequestPayload): RequestDTO
    suspend fun listOwn(userId: Long): List<RequestDTO>
    suspend fun listForCompany(companyId: Long, status: RequestStatus?): List<RequestDTO>
    suspend fun setStatus(adminId: Long, companyId: Long, requestId: Long, payload: SetStatusPayload): RequestDTO
    suspend fun leaveBalance(userId: Long): LeaveBalanceDTO
}

interface DocumentUploadService {
    suspend fun presign(userId: Long, companyId: Long?, req: PresignRequest): PresignResponse
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
                if (call.requireAuthOrRespond() == null) return@get
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_id")
                val t = templateStorage.getTemplate(id)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "not_found")
                call.respond(t)
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
        }

        // Leave balance for current user
        get("/leave/balance") {
            val claims = call.requireAuthOrRespond() ?: return@get
            val balance = requestService.leaveBalance(claims.userId)
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
    }
}

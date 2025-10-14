package com.yourcompany.zeiterfassung.ports

import java.time.Instant
import java.time.LocalDate

/**
 * Domain enums shared by routes and services.
 * Keep values in sync with DB CHECK constraints.
 */
enum class DocumentType {
    KRANKMELDUNG, URLAUB, TERMIN, VERSPAETUNG, SONSTIGES
}

enum class RequestStatus {
    EINGEREICHT, ANGENOMMEN, ABGELEHNT
}

/* ===================== Templates ===================== */

data class UpsertTemplate(
    val title: String,
    val type: DocumentType,
    val locale: String = "de",
    val storageKey: String,
    val version: Int = 1,
    val companyId: Int? = null,
    /** null → при апдейте не трогаем is_active; при insert по умолчанию TRUE */
    val isActive: Boolean? = null,
    /** Необязательный SHA-256 файла для проверки/дедупликации */
    val sha256: ByteArray? = null
)

data class DocumentTemplate(
    val id: Int,
    val title: String,
    val type: DocumentType,
    val locale: String,
    val storageKey: String,
    val sha256: ByteArray?,       // <— важно: есть в БД и в маппере
    val version: Int,
    val companyId: Int?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class TemplateQuery(
    val type: DocumentType? = null,
    val locale: String? = null,
    val includeCompanySpecific: Boolean = true,
    val includeGlobal: Boolean = true,
    val onlyActive: Boolean = true,
    val sha256: ByteArray? = null,
    val search: String? = null       // search by title (ILIKE)
)

interface DocumentTemplateStorage {
    /**
     * Returns list of templates visible for a company (company-specific and/or global).
     * If companyId == null, only global templates are returned (when includeGlobal = true).
     */
    suspend fun listTemplates(companyId: Int?, query: TemplateQuery = TemplateQuery()): List<DocumentTemplate>

    /** Fetch single template by id (null if not found). */
    suspend fun getTemplate(id: Int): DocumentTemplate?

    /**
     * Insert or update template metadata.
     * Implementations may use INSERT .. ON CONFLICT or UPDATE by id.
     * Returns the stored (fresh) entity.
     */
    suspend fun upsertTemplate(meta: UpsertTemplate): DocumentTemplate
}

/* ===================== Requests ===================== */

data class DocumentRequest(
    val id: Int,
    val userId: Int,
    val companyId: Int,
    val type: DocumentType,
    val status: RequestStatus = RequestStatus.EINGEREICHT,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val halfDayStart: Boolean? = null,
    val halfDayEnd: Boolean? = null,
    val note: String? = null,
    val declineReason: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant?
)

data class DocumentRequestAttachment(
    val id: Int,
    val requestId: Int,
    val storageKey: String,
    val fileName: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val createdAt: Instant
)

data class DocumentRequestFull(
    val request: DocumentRequest,
    val attachments: List<DocumentRequestAttachment> = emptyList()
)

data class IncomingAttachment(
    val objectKey: String,           // already uploaded via presigned URL
    val fileName: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null
)

data class CreateDocumentRequest(
    val type: DocumentType,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val halfDayStart: Boolean? = null,
    val halfDayEnd: Boolean? = null,
    val note: String? = null,
    val attachments: List<IncomingAttachment> = emptyList()
)

data class SetStatus(
    val status: RequestStatus,
    val reason: String? = null
)

// === Compatibility aliases for PG service ===
// These aliases make PG implementation compile while ports use newer names.
// Safe to keep long-term; they are transparent at compile time.
 typealias AttachmentRef = IncomingAttachment
 typealias CreateDocumentRequestPayload = CreateDocumentRequest
 typealias SetRequestStatusPayload = SetStatus

data class LeaveBalance(
    val year: Int,
    val totalDaysPerYear: Double,
    val usedDays: Double,
    val pendingDays: Double = 0.0,
    val remainingDays: Double
)

interface DocumentRequestService {
    /**
     * Create a request with optional attachments.
     * Implementations should validate:
     *  - dateTo >= dateFrom
     *  - halfDay* flags are only allowed when dateFrom == dateTo
     * Persist attachments by linking their objectKey to the created request.
     */
    suspend fun create(userId: Int, companyId: Int, payload: CreateDocumentRequest): DocumentRequestFull

    /** Requests created by the current user (newest first). */
    suspend fun listOwn(userId: Int): List<DocumentRequestFull>

    /** Company-wide listing for admins; optional status filter. */
    suspend fun listForCompany(companyId: Int, status: RequestStatus? = null): List<DocumentRequestFull>

    /**
     * Update status for a specific request.
     * Must verify that the request belongs to the given company.
     * Should also write a row to document_request_status_history.
     */
    suspend fun setStatus(adminId: Int, companyId: Int, requestId: Int, payload: SetStatus): DocumentRequestFull

    /**
     * Return current (or given year) vacation balance.
     * Backed by v_leave_balance; may additionally compute pendingDays for EINGEREICHT requests.
     */
    suspend fun leaveBalance(userId: Int, year: Int? = null): LeaveBalance
}

/* ===================== Uploads (presign) ===================== */

enum class UploadKind {
    REQUEST_ATTACHMENT,  // user uploads evidence/doctor note/appointment etc.
    TEMPLATE             // admin uploads template PDFs
}

data class PresignUploadRequest(
    val kind: UploadKind,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long? = null
)

data class PresignedUpload(
    val uploadUrl: String,               // pre-signed PUT URL
    val objectKey: String,               // where the file will live; pass this later in attachments
    val ttlSeconds: Long,                // validity
    val headers: Map<String, String> = emptyMap() // extra headers required for upload
)

interface DocumentUploadService {
    /**
     * Issue a pre-signed upload URL for the client.
     * Implementations should generate a unique objectKey, e.g.:
     *  - REQUEST_ATTACHMENT: "companies/{companyId ?: userId}/requests/tmp/{uuid}/{safeFileName}"
     *  - TEMPLATE:          "global/templates/{TYPE}/{locale}/{slug}-v{version}.pdf" (or companies/{id}/templates/…)
     */
    suspend fun presign(userId: Int, companyId: Int?, req: PresignUploadRequest): PresignedUpload
}

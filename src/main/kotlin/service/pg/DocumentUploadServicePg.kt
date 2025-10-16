package com.yourcompany.zeiterfassung.service.pg

import com.yourcompany.zeiterfassung.routes.DownloadedObject

import com.yourcompany.zeiterfassung.routes.DocumentUploadService
import com.yourcompany.zeiterfassung.routes.PresignRequest
import com.yourcompany.zeiterfassung.routes.PresignResponse
import com.yourcompany.zeiterfassung.routes.UploadPurpose

/**
 * PG-mode implementation for upload presign.
 * We don't generate a signed URL here — client will upload via our own endpoint POST /uploads (multipart).
 * This class just tells the client where to POST and what method to use.
 */
class DocumentUploadServicePg(
    private val maxSizeBytes: Long = DEFAULT_MAX_SIZE,
    private val allowedContentTypes: Set<String> = DEFAULT_ALLOWED_CONTENT_TYPES,
    private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
) : DocumentUploadService {

    override suspend fun presign(userId: Long, companyId: Long?, req: PresignRequest): PresignResponse {
        // basic validation (same rules as server-side /uploads will enforce)
        require(req.size > 0) { "file size must be > 0" }
        require(req.size <= maxSizeBytes) { "file too large: max ${maxSizeBytes} bytes" }
        require(req.contentType.lowercase() in allowedContentTypes) {
            "unsupported content type: ${req.contentType}"
        }

        // In PG mode, actual objectKey is assigned after POST /uploads (returns AttachmentRef with pg:{id}).
        return PresignResponse(
            uploadUrl = "/uploads",
            objectKey = "", // unknown yet; client will get it from POST /uploads response
            expiresInSeconds = ttlSeconds,
            method = "POST",
            headers = emptyMap()
        )
    }

    override suspend fun downloadPgObject(id: Long): DownloadedObject? {
        // PG mode: binary download is handled by /uploads/{id} route (streamed directly from DB/storage).
        // This service does not access blobs; return null so the caller can respond 404/Not Implemented.
        // TODO: Wire a repository here if you decide to fetch bytes via service.
        return null
    }

    companion object {
        private const val DEFAULT_TTL_SECONDS: Long = 15 * 60 // 15 minutes
        private const val DEFAULT_MAX_SIZE: Long = 5L * 1024 * 1024 // 5 MB
        private val DEFAULT_ALLOWED_CONTENT_TYPES = setOf(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/heic",
            "image/heif"
        )
    }
}

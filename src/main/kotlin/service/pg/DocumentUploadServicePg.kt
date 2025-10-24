package com.yourcompany.zeiterfassung.service.pg

import com.yourcompany.zeiterfassung.routes.DownloadedObject

import com.yourcompany.zeiterfassung.routes.DocumentUploadService
import com.yourcompany.zeiterfassung.routes.PresignRequest
import com.yourcompany.zeiterfassung.routes.PresignResponse
import com.yourcompany.zeiterfassung.routes.UploadPurpose

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

// Local mapping for the `files` table (id, data, content_type, file_name, size_bytes, owner_user_id, company_id)
private object FilesTablePg : LongIdTable("files") {
    val data = binary("data")
    val contentType = text("content_type")
    val fileName = text("file_name")
    val sizeBytes = long("size_bytes").nullable()
    val ownerUserId = integer("owner_user_id").nullable()
    val companyId = integer("company_id").nullable()
}

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

    override suspend fun downloadPgObject(id: Long): DownloadedObject? = newSuspendedTransaction {
        FilesTablePg
            .slice(FilesTablePg.data, FilesTablePg.contentType, FilesTablePg.fileName)
            .select { FilesTablePg.id eq id }
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                DownloadedObject(
                    bytes = row[FilesTablePg.data],
                    contentType = row[FilesTablePg.contentType],
                    fileName = row[FilesTablePg.fileName]
                )
            }
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

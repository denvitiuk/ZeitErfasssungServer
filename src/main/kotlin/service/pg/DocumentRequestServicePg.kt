package com.yourcompany.zeiterfassung.service.pg

import com.yourcompany.zeiterfassung.ports.CreateDocumentRequestPayload
import com.yourcompany.zeiterfassung.ports.DocumentRequestFull
import com.yourcompany.zeiterfassung.ports.DocumentRequest
import com.yourcompany.zeiterfassung.ports.DocumentRequestAttachment
import com.yourcompany.zeiterfassung.ports.DocumentRequestService
import com.yourcompany.zeiterfassung.ports.DocumentRequestEvent
import com.yourcompany.zeiterfassung.ports.DocumentRequestEventType
import com.yourcompany.zeiterfassung.ports.DocumentRequestSignature
import com.yourcompany.zeiterfassung.ports.DocumentSignatureRole
import com.yourcompany.zeiterfassung.ports.DocumentSignatureSettings
import com.yourcompany.zeiterfassung.ports.DocumentType
import com.yourcompany.zeiterfassung.ports.LeaveBalance
import com.yourcompany.zeiterfassung.ports.RequestStatus
import com.yourcompany.zeiterfassung.ports.SetRequestStatusPayload
import com.yourcompany.zeiterfassung.ports.SubmitDocumentSignature
import com.yourcompany.zeiterfassung.ports.UpdateDocumentSignatureSettings
import com.yourcompany.zeiterfassung.ports.AttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * PostgreSQL implementation of [DocumentRequestService].
 *
 * Works with tables:
 *  - document_requests
 *  - document_request_attachments
 *  - document_request_status_history
 *  - view v_leave_balance (and optional pending calc)
 */
class DocumentRequestServicePg(
    private val dataSource: DataSource
) : DocumentRequestService {

    //region Public API ---------------------------------------------------------------------------

    override suspend fun create(
        userId: Int,
        companyId: Int,
        payload: CreateDocumentRequestPayload
    ): DocumentRequestFull = withContext(Dispatchers.IO) {
        require(payload.dateTo >= payload.dateFrom) { "date_to must be >= date_from" }
        if ((payload.halfDayStart == true || payload.halfDayEnd == true) &&
            payload.dateFrom != payload.dateTo
        ) {
            error("half_day flags are only allowed when date_from == date_to")
        }

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val requestId = insertRequest(conn, userId, companyId, payload)
                if (payload.attachments.isNotEmpty()) {
                    batchInsertAttachments(conn, requestId, payload.attachments)
                }
                val documentEventId = insertDocumentRequestEvent(
                    conn = conn,
                    requestId = requestId,
                    companyId = companyId,
                    actorUserId = userId,
                    eventType = DocumentRequestEventType.REQUEST_CREATED,
                    metadata = jsonObject(
                        "type" to payload.type.name,
                        "dateFrom" to payload.dateFrom.toString(),
                        "dateTo" to payload.dateTo.toString()
                    )
                )

                insertAppEvent(
                    conn = conn,
                    eventType = "DOCUMENT_REQUEST_CREATED",
                    userId = userId,
                    companyId = companyId,
                    payload = jsonObject(
                        "documentRequestId" to requestId.toString(),
                        "documentRequestEventId" to documentEventId.toString(),
                        "documentType" to payload.type.name,
                        "dateFrom" to payload.dateFrom.toString(),
                        "dateTo" to payload.dateTo.toString(),
                        "source" to "document_request_service_pg"
                    )
                )

                conn.commit()
                fetchFullRequest(conn, requestId) ?: error("created request not found")
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    override suspend fun listOwn(userId: Int): List<DocumentRequestFull> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val base = mutableListOf<DocumentRequest>()
            conn.prepareStatement(
                """
                SELECT id, user_id, company_id, type, status,
                       date_from, date_to, half_day_start, half_day_end,
                       note, decline_reason,
                       created_at, updated_at
                  FROM document_requests
                 WHERE user_id = ?
                 ORDER BY created_at DESC
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, userId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) base += mapRequestRow(rs)
                }
            }
            base.map { r -> DocumentRequestFull(request = r, attachments = loadAttachments(conn, r.id)) }
        }
    }

    override suspend fun listForCompany(
        companyId: Int,
        status: RequestStatus?
    ): List<DocumentRequestFull> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = StringBuilder(
                """
                SELECT id, user_id, company_id, type, status,
                       date_from, date_to, half_day_start, half_day_end,
                       note, decline_reason,
                       created_at, updated_at
                  FROM document_requests
                 WHERE company_id = ?
                """.trimIndent()
            )
            if (status != null) sql.append(" AND status = ?")
            sql.append(" ORDER BY created_at DESC")

            val base = mutableListOf<DocumentRequest>()
            conn.prepareStatement(sql.toString()).use { ps ->
                ps.setInt(1, companyId)
                if (status != null) ps.setString(2, status.name)
                ps.executeQuery().use { rs ->
                    while (rs.next()) base += mapRequestRow(rs)
                }
            }
            base.map { r -> DocumentRequestFull(request = r, attachments = loadAttachments(conn, r.id)) }
        }
    }

    override suspend fun setStatus(
        adminId: Int,
        companyId: Int,
        requestId: Int,
        payload: SetRequestStatusPayload
    ): DocumentRequestFull = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                // 1) Ensure request belongs to the same company
                val (currentStatus, _) = conn.prepareStatement(
                    "SELECT status, company_id FROM document_requests WHERE id = ?"
                ).use { ps ->
                    ps.setInt(1, requestId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) error("request not found")
                        val compId = rs.getInt("company_id")
                        if (compId != companyId) error("request does not belong to company")
                        RequestStatus.valueOf(rs.getString("status")) to compId
                    }
                }

                if (payload.status == RequestStatus.ANGENOMMEN) {
                    validateRequiredSignaturesBeforeAccept(conn, companyId, requestId)
                }

                // 2) Update status
                conn.prepareStatement(
                    """
                    UPDATE document_requests
                       SET status = ?, decline_reason = ?, updated_at = now()
                     WHERE id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, payload.status.name)
                    ps.setString(2, payload.reason)
                    ps.setInt(3, requestId)
                    if (ps.executeUpdate() != 1) error("failed to update request status")
                }

                // 3) History
                conn.prepareStatement(
                    """
                    INSERT INTO document_request_status_history
                        (request_id, old_status, new_status, reason, changed_by)
                    VALUES (?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setInt(1, requestId)
                    ps.setString(2, currentStatus.name)
                    ps.setString(3, payload.status.name)
                    ps.setString(4, payload.reason)
                    ps.setInt(5, adminId)
                    ps.executeUpdate()
                }

                val documentEventId = insertDocumentRequestEvent(
                    conn = conn,
                    requestId = requestId,
                    companyId = companyId,
                    actorUserId = adminId,
                    eventType = DocumentRequestEventType.STATUS_CHANGED,
                    metadata = jsonObject(
                        "oldStatus" to currentStatus.name,
                        "newStatus" to payload.status.name,
                        "reason" to payload.reason
                    )
                )

                insertAppEvent(
                    conn = conn,
                    eventType = "DOCUMENT_STATUS_CHANGED",
                    userId = adminId,
                    companyId = companyId,
                    payload = jsonObject(
                        "documentRequestId" to requestId.toString(),
                        "documentRequestEventId" to documentEventId.toString(),
                        "oldStatus" to currentStatus.name,
                        "newStatus" to payload.status.name,
                        "reason" to payload.reason,
                        "source" to "document_request_service_pg"
                    )
                )

                conn.commit()
                fetchFullRequest(conn, requestId) ?: error("updated request not found")
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    override suspend fun signRequest(
        signerUserId: Int,
        companyId: Int,
        requestId: Int,
        payload: SubmitDocumentSignature,
        ipAddress: String?,
        userAgent: String?
    ): DocumentRequestSignature = withContext(Dispatchers.IO) {
        val signatureBytes = decodeBase64Image(payload.signatureImageBase64)
        if (signatureBytes.isEmpty()) error("signature image is empty")

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val requestAccess = fetchRequestAccessRow(conn, requestId) ?: error("request not found")
                val requestOwnerId = requestAccess.first
                val requestCompanyId = requestAccess.second

                if (requestCompanyId != companyId) error("request does not belong to company")

                val settings = ensureAndFetchSignatureSettings(conn, companyId)
                if (!settings.signaturesEnabled) error("document signatures are disabled")

                when (payload.signerRole) {
                    DocumentSignatureRole.WORKER -> {
                        if (requestOwnerId != signerUserId) error("worker can only sign own request")
                    }
                    DocumentSignatureRole.ADMIN -> {
                        // Admin authorization is enforced in the route before calling this method.
                    }
                }

                ensureSignatureRoleNotExists(conn, requestId, payload.signerRole)

                val snapshotHash = computeRequestSnapshotHash(conn, requestId)
                val blobId = insertSignatureBlob(
                    conn = conn,
                    ownerUserId = signerUserId,
                    companyId = companyId,
                    bytes = signatureBytes,
                    signerRole = payload.signerRole,
                    requestId = requestId
                )

                val signatureId = insertDocumentRequestSignature(
                    conn = conn,
                    requestId = requestId,
                    companyId = companyId,
                    signerUserId = signerUserId,
                    signerRole = payload.signerRole,
                    signatureImageBlobId = blobId,
                    documentSnapshotHash = snapshotHash,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    deviceInfo = payload.deviceInfo
                )

                val documentEventId = insertDocumentRequestEvent(
                    conn = conn,
                    requestId = requestId,
                    companyId = companyId,
                    actorUserId = signerUserId,
                    eventType = when (payload.signerRole) {
                        DocumentSignatureRole.WORKER -> DocumentRequestEventType.WORKER_SIGNED
                        DocumentSignatureRole.ADMIN -> DocumentRequestEventType.ADMIN_SIGNED
                    },
                    metadata = jsonObject(
                        "signatureId" to signatureId.toString(),
                        "signatureImageBlobId" to blobId.toString(),
                        "documentSnapshotHash" to snapshotHash
                    )
                )

                insertAppEvent(
                    conn = conn,
                    eventType = "SIGNATURE_SUBMITTED",
                    userId = signerUserId,
                    companyId = companyId,
                    payload = jsonObject(
                        "documentRequestId" to requestId.toString(),
                        "documentRequestEventId" to documentEventId.toString(),
                        "signatureId" to signatureId.toString(),
                        "signatureImageBlobId" to blobId.toString(),
                        "signerRole" to payload.signerRole.name,
                        "documentSnapshotHash" to snapshotHash,
                        "source" to "document_request_service_pg"
                    )
                )

                conn.commit()
                fetchSignatureById(conn, signatureId) ?: error("created signature not found")
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    override suspend fun listSignatures(
        userId: Int,
        companyId: Int,
        requestId: Int
    ): List<DocumentRequestSignature> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val requestAccess = fetchRequestAccessRow(conn, requestId) ?: error("request not found")
            val requestOwnerId = requestAccess.first
            val requestCompanyId = requestAccess.second

            if (requestCompanyId != companyId) error("request does not belong to company")
            if (requestOwnerId != userId) {
                // Admin-wide access is enforced by company scope in the route layer.
                // This service method stays optional/read-only and scoped by companyId.
            }

            loadSignatures(conn, requestId)
        }
    }

    override suspend fun listEvents(
        userId: Int,
        companyId: Int,
        requestId: Int
    ): List<DocumentRequestEvent> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val requestAccess = fetchRequestAccessRow(conn, requestId) ?: error("request not found")
            val requestCompanyId = requestAccess.second

            if (requestCompanyId != companyId) error("request does not belong to company")

            loadEvents(conn, requestId)
        }
    }

    override suspend fun getSignatureSettings(companyId: Int): DocumentSignatureSettings =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                ensureAndFetchSignatureSettings(conn, companyId)
            }
        }

    override suspend fun updateSignatureSettings(
        adminId: Int,
        companyId: Int,
        payload: UpdateDocumentSignatureSettings
    ): DocumentSignatureSettings = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                ensureAndFetchSignatureSettings(conn, companyId)

                conn.prepareStatement(
                    """
                    UPDATE company_document_signature_settings
                       SET signatures_enabled = COALESCE(?, signatures_enabled),
                           signatures_required = COALESCE(?, signatures_required),
                           worker_signature_required = COALESCE(?, worker_signature_required),
                           admin_signature_required = COALESCE(?, admin_signature_required),
                           updated_at = now()
                     WHERE company_id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setNullableBoolean(1, payload.signaturesEnabled)
                    ps.setNullableBoolean(2, payload.signaturesRequired)
                    ps.setNullableBoolean(3, payload.workerSignatureRequired)
                    ps.setNullableBoolean(4, payload.adminSignatureRequired)
                    ps.setInt(5, companyId)
                    if (ps.executeUpdate() != 1) error("failed to update signature settings")
                }

                val updated = ensureAndFetchSignatureSettings(conn, companyId)
                conn.commit()
                updated
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    override suspend fun leaveBalance(userId: Int, year: Int?): LeaveBalance =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val y = year ?: LocalDate.now().year
                ensureLeaveEntitlement(conn, userId, y)

                // Base from view (used + total + remaining); view's pending_days is 0 — we'll compute it below
                val base: LeaveBalance = conn.prepareStatement(
                    """
                    SELECT year, total_days_per_year, used_days, pending_days, remaining_days
                      FROM v_leave_balance
                     WHERE user_id = ? AND year = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setInt(1, userId)
                    ps.setInt(2, y)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            LeaveBalance(
                                year = rs.getInt("year"),
                                totalDaysPerYear = rs.getBigDecimal("total_days_per_year").toDouble(),
                                usedDays = rs.getBigDecimal("used_days").toDouble(),
                                pendingDays = 0.0, // placeholder, replace below
                                remainingDays = rs.getBigDecimal("remaining_days").toDouble()
                            )
                        } else {
                            val total = fetchLeaveEntitlementDays(conn, userId, y)
                            LeaveBalance(
                                year = y,
                                totalDaysPerYear = total,
                                usedDays = 0.0,
                                pendingDays = 0.0,
                                remainingDays = total
                            )
                        }
                    }
                }

                val pending = computePendingDays(conn, userId, y)
                base.copy(pendingDays = pending)
            }
        }

    private fun ensureLeaveEntitlement(
        conn: Connection,
        userId: Int,
        year: Int
    ) {
        conn.prepareStatement(
            """
            INSERT INTO leave_entitlements (user_id, year, days_total)
            VALUES (
                ?,
                ?,
                COALESCE(
                    (
                        SELECT days_total
                          FROM leave_entitlements
                         WHERE user_id = ?
                         ORDER BY year DESC
                         LIMIT 1
                    ),
                    24
                )
            )
            ON CONFLICT (user_id, year) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, userId)
            ps.setInt(2, year)
            ps.setInt(3, userId)
            ps.executeUpdate()
        }
    }

    private fun fetchLeaveEntitlementDays(
        conn: Connection,
        userId: Int,
        year: Int
    ): Double {
        conn.prepareStatement(
            """
            SELECT days_total
              FROM leave_entitlements
             WHERE user_id = ? AND year = ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, userId)
            ps.setInt(2, year)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getBigDecimal("days_total").toDouble() else 24.0
            }
        }
    }

    //endregion ------------------------------------------------------------------------------------

    //region Internal helpers ---------------------------------------------------------------------

    private fun insertRequest(
        conn: Connection,
        userId: Int,
        companyId: Int,
        p: CreateDocumentRequestPayload
    ): Int {
        conn.prepareStatement(
            """
            INSERT INTO document_requests
                (user_id, company_id, type, status,
                 date_from, date_to, half_day_start, half_day_end, note)
            VALUES (?, ?, ?, 'EINGEREICHT',
                    ?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, userId)
            ps.setInt(2, companyId)
            ps.setString(3, p.type.name)
            ps.setObject(4, p.dateFrom)
            ps.setObject(5, p.dateTo)
            ps.setObject(6, p.halfDayStart)
            ps.setObject(7, p.halfDayEnd)
            ps.setString(8, p.note)
            ps.executeQuery().use { rs ->
                if (!rs.next()) error("failed to insert request")
                return rs.getInt(1)
            }
        }
    }

    private fun batchInsertAttachments(
        conn: Connection,
        requestId: Int,
        files: List<AttachmentRef>
    ) {
        conn.prepareStatement(
            """
            INSERT INTO document_request_attachments
                (request_id, storage_key, file_name, content_type, size_bytes)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            for (f in files) {
                ps.setInt(1, requestId)
                ps.setString(2, f.objectKey)
                ps.setString(3, f.fileName)
                ps.setString(4, f.contentType)
                if (f.sizeBytes != null) ps.setLong(5, f.sizeBytes) else ps.setNull(5, java.sql.Types.BIGINT)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun fetchFullRequest(conn: Connection, requestId: Int): DocumentRequestFull? {
        val req: DocumentRequest = conn.prepareStatement(
            """
            SELECT id, user_id, company_id, type, status,
                   date_from, date_to, half_day_start, half_day_end,
                   note, decline_reason,
                   created_at, updated_at
              FROM document_requests
             WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, requestId)
            ps.executeQuery().use { rs -> if (rs.next()) mapRequestRow(rs) else null }
        } ?: return null

        val atts = loadAttachments(conn, requestId)
        return DocumentRequestFull(request = req, attachments = atts)
    }

    private fun fetchRequestAccessRow(conn: Connection, requestId: Int): Pair<Int, Int>? {
        conn.prepareStatement(
            """
            SELECT user_id, company_id
              FROM document_requests
             WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, requestId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) {
                    rs.getInt("user_id") to rs.getInt("company_id")
                } else {
                    null
                }
            }
        }
    }

    private fun ensureSignatureRoleNotExists(
        conn: Connection,
        requestId: Int,
        signerRole: DocumentSignatureRole
    ) {
        conn.prepareStatement(
            """
            SELECT 1
              FROM document_request_signatures
             WHERE request_id = ? AND signer_role = ?
             LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, requestId.toLong())
            ps.setString(2, signerRole.name)
            ps.executeQuery().use { rs ->
                if (rs.next()) error("signature already exists for role ${signerRole.name}")
            }
        }
    }

    private fun computeRequestSnapshotHash(conn: Connection, requestId: Int): String {
        val requestPart = conn.prepareStatement(
            """
            SELECT id, user_id, company_id, type, status,
                   date_from, date_to, half_day_start, half_day_end,
                   note, decline_reason, created_at, updated_at
              FROM document_requests
             WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, requestId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) error("request not found")
                listOf(
                    rs.getInt("id"),
                    rs.getInt("user_id"),
                    rs.getInt("company_id"),
                    rs.getString("type"),
                    rs.getString("status"),
                    rs.getObject("date_from")?.toString(),
                    rs.getObject("date_to")?.toString(),
                    rs.getObject("half_day_start")?.toString(),
                    rs.getObject("half_day_end")?.toString(),
                    rs.getString("note"),
                    rs.getString("decline_reason"),
                    rs.getTimestamp("created_at")?.toInstant()?.toString(),
                    rs.getTimestamp("updated_at")?.toInstant()?.toString()
                ).joinToString("|")
            }
        }

        val attachmentsPart = conn.prepareStatement(
            """
            SELECT storage_key, file_name, content_type, size_bytes
              FROM document_request_attachments
             WHERE request_id = ?
             ORDER BY id ASC
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, requestId)
            ps.executeQuery().use { rs ->
                val parts = mutableListOf<String>()
                while (rs.next()) {
                    parts += listOf(
                        rs.getString("storage_key"),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getLongOrNull("size_bytes")?.toString()
                    ).joinToString("|")
                }
                parts.joinToString("||")
            }
        }

        return sha256Hex("$requestPart::$attachmentsPart".toByteArray(Charsets.UTF_8))
    }

    private fun insertSignatureBlob(
        conn: Connection,
        ownerUserId: Int,
        companyId: Int,
        bytes: ByteArray,
        signerRole: DocumentSignatureRole,
        requestId: Int
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO document_file_blobs
                (data, content_type, file_name, size_bytes, owner_user_id, company_id)
            VALUES (?, 'image/png', ?, ?, ?, ?)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setBytes(1, bytes)
            ps.setString(2, "signature_${requestId}_${signerRole.name.lowercase()}.png")
            ps.setLong(3, bytes.size.toLong())
            ps.setInt(4, ownerUserId)
            ps.setInt(5, companyId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) error("failed to insert signature blob")
                return rs.getLong("id")
            }
        }
    }

    private fun insertDocumentRequestSignature(
        conn: Connection,
        requestId: Int,
        companyId: Int,
        signerUserId: Int,
        signerRole: DocumentSignatureRole,
        signatureImageBlobId: Long,
        documentSnapshotHash: String,
        ipAddress: String?,
        userAgent: String?,
        deviceInfo: String?
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO document_request_signatures
                (request_id, company_id, signer_user_id, signer_role,
                 signature_image_blob_id, document_snapshot_hash,
                 ip_address, user_agent, device_info)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, requestId.toLong())
            ps.setLong(2, companyId.toLong())
            ps.setLong(3, signerUserId.toLong())
            ps.setString(4, signerRole.name)
            ps.setLong(5, signatureImageBlobId)
            ps.setString(6, documentSnapshotHash)
            ps.setString(7, ipAddress)
            ps.setString(8, userAgent)
            ps.setString(9, deviceInfo)
            ps.executeQuery().use { rs ->
                if (!rs.next()) error("failed to insert document request signature")
                return rs.getLong("id")
            }
        }
    }

    private fun fetchSignatureById(conn: Connection, signatureId: Long): DocumentRequestSignature? {
        conn.prepareStatement(
            """
            SELECT id, request_id, company_id, signer_user_id, signer_role,
                   signature_image_blob_id, document_snapshot_hash,
                   ip_address, user_agent, device_info,
                   signed_at, created_at
              FROM document_request_signatures
             WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, signatureId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) mapSignatureRow(rs) else null
            }
        }
    }

    private fun loadSignatures(conn: Connection, requestId: Int): List<DocumentRequestSignature> {
        val out = mutableListOf<DocumentRequestSignature>()
        conn.prepareStatement(
            """
            SELECT id, request_id, company_id, signer_user_id, signer_role,
                   signature_image_blob_id, document_snapshot_hash,
                   ip_address, user_agent, device_info,
                   signed_at, created_at
              FROM document_request_signatures
             WHERE request_id = ?
             ORDER BY signed_at ASC
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, requestId.toLong())
            ps.executeQuery().use { rs ->
                while (rs.next()) out += mapSignatureRow(rs)
            }
        }
        return out
    }

    private fun insertDocumentRequestEvent(
        conn: Connection,
        requestId: Int,
        companyId: Int,
        actorUserId: Int?,
        eventType: DocumentRequestEventType,
        metadata: String?
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO document_request_events
                (request_id, company_id, actor_user_id, event_type, metadata)
            VALUES (?, ?, ?, ?, ?::jsonb)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, requestId)
            ps.setInt(2, companyId)
            if (actorUserId != null) ps.setInt(3, actorUserId) else ps.setNull(3, Types.INTEGER)
            ps.setString(4, eventType.name)
            ps.setString(5, metadata)
            ps.executeQuery().use { rs ->
                if (!rs.next()) error("failed to insert document request event")
                return rs.getLong("id")
            }
        }
    }

    private fun loadEvents(conn: Connection, requestId: Int): List<DocumentRequestEvent> {
        val out = mutableListOf<DocumentRequestEvent>()
        conn.prepareStatement(
            """
            SELECT id, request_id, company_id, actor_user_id, event_type,
                   metadata::text AS metadata, created_at
              FROM document_request_events
             WHERE request_id = ?
             ORDER BY created_at DESC
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, requestId)
            ps.executeQuery().use { rs ->
                while (rs.next()) out += mapEventRow(rs)
            }
        }
        return out
    }

    private fun mapEventRow(rs: ResultSet): DocumentRequestEvent =
        DocumentRequestEvent(
            id = rs.getLong("id"),
            requestId = rs.getInt("request_id"),
            companyId = rs.getInt("company_id"),
            actorUserId = rs.getIntOrNull("actor_user_id"),
            eventType = DocumentRequestEventType.valueOf(rs.getString("event_type")),
            metadata = rs.getString("metadata"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )

    private fun ensureAndFetchSignatureSettings(conn: Connection, companyId: Int): DocumentSignatureSettings {
        conn.prepareStatement(
            """
            INSERT INTO company_document_signature_settings (company_id)
            VALUES (?)
            ON CONFLICT (company_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, companyId)
            ps.executeUpdate()
        }

        conn.prepareStatement(
            """
            SELECT company_id, signatures_enabled, signatures_required,
                   worker_signature_required, admin_signature_required,
                   created_at, updated_at
              FROM company_document_signature_settings
             WHERE company_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, companyId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) error("signature settings not found")
                return DocumentSignatureSettings(
                    companyId = rs.getInt("company_id"),
                    signaturesEnabled = rs.getBoolean("signatures_enabled"),
                    signaturesRequired = rs.getBoolean("signatures_required"),
                    workerSignatureRequired = rs.getBoolean("worker_signature_required"),
                    adminSignatureRequired = rs.getBoolean("admin_signature_required"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant()
                )
            }
        }
    }

    private fun validateRequiredSignaturesBeforeAccept(
        conn: Connection,
        companyId: Int,
        requestId: Int
    ) {
        val settings = ensureAndFetchSignatureSettings(conn, companyId)
        if (!settings.signaturesEnabled || !settings.signaturesRequired) return

        val existingRoles = loadSignatures(conn, requestId).map { it.signerRole }.toSet()

        if (settings.workerSignatureRequired && DocumentSignatureRole.WORKER !in existingRoles) {
            error("missing required worker signature")
        }
        if (settings.adminSignatureRequired && DocumentSignatureRole.ADMIN !in existingRoles) {
            error("missing required admin signature")
        }
    }

    private fun mapSignatureRow(rs: ResultSet): DocumentRequestSignature =
        DocumentRequestSignature(
            id = rs.getLong("id"),
            requestId = rs.getLong("request_id").toInt(),
            companyId = rs.getLong("company_id").toInt(),
            signerUserId = rs.getLong("signer_user_id").toInt(),
            signerRole = DocumentSignatureRole.valueOf(rs.getString("signer_role")),
            signatureImageBlobId = rs.getLongOrNull("signature_image_blob_id"),
            documentSnapshotHash = rs.getString("document_snapshot_hash"),
            ipAddress = rs.getString("ip_address"),
            userAgent = rs.getString("user_agent"),
            deviceInfo = rs.getString("device_info"),
            signedAt = rs.getTimestamp("signed_at").toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )

    private fun decodeBase64Image(value: String): ByteArray {
        val cleaned = value.substringAfter("base64,", value).trim()
        return runCatching { Base64.getDecoder().decode(cleaned) }
            .getOrElse { error("invalid signature image") }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun mapRequestRow(rs: ResultSet): DocumentRequest =
        DocumentRequest(
            id = rs.getInt("id"),
            userId = rs.getInt("user_id"),
            companyId = rs.getInt("company_id"),
            type = DocumentType.valueOf(rs.getString("type")),
            status = RequestStatus.valueOf(rs.getString("status")),
            dateFrom = rs.getObject("date_from", LocalDate::class.java),
            dateTo = rs.getObject("date_to", LocalDate::class.java),
            halfDayStart = rs.getObject("half_day_start") as Boolean?,
            halfDayEnd = rs.getObject("half_day_end") as Boolean?,
            note = rs.getString("note"),
            declineReason = rs.getString("decline_reason"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )

    private fun loadAttachments(conn: Connection, requestId: Int): List<DocumentRequestAttachment> {
        val out = mutableListOf<DocumentRequestAttachment>()
        conn.prepareStatement(
            """
            SELECT id, request_id, storage_key, file_name, content_type, size_bytes, created_at
              FROM document_request_attachments
             WHERE request_id = ?
             ORDER BY created_at ASC
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, requestId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += DocumentRequestAttachment(
                        id = rs.getInt("id"),
                        requestId = rs.getInt("request_id"),
                        storageKey = rs.getString("storage_key"),
                        fileName = rs.getString("file_name"),
                        contentType = rs.getString("content_type"),
                        sizeBytes = rs.getLongOrNull("size_bytes"),
                        createdAt = rs.getTimestamp("created_at").toInstant()
                    )
                }
            }
        }
        return out
    }

    /**
     * Pending vacation days (requests with status = EINGEREICHT) for the given user/year.
     * Uses business_days(d1,d2) and subtracts half-day flags.
     */
    private fun computePendingDays(conn: Connection, userId: Int, year: Int): Double {
        conn.prepareStatement(
            """
            SELECT COALESCE(SUM(GREATEST(
                       0,
                       business_days(date_from, date_to)
                       - (CASE WHEN COALESCE(half_day_start,false) THEN 0.5 ELSE 0 END)
                       - (CASE WHEN COALESCE(half_day_end,false)   THEN 0.5 ELSE 0 END)
                   )), 0) AS pending
              FROM document_requests
             WHERE user_id = ?
               AND type = 'URLAUB'
               AND status = 'EINGEREICHT'
               AND EXTRACT(YEAR FROM date_from)::INT = ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, userId)
            ps.setInt(2, year)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getBigDecimal("pending")?.toDouble() ?: 0.0 else 0.0
            }
        }
    }

    // Simple helper for nullable BIGINT
    private fun ResultSet.getLongOrNull(column: String): Long? =
        getLong(column).let { if (wasNull()) null else it }

    private fun ResultSet.getIntOrNull(column: String): Int? =
        getInt(column).let { if (wasNull()) null else it }

    private fun java.sql.PreparedStatement.setNullableBoolean(index: Int, value: Boolean?) {
        if (value == null) setNull(index, Types.BOOLEAN) else setBoolean(index, value)
    }

    private fun jsonObject(vararg pairs: Pair<String, String?>): String {
        return pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val encodedKey = jsonEscape(key)
            if (value == null) {
                "\"$encodedKey\":null"
            } else {
                "\"$encodedKey\":\"${jsonEscape(value)}\""
            }
        }
    }

    private fun jsonEscape(value: String): String =
        buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }

    private fun insertAppEvent(
        conn: Connection,
        eventType: String,
        userId: Int,
        companyId: Int,
        payload: String
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO app_events
                (event_id, event_type, user_id, company_id, source, status,
                 payload, occurred_at, received_at)
            VALUES (?, ?, ?, ?, 'backend', 'received', ?::jsonb, now(), now())
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, eventType)
            ps.setInt(3, userId)
            ps.setInt(4, companyId)
            ps.setString(5, payload)
            ps.executeQuery().use { rs ->
                if (!rs.next()) error("failed to insert app event")
                return rs.getLong("id")
            }
        }
    }

    //endregion -----------------------------------------------------------------------------------
}

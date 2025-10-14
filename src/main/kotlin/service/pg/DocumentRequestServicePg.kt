package com.yourcompany.zeiterfassung.service.pg

import com.yourcompany.zeiterfassung.ports.CreateDocumentRequestPayload
import com.yourcompany.zeiterfassung.ports.DocumentRequestFull
import com.yourcompany.zeiterfassung.ports.DocumentRequest
import com.yourcompany.zeiterfassung.ports.DocumentRequestAttachment
import com.yourcompany.zeiterfassung.ports.DocumentRequestService
import com.yourcompany.zeiterfassung.ports.DocumentType
import com.yourcompany.zeiterfassung.ports.LeaveBalance
import com.yourcompany.zeiterfassung.ports.RequestStatus
import com.yourcompany.zeiterfassung.ports.SetRequestStatusPayload
import com.yourcompany.zeiterfassung.ports.AttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
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

    override suspend fun leaveBalance(userId: Int, year: Int?): LeaveBalance =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val y = year ?: LocalDate.now().year

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
                            LeaveBalance(
                                year = y,
                                totalDaysPerYear = 0.0,
                                usedDays = 0.0,
                                pendingDays = 0.0,
                                remainingDays = 0.0
                            )
                        }
                    }
                }

                val pending = computePendingDays(conn, userId, y)
                base.copy(pendingDays = pending)
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

    //endregion -----------------------------------------------------------------------------------
}

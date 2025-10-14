package com.yourcompany.zeiterfassung.service.pg

import com.yourcompany.zeiterfassung.ports.DocumentTemplate
import com.yourcompany.zeiterfassung.ports.DocumentTemplateStorage
import com.yourcompany.zeiterfassung.ports.DocumentType
import com.yourcompany.zeiterfassung.ports.TemplateQuery
import com.yourcompany.zeiterfassung.ports.UpsertTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * PostgreSQL implementation for document templates storage.
 * Works with table `document_templates` from migrations.
 */
class TemplateStoragePg(
    private val dataSource: DataSource
) : DocumentTemplateStorage {

    override suspend fun listTemplates(
        companyId: Int?,
        query: TemplateQuery
    ): List<DocumentTemplate> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val (sql, params) = buildListSql(companyId, query)
            conn.prepareStatement(sql).use { ps ->
                bindParams(ps, params)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<DocumentTemplate>()
                    while (rs.next()) out += rs.toTemplate()
                    out
                }
            }
        }
    }

    override suspend fun getTemplate(id: Int): DocumentTemplate? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, title, type, locale, storage_key, sha256, version,
                       company_id, is_active, created_at, updated_at
                  FROM document_templates
                 WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toTemplate() else null }
            }
        }
    }

    override suspend fun upsertTemplate(meta: UpsertTemplate): DocumentTemplate = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val existingId = findExistingId(conn, meta)
                val updated: DocumentTemplate = if (existingId != null) {
                    conn.prepareStatement(
                        """
                        UPDATE document_templates
                           SET storage_key = ?,
                               sha256      = ?,
                               is_active   = COALESCE(?, is_active),
                               updated_at  = now()
                         WHERE id = ?
                     RETURNING id, title, type, locale, storage_key, sha256, version,
                               company_id, is_active, created_at, updated_at
                        """.trimIndent()
                    ).use { ps ->
                        ps.setString(1, meta.storageKey)
                        ps.setNullableBytes(2, meta.sha256)
                        if (meta.isActive == null) ps.setNull(3, Types.BOOLEAN) else ps.setBoolean(3, meta.isActive)
                        ps.setInt(4, existingId)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "UPDATE did not return a row" }
                            rs.toTemplate()
                        }
                    }
                } else {
                    conn.prepareStatement(
                        """
                        INSERT INTO document_templates
                            (title, type, locale, storage_key, sha256, version, company_id, is_active)
                        VALUES (?,?,?,?,?,?,?,COALESCE(?, TRUE))
                        RETURNING id, title, type, locale, storage_key, sha256, version,
                                  company_id, is_active, created_at, updated_at
                        """.trimIndent()
                    ).use { ps ->
                        ps.setString(1, meta.title)
                        ps.setString(2, meta.type.name)
                        ps.setString(3, meta.locale)
                        ps.setString(4, meta.storageKey)
                        ps.setNullableBytes(5, meta.sha256)
                        ps.setInt(6, meta.version)
                        ps.setNullableInt(7, meta.companyId)
                        if (meta.isActive == null) ps.setNull(8, Types.BOOLEAN) else ps.setBoolean(8, meta.isActive)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "INSERT did not return a row" }
                            rs.toTemplate()
                        }
                    }
                }
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

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun buildListSql(companyId: Int?, q: TemplateQuery): Pair<String, List<Any?>> {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any?>()

        if (q.onlyActive) where += "is_active = TRUE"
        if (q.type != null) { where += "type = ?"; params += q.type.name }
        if (q.locale != null) { where += "locale = ?"; params += q.locale }

        when {
            q.includeCompanySpecific && q.includeGlobal -> {
                if (companyId != null) {
                    where += "(company_id = ? OR company_id IS NULL)"; params += companyId
                } else where += "company_id IS NULL"
            }
            q.includeCompanySpecific -> {
                if (companyId != null) { where += "company_id = ?"; params += companyId } else where += "1=0"
            }
            q.includeGlobal -> where += "company_id IS NULL"
            else -> where += "1=0"
        }

        val sql = buildString {
            append(
                """
                SELECT id, title, type, locale, storage_key, sha256, version,
                       company_id, is_active, created_at, updated_at
                  FROM document_templates
                """.trimIndent()
            )
            if (where.isNotEmpty()) append("\n WHERE ").append(where.joinToString(" AND "))
            append("\n ORDER BY COALESCE(company_id,0) DESC, type, title, version DESC")
        }
        return sql to params
    }

    private fun bindParams(ps: PreparedStatement, params: List<Any?>) {
        var i = 1
        for (p in params) {
            when (p) {
                null -> ps.setNull(i, Types.NULL)
                is Int -> ps.setInt(i, p)
                is String -> ps.setString(i, p)
                is Boolean -> ps.setBoolean(i, p)
                else -> ps.setObject(i, p)
            }
            i++
        }
    }

    /** Find existing by semantic unique: (company_id NULL-or-value, type, locale, title, version). */
    private fun findExistingId(conn: Connection, meta: UpsertTemplate): Int? {
        val sql = """
            SELECT id
              FROM document_templates
             WHERE type = ? AND locale = ? AND title = ? AND version = ?
               AND ( (company_id IS NULL AND ? IS NULL) OR company_id = ? )
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, meta.type.name)
            ps.setString(2, meta.locale)
            ps.setString(3, meta.title)
            ps.setInt(4, meta.version)
            if (meta.companyId == null) ps.setNull(5, Types.INTEGER) else ps.setInt(5, meta.companyId)
            ps.setNullableInt(6, meta.companyId)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else null }
        }
    }
}

// ------------------------------
// Mappers & JDBC helpers
// ------------------------------

private fun ResultSet.toTemplate(): DocumentTemplate = DocumentTemplate(
    id = getInt("id"),
    title = getString("title"),
    type = DocumentType.valueOf(getString("type")),
    locale = getString("locale"),
    storageKey = getString("storage_key"),
    sha256 = getBytes("sha256"),
    version = getInt("version"),
    companyId = getObject("company_id") as Int?,
    isActive = getBoolean("is_active"),
    createdAt = getTimestamp("created_at").toInstant(),
    updatedAt = getTimestamp("updated_at").toInstant()
)

private fun PreparedStatement.setNullableInt(index: Int, value: Int?) {
    if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
}

private fun PreparedStatement.setNullableBytes(index: Int, value: ByteArray?) {
    if (value == null) setNull(index, Types.BINARY) else setBytes(index, value)
}

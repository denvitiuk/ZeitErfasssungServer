
package com.yourcompany.zeiterfassung.service.pg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Types
import javax.sql.DataSource

/**
 * Простое PG-хранилище бинарников для документ-потока.
 * Таблица: document_file_blobs
 *  - id BIGSERIAL PK
 *  - data BYTEA
 *  - content_type TEXT
 *  - file_name TEXT
 *  - size_bytes BIGINT
 *  - owner_user_id BIGINT
 *  - company_id BIGINT NULL
 *  - created_at TIMESTAMPTZ DEFAULT now()
 */
class FilesStoragePg(
    private val dataSource: DataSource
) {
    data class FileBlob(
        val bytes: ByteArray,
        val contentType: String,
        val fileName: String,
        val sizeBytes: Long
    )

    /**
     * Сохраняет файл в PG и возвращает blobId. objectKey для вложений формируй как "pg:{blobId}".
     */
    suspend fun insert(
        data: ByteArray,
        fileName: String,
        contentType: String,
        size: Long,
        ownerUserId: Long,
        companyId: Long?
    ): Long = withContext(Dispatchers.IO) {
        require(size >= 0) { "size must be >= 0" }
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO document_file_blobs
                    (data, content_type, file_name, size_bytes, owner_user_id, company_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                RETURNING id
                """.trimIndent()
            ).use { ps ->
                ps.setBytes(1, data)
                ps.setString(2, contentType)
                ps.setString(3, fileName)
                ps.setLong(4, size)
                ps.setLong(5, ownerUserId)
                if (companyId != null) ps.setLong(6, companyId) else ps.setNull(6, Types.BIGINT)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "INSERT did not return id" }
                    rs.getLong(1)
                }
            }
        }
    }

    /**
     * Возвращает файл по blobId (или null, если не найден).
     */
    suspend fun find(blobId: Long): FileBlob? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT data, content_type, file_name, size_bytes
                FROM document_file_blobs
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, blobId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    val bytes = rs.getBytes("data")
                    val ct = rs.getString("content_type")
                    val fn = rs.getString("file_name")
                    val sz = rs.getLong("size_bytes")
                    FileBlob(bytes = bytes, contentType = ct, fileName = fn, sizeBytes = sz)
                }
            }
        }
    }

    /**
     * Хелперы для objectKey вида "pg:{id}".
     */
    fun objectKeyForId(id: Long): String = "pg:$id"

    fun parseObjectKey(objectKey: String): Long? =
        if (objectKey.startsWith("pg:")) objectKey.removePrefix("pg:").toLongOrNull() else null
}



package com.yourcompany.zeiterfassung.service

import com.yourcompany.zeiterfassung.routes.DocumentTemplateStorage as RoutesTemplateStorage
import com.yourcompany.zeiterfassung.routes.TemplateDTO
import com.yourcompany.zeiterfassung.routes.TemplateQuery as RoutesTemplateQuery
import com.yourcompany.zeiterfassung.routes.RequestType

import com.yourcompany.zeiterfassung.ports.DocumentTemplateStorage as PortsTemplateStorage
import com.yourcompany.zeiterfassung.ports.DocumentTemplate
import com.yourcompany.zeiterfassung.ports.UpsertTemplate
import com.yourcompany.zeiterfassung.ports.TemplateQuery as PortsTemplateQuery
import com.yourcompany.zeiterfassung.ports.DocumentType

/**
 * Adapter-service между роутами и портами для работы с шаблонами документов.
 * Реализует интерфейс роутов и делегирует в портовый DAO (TemplateStoragePg и т.п.).
 */
class TemplateService(
    private val storage: PortsTemplateStorage
) : RoutesTemplateStorage {

    override suspend fun listTemplates(companyId: Long?, query: RoutesTemplateQuery): List<TemplateDTO> {
        val pq = query.toPorts()
        return storage.listTemplates(companyId?.toInt(), pq).map { it.toRoutes() }
    }

    override suspend fun getTemplate(id: Long): TemplateDTO? =
        storage.getTemplate(id.toInt())?.toRoutes()

    override suspend fun upsertTemplate(meta: TemplateDTO): TemplateDTO {
        val saved = storage.upsertTemplate(meta.toUpsert())
        return saved.toRoutes()
    }

    // ----------------------------------------------------
    // Mappers
    // ----------------------------------------------------

    private fun RoutesTemplateQuery.toPorts(): PortsTemplateQuery =
        PortsTemplateQuery(
            type = this.type?.let { DocumentType.valueOf(it.name) },
            locale = this.locale,
            includeCompanySpecific = this.includeCompanySpecific,
            includeGlobal = true,
            onlyActive = true,
            sha256 = null,
            search = null
        )

    private fun DocumentTemplate.toRoutes(): TemplateDTO =
        TemplateDTO(
            id = this.id.toLong(),
            title = this.title,
            type = RequestType.valueOf(this.type.name),
            locale = this.locale,
            storageKey = this.storageKey,
            sha256 = this.sha256?.toHexLower(),
            version = this.version,
            companyId = this.companyId?.toLong(),
            updatedAt = this.updatedAt.toEpochMilli()
        )

    private fun TemplateDTO.toUpsert(): UpsertTemplate =
        UpsertTemplate(
            title = this.title,
            type = DocumentType.valueOf(this.type.name),
            locale = this.locale,
            storageKey = this.storageKey,
            version = this.version,
            companyId = this.companyId?.toInt(),
            isActive = null, // не трогаем is_active при апдейте, по умолчанию TRUE при insert
            sha256 = this.sha256?.let { hexToBytes(it) }
        )
}

// ----------------------------------------------------
// Hex helpers
// ----------------------------------------------------

private fun ByteArray.toHexLower(): String = joinToString("") { b -> "%02x".format(b) }

private fun hexToBytes(input: String): ByteArray {
    val s = input.trim().removePrefix("0x").replace(" ", "").lowercase()
    require(s.length % 2 == 0) { "Invalid hex length" }
    val out = ByteArray(s.length / 2)
    var i = 0
    var j = 0
    while (i < s.length) {
        val hi = Character.digit(s[i], 16)
        val lo = Character.digit(s[i + 1], 16)
        require(hi >= 0 && lo >= 0) { "Invalid hex char" }
        out[j++] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
}


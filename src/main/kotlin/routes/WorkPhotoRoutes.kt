package com.yourcompany.zeiterfassung.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.content.* // for MultiPart
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.javatime.datetime
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import java.security.MessageDigest

/* === Exposed таблицы (минимум для валидации и вставки) =================== */
object Projects : Table("projects") {
    val id = integer("id").autoIncrement()
    val companyId = integer("company_id")
    override val primaryKey = PrimaryKey(id)
}
object ProjectMembers : Table("project_members") {
    val projectId = integer("project_id")
    val userId = integer("user_id")
}
object WorkPhotos : Table("work_photos") {
    val id = uuid("id")
    val companyId = integer("company_id")
    val projectId = integer("project_id")
    val userId = integer("user_id")
    val type = varchar("type", 10) // 'BEFORE'|'AFTER'
    val caption = text("caption").nullable()
    val status = short("status") // 0=pending,1=approved,2=rejected
    val storageKey = text("storage_key")
    val url = text("url").nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val sizeBytes = long("size_bytes").nullable()
    val checksumMd5 = binary("checksum_md5").nullable()
    val takenAt = datetime("taken_at").nullable()         // если у тебя TIMESTAMPTZ — можно use `timestampWithTimeZone`
    val createdAt = datetime("created_at")                // DEFAULT now() в БД
    val approvedBy = integer("approved_by").nullable()
    val approvedAt = datetime("approved_at").nullable()
    val rejectedReason = text("rejected_reason").nullable()
}

/* === DTO для ответа ======================================================= */
@Serializable
data class WorkPhotoDTO(
    val id: String,
    val type: String,
    val url: String?,
    val caption: String?,
    val status: Int,
    val createdAt: String,
    val takenAt: String?
)

/* === Утилиты ============================================================== */
private fun readUserId(principal: JWTPrincipal): Int? =
    principal.payload.getClaim("id")?.asInt()
        ?: principal.payload.getClaim("id")?.asString()?.toIntOrNull()

private fun readCompanyId(principal: JWTPrincipal): Int? =
    principal.payload.getClaim("companyId")?.asInt()
        ?: principal.payload.getClaim("companyId")?.asString()?.toIntOrNull()

/* === Роуты ================================================================ */
fun Route.workPhotoRoutes() {
    authenticate("bearerAuth") {
        route("/api/work-photos") {

            /**
             * POST /api/work-photos/{projectId}
             * multipart form-data:
             *   - file: бинарник
             *   - type: "BEFORE" | "AFTER" (обязательно)
             *   - caption: текст (опц.)
             *   - takenAt: ISO8601 (опц.) — например "2025-08-19T07:45:00+02:00"
             */
            post("/{projectId}") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val userId = readUserId(principal)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no_user_in_token"))

                val projectId = call.parameters["projectId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "projectId_required"))

                // Проверка: пользователь — член проекта, и проект существует
                val (companyId, isMember) = transaction {
                    val projCompany = Projects
                        .slice(Projects.companyId)
                        .select { Projects.id eq projectId }
                        .limit(1)
                        .singleOrNull()
                        ?.get(Projects.companyId)

                    if (projCompany == null) {
                        Pair(null, false)
                    } else {
                        val member = ProjectMembers
                            .slice(ProjectMembers.userId)
                            .select { (ProjectMembers.projectId eq projectId) and (ProjectMembers.userId eq userId) }
                            .limit(1)
                            .any()
                        Pair(projCompany, member)
                    }
                }
                if (companyId == null) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "project_not_found"))
                if (!isMember) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_project_member"))

                // Парсим multipart
                var fileBytes: ByteArray? = null
                var fileExt = "jpg"
                var type: String? = null
                var caption: String? = null
                var takenAtStr: String? = null
                var takenAtParsed: OffsetDateTime? = null

                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> when (part.name) {
                            // Поддерживаем и type, и kind + регистр не важен
                            "type", "kind" -> type = part.value.trim().uppercase()
                            "caption" -> caption = part.value
                            "takenAt" -> {
                                takenAtStr = part.value
                                takenAtParsed = runCatching { OffsetDateTime.parse(part.value) }.getOrNull()
                            }
                        }
                        is PartData.FileItem -> {
                            val name = part.originalFileName ?: "photo.jpg"
                            fileExt = name.substringAfterLast('.', "jpg").lowercase()
                            fileBytes = part.streamProvider().readBytes()
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                // Проверки
                if (fileBytes == null) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "file_required")
                    )
                }
                val kind = when (type?.lowercase()) {
                    "before" -> "BEFORE"
                    "after" -> "AFTER"
                    "before|after" -> type!!.uppercase() // не попадём, но оставим на всякий случай
                    else -> type
                }
                if (kind !in listOf("BEFORE", "AFTER")) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "type_invalid", "detail" to "use BEFORE or AFTER (or kind=before|after)")
                    )
                }

                // Ограничим размер файла (например, до 15 МБ)
                val maxBytes = 15 * 1024 * 1024
                if (fileBytes!!.size > maxBytes) {
                    return@post call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "file_too_large", "limit" to maxBytes))
                }

                // Сохраняем файл на диск (минимальный вариант)
                val key = "${UUID.randomUUID()}.$fileExt"
                val dir = File("uploads/projects/$projectId").apply { mkdirs() }
                val file = File(dir, key)
                try {
                    file.writeBytes(fileBytes!!)
                } catch (t: Throwable) {
                    call.application.log.error("Failed to write photo to disk", t)
                    return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "disk_write_failed"))
                }

                val storageKey = "projects/$projectId/$key"
                val publicUrl = "/files/projects/$projectId/$key" // если раздаёшь через StaticFiles

                // MD5
                val md5 = try { MessageDigest.getInstance("MD5").digest(fileBytes) } catch (_: Throwable) { null }

                // Вставляем метаданные
                val saved = transaction {
                    val newId = UUID.randomUUID()
                    WorkPhotos.insert { ins ->
                        ins[id] = newId
                        ins[WorkPhotos.companyId] = companyId
                        ins[WorkPhotos.projectId] = projectId
                        ins[WorkPhotos.userId] = userId
                        ins[WorkPhotos.type] = kind!!
                        ins[WorkPhotos.caption] = caption
                        ins[WorkPhotos.status] = 0 // pending
                        ins[WorkPhotos.storageKey] = storageKey
                        ins[WorkPhotos.url] = publicUrl
                        ins[WorkPhotos.sizeBytes] = fileBytes!!.size.toLong()
                        if (md5 != null) ins[WorkPhotos.checksumMd5] = md5
                        ins[WorkPhotos.takenAt] = takenAtParsed?.toLocalDateTime()
                        // created_at — БД сама заполнит default now()
                    }
                    WorkPhotos
                        .slice(
                            WorkPhotos.id, WorkPhotos.type, WorkPhotos.url, WorkPhotos.caption,
                            WorkPhotos.status, WorkPhotos.createdAt, WorkPhotos.takenAt
                        )
                        .select { (WorkPhotos.projectId eq projectId) and (WorkPhotos.userId eq userId) and (WorkPhotos.storageKey eq storageKey) }
                        .orderBy(WorkPhotos.createdAt to SortOrder.DESC)
                        .limit(1)
                        .map { row ->
                            WorkPhotoDTO(
                                id = row[WorkPhotos.id].toString(),
                                type = row[WorkPhotos.type],
                                url = row[WorkPhotos.url],
                                caption = row[WorkPhotos.caption],
                                status = row[WorkPhotos.status].toInt(),
                                createdAt = row[WorkPhotos.createdAt].toString(),
                                takenAt = row[WorkPhotos.takenAt]?.toString()
                            )
                        }
                        .single()
                }

                call.respond(HttpStatusCode.OK, saved)
            }

            // Альтернативная форма: POST /api/work-photos/upload (projectId в форме или query)
            post("/upload") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val userId = readUserId(principal)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no_user_in_token"))

                var projectIdFromForm: Int? = null
                var type: String? = null
                var caption: String? = null
                var takenAtParsed: OffsetDateTime? = null
                var fileBytes: ByteArray? = null
                var fileExt = "jpg"

                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> when (part.name) {
                            "projectId", "project_id", "project" -> projectIdFromForm = part.value.toIntOrNull()
                            "type", "kind" -> type = part.value.trim().uppercase()
                            "caption" -> caption = part.value
                            "takenAt" -> takenAtParsed = runCatching { OffsetDateTime.parse(part.value) }.getOrNull()
                        }
                        is PartData.FileItem -> {
                            val name = part.originalFileName ?: "photo.jpg"
                            fileExt = name.substringAfterLast('.', "jpg").lowercase()
                            fileBytes = part.streamProvider().readBytes()
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                val projectId = projectIdFromForm ?: call.request.queryParameters["projectId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "projectId_required"))

                // Проверка членства
                val allowedCompany = transaction {
                    Projects
                        .slice(Projects.companyId)
                        .select { Projects.id eq projectId }
                        .limit(1)
                        .singleOrNull()
                        ?.get(Projects.companyId)
                } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "project_not_found"))

                val isMember = transaction {
                    ProjectMembers
                        .slice(ProjectMembers.userId)
                        .select { (ProjectMembers.projectId eq projectId) and (ProjectMembers.userId eq userId) }
                        .limit(1)
                        .any()
                }
                if (!isMember) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_project_member"))

                // Нормализация kind/type
                val kind = when (type?.lowercase()) {
                    "before" -> "BEFORE"
                    "after" -> "AFTER"
                    else -> type
                }
                if (fileBytes == null || kind !in listOf("BEFORE", "AFTER")) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "file_and_type_required"))
                }

                val maxBytes = 15 * 1024 * 1024
                if (fileBytes!!.size > maxBytes) return@post call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "file_too_large", "limit" to maxBytes))

                val key = "${UUID.randomUUID()}.$fileExt"
                val dir = File("uploads/projects/$projectId").apply { mkdirs() }
                val file = File(dir, key)
                try { file.writeBytes(fileBytes!!) } catch (t: Throwable) {
                    call.application.log.error("Failed to write photo to disk", t)
                    return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "disk_write_failed"))
                }

                val storageKey = "projects/$projectId/$key"
                val publicUrl = "/files/projects/$projectId/$key"
                val md5 = try { MessageDigest.getInstance("MD5").digest(fileBytes) } catch (_: Throwable) { null }

                val saved = transaction {
                    val newId = UUID.randomUUID()
                    WorkPhotos.insert { ins ->
                        ins[id] = newId
                        ins[WorkPhotos.companyId] = allowedCompany
                        ins[WorkPhotos.projectId] = projectId
                        ins[WorkPhotos.userId] = userId
                        ins[WorkPhotos.type] = kind!!
                        ins[WorkPhotos.caption] = caption
                        ins[WorkPhotos.status] = 0
                        ins[WorkPhotos.storageKey] = storageKey
                        ins[WorkPhotos.url] = publicUrl
                        ins[WorkPhotos.sizeBytes] = fileBytes!!.size.toLong()
                        if (md5 != null) ins[WorkPhotos.checksumMd5] = md5
                        ins[WorkPhotos.takenAt] = takenAtParsed?.toLocalDateTime()
                    }
                    WorkPhotos
                        .slice(
                            WorkPhotos.id, WorkPhotos.type, WorkPhotos.url, WorkPhotos.caption,
                            WorkPhotos.status, WorkPhotos.createdAt, WorkPhotos.takenAt
                        )
                        .select { (WorkPhotos.projectId eq projectId) and (WorkPhotos.userId eq userId) and (WorkPhotos.storageKey eq storageKey) }
                        .orderBy(WorkPhotos.createdAt to SortOrder.DESC)
                        .limit(1)
                        .map { row ->
                            WorkPhotoDTO(
                                id = row[WorkPhotos.id].toString(),
                                type = row[WorkPhotos.type],
                                url = row[WorkPhotos.url],
                                caption = row[WorkPhotos.caption],
                                status = row[WorkPhotos.status].toInt(),
                                createdAt = row[WorkPhotos.createdAt].toString(),
                                takenAt = row[WorkPhotos.takenAt]?.toString()
                            )
                        }
                        .single()
                }
                call.respond(HttpStatusCode.OK, saved)
            }

            /**
             * GET /api/work-photos/{projectId}
             * Вернёт последние фото проекта (пользователь должен быть членом проекта).
             * Параметры: ?type=BEFORE|AFTER (опц.), ?limit=50 (опц.)
             */
            get("/{projectId}") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val userId = readUserId(principal)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no_user_in_token"))
                val projectId = call.parameters["projectId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "projectId_required"))
                val filterTypeRaw = call.request.queryParameters["type"]?.trim()
                val filterType = filterTypeRaw?.uppercase()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100

                val allowed = transaction {
                    ProjectMembers
                        .slice(ProjectMembers.userId)
                        .select { (ProjectMembers.projectId eq projectId) and (ProjectMembers.userId eq userId) }
                        .limit(1)
                        .any()
                }
                if (!allowed) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_project_member"))

                val list = transaction {
                    var q = WorkPhotos
                        .slice(
                            WorkPhotos.id, WorkPhotos.type, WorkPhotos.url, WorkPhotos.caption,
                            WorkPhotos.status, WorkPhotos.createdAt, WorkPhotos.takenAt
                        )
                        .select { WorkPhotos.projectId eq projectId }

                    if (filterType == "BEFORE" || filterType == "AFTER") {
                        q = q.andWhere { WorkPhotos.type eq filterType }
                    }

                    q.orderBy(WorkPhotos.createdAt to SortOrder.DESC)
                        .limit(limit)
                        .map { row ->
                            WorkPhotoDTO(
                                id = row[WorkPhotos.id].toString(),
                                type = row[WorkPhotos.type],
                                url = row[WorkPhotos.url],
                                caption = row[WorkPhotos.caption],
                                status = row[WorkPhotos.status].toInt(),
                                createdAt = row[WorkPhotos.createdAt].toString(),
                                takenAt = row[WorkPhotos.takenAt]?.toString()
                            )
                        }
                }

                call.respond(HttpStatusCode.OK, list)
            }
        }
    }
}

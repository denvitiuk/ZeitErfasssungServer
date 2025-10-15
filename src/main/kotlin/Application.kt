package com.yourcompany.zeiterfassung

import io.github.cdimascio.dotenv.dotenv
import com.twilio.Twilio
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import java.util.concurrent.TimeUnit

import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.http.content.*
import java.io.File

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.Json

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm

import com.yourcompany.zeiterfassung.db.configureDatabases
import com.yourcompany.zeiterfassung.routes.*
import com.yourcompany.zeiterfassung.service.AccountDeletionService

// --- Document Flow imports ---
import com.yourcompany.zeiterfassung.routes.registerDocumentFlowRoutes
import com.yourcompany.zeiterfassung.routes.DocumentTemplateStorage
import com.yourcompany.zeiterfassung.routes.DocumentRequestService
import com.yourcompany.zeiterfassung.routes.DocumentUploadService
import com.yourcompany.zeiterfassung.routes.TemplateDTO
import com.yourcompany.zeiterfassung.routes.TemplateQuery
import com.yourcompany.zeiterfassung.routes.RequestDTO
import com.yourcompany.zeiterfassung.routes.SetStatusPayload
import com.yourcompany.zeiterfassung.routes.CreateRequestPayload
import com.yourcompany.zeiterfassung.routes.LeaveBalanceDTO
import com.yourcompany.zeiterfassung.routes.PresignRequest
import com.yourcompany.zeiterfassung.routes.PresignResponse
import com.yourcompany.zeiterfassung.routes.RequestStatus
import com.yourcompany.zeiterfassung.routes.DownloadedObject

import com.yourcompany.zeiterfassung.service.TemplateService
import com.yourcompany.zeiterfassung.service.pg.TemplateStoragePg
import com.yourcompany.zeiterfassung.service.pg.DocumentRequestServicePg
import com.yourcompany.zeiterfassung.service.pg.DocumentUploadServicePg
import com.yourcompany.zeiterfassung.service.RequestService
import javax.sql.DataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import java.util.TimeZone
import io.ktor.server.application.ApplicationStopped

// In-memory cache for phone verification codes (5-minute TTL)
val verificationCodeCache: Cache<String, String> = Caffeine.newBuilder()
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .build()

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    // 1. JSON serialization
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
        })
    }

    // 2. CORS
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Put)
        allowCredentials = true
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    // ✅ 3. Error handling
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "Unkorrekter Antrag"))
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Unbekannter Fehler"))
            )
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "Bitte geben Sie das richtige Passwort oder die richtige E-Mail-Adresse ein.")
            )
        }
    }

    // 4. Load environment variables and Twilio configuration
    val env = dotenv {
        ignoreIfMalformed = true
        ignoreIfMissing   = true
    }
    val config = environment.config
    val twilioSid   = config.propertyOrNull("twilio.accountSid")?.getString()
        ?: env["TWILIO_SID"] ?: error("TWILIO_SID is not configured")
    val verifyServiceSid = env["TWILIO_VERIFY_SERVICE_SID"]
        ?: error("TWILIO_VERIFY_SERVICE_SID is not configured")
    val twilioToken = config.propertyOrNull("twilio.authToken")?.getString()
        ?: env["TWILIO_TOKEN"] ?: error("TWILIO_TOKEN is not configured")
    val twilioFrom  = config.propertyOrNull("twilio.fromNumber")?.getString()
        ?: env["TWILIO_PHONE_NUMBER"] ?: error("TWILIO_PHONE_NUMBER is not configured")

    Twilio.init(twilioSid, twilioToken)

    // 5. JWT authentication
    install(Authentication) {
        jwt("bearerAuth") {
            val cfg = this@module.environment.config
            val jwtSecret = cfg.propertyOrNull("ktor.jwt.secret")?.getString()
                ?: env["JWT_SECRET"] ?: "dev-secret"
            val jwtIssuer = cfg.propertyOrNull("ktor.jwt.issuer")?.getString()
                ?: env["JWT_ISSUER"] ?: "dev-issuer"
            val jwtAudience = cfg.propertyOrNull("ktor.jwt.audience")?.getString()
                ?: env["JWT_AUDIENCE"] ?: "dev-audience"
            realm = cfg.propertyOrNull("ktor.jwt.realm")?.getString()
                ?: env["JWT_REALM"] ?: "zeiterfassung"

            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withIssuer(jwtIssuer)
                    .withAudience(jwtAudience)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("id").asString() != null)
                    JWTPrincipal(credential.payload)
                else null
            }
        }
    }

    // 6. Database initialization
    configureDatabases()

    // --- Document Flow stubs (temporary wiring) ---
    val templateStorageStub = object : DocumentTemplateStorage {
        override suspend fun listTemplates(companyId: Long?, query: TemplateQuery): List<TemplateDTO> = emptyList()
        override suspend fun getTemplate(id: Long): TemplateDTO? = null
        override suspend fun upsertTemplate(meta: TemplateDTO): TemplateDTO = meta
    }

    val requestServiceStub = object : DocumentRequestService {
        override suspend fun create(
            userId: Long,
            companyId: Long,
            payload: CreateRequestPayload
        ): RequestDTO {
            throw UnsupportedOperationException("create: not wired yet (stub)")
        }

        override suspend fun listOwn(userId: Long): List<RequestDTO> {
            return emptyList()
        }

        override suspend fun listForCompany(
            companyId: Long,
            status: RequestStatus?
        ): List<RequestDTO> {
            return emptyList()
        }

        override suspend fun setStatus(
            adminId: Long,
            companyId: Long,
            requestId: Long,
            payload: SetStatusPayload
        ): RequestDTO {
            throw UnsupportedOperationException("setStatus: not wired yet (stub)")
        }

        override suspend fun leaveBalance(userId: Long): LeaveBalanceDTO {
            // Return zeros in stub so UI can render without crashing
            return LeaveBalanceDTO(0.0, 0.0, 0.0, 0.0)
        }


        override suspend fun adminLeaveBalance(targetUserId: Long, year: Int): LeaveBalanceDTO {
            return LeaveBalanceDTO(0.0, 0.0, 0.0, 0.0)
        }

        override suspend fun adjustLeaveEntitlement(
            adminId: Long,
            companyId: Long,
            targetUserId: Long,
            year: Int,
            deltaDays: Int,
            reason: String?
        ): LeaveBalanceDTO {
            throw UnsupportedOperationException("adjustLeaveEntitlement: not wired yet (stub)")
        }
    }

    val uploadServiceStub = object : DocumentUploadService {
        override suspend fun presign(userId: Long, companyId: Long?, req: PresignRequest): PresignResponse {
            throw UnsupportedOperationException("presign uploads: not wired yet (STORAGE_PROVIDER=pg)")
        }
        override suspend fun downloadPgObject(id: Long): DownloadedObject? {
            // Stub implementation for compilation when STORAGE_PROVIDER=pg but DS is not wired
            return null
        }
    }
    // --- End stubs ---

    // Storage provider flag (pg by default; later can be switched to s3 without frontend changes)
    val storageProvider = (env["STORAGE_PROVIDER"] ?: "pg").lowercase()
    environment.log.info("Storage provider: $storageProvider")

    val triple: Triple<DocumentTemplateStorage, DocumentRequestService, DocumentUploadService> =
        if (storageProvider == "pg") {
            val ds = try {
                buildHikariFromEnv(env)
            } catch (t: Throwable) {
                environment.log.error("Failed to init Hikari DataSource for Document Flow, falling back to stubs", t)
                null
            }
            if (ds != null) {
                val tpl = TemplateService(TemplateStoragePg(ds))
                val req = RequestService(DocumentRequestServicePg(ds))
                val upl = DocumentUploadServicePg()
                Triple(tpl, req, upl)
            } else {
                Triple(templateStorageStub, requestServiceStub, uploadServiceStub)
            }
        } else {
            // future: s3 wiring
            Triple(templateStorageStub, requestServiceStub, uploadServiceStub)
        }

    val (templateStorageImpl, requestServiceImpl, uploadServiceImpl) = triple

    // 7. Quartz scheduler for exact-time proof (configurable time via EXACT_PROOF_TIME=HH:mm)
    val scheduler = StdSchedulerFactory.getDefaultScheduler().apply { start() }

    val timeStr = env["EXACT_PROOF_TIME"] ?: "09:43"
    val (proofHour, proofMinute) = timeStr.split(":").let {
        val h = it.getOrNull(0)?.toIntOrNull() ?: 9
        val m = it.getOrNull(1)?.toIntOrNull() ?: 43
        h to m
    }
    val berlinTz = TimeZone.getTimeZone("Europe/Berlin")

    scheduler.scheduleJob(
        JobBuilder.newJob(ExactProofJob::class.java)
            .withIdentity("exactProofJob", "proofs")
            .build(),
        TriggerBuilder.newTrigger()
            .withSchedule(
                CronScheduleBuilder.dailyAtHourAndMinute(proofHour, proofMinute)
                    .inTimeZone(berlinTz)
            )
            .build()
    )

    // Graceful shutdown for Quartz
    environment.monitor.subscribe(ApplicationStopped) {
        scheduler.shutdown(true)
    }

    // Account deletion service (for in‑app account removal)
    val appBaseUrl = environment.config.propertyOrNull("app.baseUrl")?.getString()
        ?: env["APP_BASE_URL"] ?: "https://ratty-marian-denvitiuk-7c71a36f.koyeb.app"
    val deletionService = AccountDeletionService(appBaseUrl)

    // 8. Routing
    routing {
        passwordResetRoutes(twilioFrom, env)
        authRoutes(twilioFrom, verifyServiceSid)

        // Public static file serving for uploaded images (before/after)
        staticFiles("/files", File("uploads"))

        supportAutoReplyRoutes(env)

        authenticate("bearerAuth") {
            qrRoutes()
            scanRoutes()
            logsRoutes()
            proofsRoutes()
            pauseRoutes()
            deviceTokenRoutes()
            adminInviteRoutes(env)
            companiesRoutes()
            projectsRoutes()
            workPhotoRoutes()
            timesheetRoutes()
            companyMonthsRoutes()
            companyTimesheetRoutes()
            registerEntitlementsRoutes()
            accountDeletionRoutes(deletionService)
            // Document Flow
            registerDocumentFlowRoutes(
                templateStorage = templateStorageImpl,
                requestService = requestServiceImpl,
                uploadService = uploadServiceImpl
            )



        }
    }
}

/**
 * Quartz Job that inserts a new Proof record and sends a push notification
 * to the device at the exact scheduled time.
 */
class ExactProofJob : Job {
    override fun execute(context: JobExecutionContext) {
        // TODO: Insert a new record into Proofs table with responded = false,
        // then send APNs/FCM push containing the new proofId in userInfo.
    }
}

private fun buildHikariFromEnv(env: io.github.cdimascio.dotenv.Dotenv): DataSource {
    val jdbcUrl = env["JDBC_DATABASE_URL"]
        ?: env["JDBC_DATABASE_URL_NEON"]
        ?: error("JDBC_DATABASE_URL or JDBC_DATABASE_URL_NEON must be set")
    val user = env["DB_USER"] ?: env["DB_USER_NEON"] ?: error("DB_USER/DB_USER_NEON must be set")
    val pass = env["DB_PASSWORD"] ?: env["DB_PASSWORD_NEON"] ?: error("DB_PASSWORD/DB_PASSWORD_NEON must be set")
    val maxPool = (env["DB_MAX_POOL"] ?: env["DB_MAX_POOL_NEON"] ?: "10").toInt()

    val cfg = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = user
        this.password = pass
        this.maximumPoolSize = maxPool
        this.isAutoCommit = true
    }
    return HikariDataSource(cfg)
}
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

import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import java.util.TimeZone

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
        allowCredentials = true
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    // ✅ 3. Error handling
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "Некорректный запрос"))
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Неизвестная ошибка"))
            )
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "Authorization failed. Please provide a valid token.")
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
            realm = config.propertyOrNull("ktor.jwt.realm")?.getString()
                ?: env["JWT_REALM"] ?: error("ktor.jwt.realm is not configured")

            val jwtConfig = this@module.environment.config.config("ktor.jwt")
            val secret = jwtConfig.property("secret").getString()
            val issuer = jwtConfig.property("issuer").getString()
            val audience = jwtConfig.property("audience").getString()

            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withIssuer(issuer)
                    .withAudience(audience)
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

    // 7. Quartz scheduler for exact-time proof
    val scheduler = StdSchedulerFactory.getDefaultScheduler().apply { start() }

    scheduler.scheduleJob(
        JobBuilder.newJob(ExactProofJob::class.java)
            .withIdentity("exactProofJob", "proofs")
            .build(),
        TriggerBuilder.newTrigger()
            .withSchedule(
                CronScheduleBuilder.dailyAtHourAndMinute(9, 43)
                    .inTimeZone(TimeZone.getTimeZone("Europe/Berlin"))
            )
            .build()
    )

    // 8. Routing
    routing {
        passwordResetRoutes(twilioFrom, env)
        authRoutes(twilioFrom, verifyServiceSid)

        // Public static file serving for uploaded images (before/after)
        staticFiles("/files", File("uploads"))

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
package com.yourcompany.zeiterfassung.push

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import io.github.cdimascio.dotenv.Dotenv
import org.slf4j.LoggerFactory
import java.io.File

object ApnsClientFactory {
    private val logger = LoggerFactory.getLogger(ApnsClientFactory::class.java)
    private val dotenv = Dotenv.load()

    private val p8Path = dotenv["APNS_P8_PATH"] ?: error("APNS_P8_PATH env var not set")
    private val teamId = dotenv["APNS_TEAM_ID"] ?: error("APNS_TEAM_ID env var not set")
    private val keyId = dotenv["APNS_KEY_ID"] ?: error("APNS_KEY_ID env var not set")
    private val useProduction = dotenv["APNS_USE_PRODUCTION"]?.toBoolean() ?: false

    private val signingKey: ApnsSigningKey = try {
        ApnsSigningKey.loadFromPkcs8File(File(p8Path), teamId, keyId).also {
            logger.info("✅ APNs signing key loaded from $p8Path")
        }
    } catch (e: Exception) {
        logger.error("❌ Failed to load APNs signing key from $p8Path", e)
        throw e
    }

    val client: ApnsClient by lazy {
        val server = if (useProduction)
            ApnsClientBuilder.PRODUCTION_APNS_HOST
        else
            ApnsClientBuilder.DEVELOPMENT_APNS_HOST

        ApnsClientBuilder()
            .setApnsServer(server)
            .setSigningKey(signingKey)
            .build()
            .also { logger.info("✅ APNs client initialized with server $server") }
    }
}

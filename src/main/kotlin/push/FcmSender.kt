package com.yourcompany.zeiterfassung.push

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.await
import org.slf4j.LoggerFactory

object FcmSender {

    private val logger = LoggerFactory.getLogger(FcmSender::class.java)

    suspend fun send(fcmToken: String, data: Map<String, String>): Boolean {
        return runCatching {
            val message = buildMessage(fcmToken, data)
            val future: ListenableFuture<String> =
                FirebaseMessaging.getInstance().sendAsync(message) as ListenableFuture<String>
            val response = future.await()
            logger.info("✅ FCM message sent: $response")
            true
        }.onFailure {
            logger.error("❌ FCM message failed", it)
        }.getOrDefault(false)
    }

    private fun buildMessage(token: String, data: Map<String, String>): Message =
        Message.builder()
            .setToken(token)
            .putAllData(data)
            .build()
}

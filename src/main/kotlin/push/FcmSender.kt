package com.yourcompany.zeiterfassung.push

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.Notification
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import org.slf4j.LoggerFactory

object FcmSender {

    private val logger = LoggerFactory.getLogger(FcmSender::class.java)

    fun send(fcmToken: String, data: Map<String, String>): Boolean {
        return runCatching {
            val message = buildMessage(fcmToken, data)
            val response = FirebaseMessaging.getInstance().sendAsync(message).get()
            logger.info("✅ FCM message sent: $response")
            true
        }.onFailure { throwable ->
            if (throwable is FirebaseMessagingException) {
                val errorCode = throwable.errorCode
                if (errorCode?.name == "REGISTRATION_TOKEN_NOT_REGISTERED") {
                    logger.warn("⚠️ FCM token no longer registered: $fcmToken")
                    // TODO: удалить токен из базы
                }
                logger.error("❌ FCM failed with error code: $errorCode", throwable)
            } else {
                logger.error("❌ FCM message failed", throwable)
            }
        }.getOrDefault(false)
    }


    private fun buildMessage(token: String, data: Map<String, String>): Message {
        val builder = Message.builder()
            .setToken(token)
            .putAllData(data)
            .setAndroidConfig(
                AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setTtl(5 * 60 * 1000) // 5 минут в мс
                    .build()
            )

        data["title"]?.let { title ->
            builder.setNotification(
                Notification.builder()
                    .setTitle(title)
                    .setBody(data["body"] ?: "")
                    .build()
            )
        }

        return builder.build()
    }
}

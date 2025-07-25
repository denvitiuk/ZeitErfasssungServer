// ApnsSender.kt
package com.yourcompany.zeiterfassung.push

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import com.eatthepath.pushy.apns.util.TokenUtil
import kotlinx.coroutines.future.await

suspend fun sendApns(deviceToken: String, payloadJson: String) {
    val notification = SimpleApnsPushNotification(
        TokenUtil.sanitizeTokenString(deviceToken),
        "com.yourcompany.zeiterfassung",  // ваш bundle identifier
        payloadJson
    )
    ApnsClientFactory.client.sendNotification(notification).await()
}
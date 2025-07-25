package com.yourcompany.zeiterfassung.push

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.auth.ApnsSigningKey

import java.io.File
import java.io.FileInputStream

// Load PKCS#8 signing key from .p8 file
val signingKey: ApnsSigningKey = ApnsSigningKey.loadFromPkcs8File(
    File("/path/to/AuthKey_ABC123DEF.p8"),
    "TEAMID",
    "KEYID"
)


object ApnsClientFactory {
    val client: ApnsClient = ApnsClientBuilder()
        .setApnsServer(ApnsClientBuilder.DEVELOPMENT_APNS_HOST)
        .setSigningKey(signingKey)
        .build()
}
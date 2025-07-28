// Hardcoded version variables
val kotlin_version = "2.1.10"
val ktor_version = "3.2.1"
val logback_version = "1.5.16"
val postgres_version = "42.6.0"
val h2_version = "2.1.214"
val exposed_version = "0.41.1"
val hikari_version   = "5.0.1"
val flyway_version   = "9.22.0"

plugins {
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10"
    id("io.ktor.plugin") version "3.2.1"
    application
}

tasks.test {
    environment("JWT_REALM", "zeiterfassung")
}

group = "com.yourcompany.zeiterfassung"
version = "0.0.1"

application {
    // Ktor EngineMain
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    google()
    mavenCentral()
    maven { url = uri("https://packages.confluent.io/maven/") }
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-openapi-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-config-yaml-jvm:$ktor_version")

    // Jackson (для поддержки JWT от Auth0)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")



    // БД-драйверы
    implementation("org.postgresql:postgresql:$postgres_version")
    implementation("com.h2database:h2:$h2_version")

    // Логирование
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // Ktor client (если нужен)
    implementation("io.ktor:ktor-client-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-apache-jvm:$ktor_version")

    // Тесты
    testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")

    // In-memory cache for codes
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

// Twilio
    implementation("com.twilio.sdk:twilio:8.33.0")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

    // 1) HikariCP
    implementation("com.zaxxer:HikariCP:$hikari_version")

    // 2) Flyway
    implementation("org.flywaydb:flyway-core:$flyway_version")

    // 3) Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")

    implementation("at.favre.lib:bcrypt:0.9.0")
    // jBCrypt for password hashing (used in password reset flow)
    implementation("org.mindrot:jbcrypt:0.4")
    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.0")
    implementation("com.google.zxing:javase:3.5.0")

    implementation("io.ktor:ktor-server-status-pages-jvm:${ktor_version}")


    implementation("org.quartz-scheduler:quartz:2.3.2")
    implementation("com.eatthepath:pushy:0.14.0")        // APNs client
    implementation("com.google.firebase:firebase-admin:8.2.0") // FCM для Android
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.7.3")
    implementation("com.google.firebase:firebase-messaging:23.0.6")
    implementation("com.sun.mail:jakarta.mail:1.6.7")
    implementation("at.favre.lib:bcrypt:0.9.0")





}

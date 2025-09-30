// Hardcoded version variables
val kotlin_version = "2.1.21"
val ktor_version = "3.2.1"
val logback_version = "1.5.16"
val postgres_version = "42.6.0"
val h2_version = "2.1.214"
val exposed_version = "0.41.1"
val hikari_version   = "5.0.1"
val flyway_version   = "9.22.0"
val stripe_version  = "29.5.0"



configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.gradleup.shadow") {
            useTarget("com.github.johnrengelman:shadow:8.1.1")
        }
    }
}


plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21"
    id("io.ktor.plugin") version "3.2.1"

    application
}



// Heroku/Koyeb: buildpack ищет stage; собираем fat‑jar, это надёжнее installDist
tasks.register("stage") {
    dependsOn("clean", "shadowJar")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.test {
    environment("JWT_REALM", "zeiterfassung")
}

group = "com.yourcompany.zeiterfassung"
version = "0.0.1"

application {
    // Ktor EngineMain
    mainClass.set("io.ktor.server.netty.EngineMain")
    applicationName = "zeiterfassung-server"
}

// ShadowJar configuration (без import, через FQCN)
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    // Добавляем Main‑Class в манифест, чтобы `java -jar` работал
    manifest {
        attributes["Main-Class"] = "io.ktor.server.netty.EngineMain"
    }
    // Сливаем service‑файлы (Netty/SLF4J и пр.)
    mergeServiceFiles()
    archiveBaseName.set("zeiterfassung-server")
    archiveClassifier.set("all")
}

distributions {
    main {
        distributionBaseName.set("zeiterfassung-server")
    }
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
    implementation("com.auth0:java-jwt:4.5.0")
    implementation("com.auth0:jwks-rsa:0.22.1")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-openapi-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-config-yaml-jvm:$ktor_version")

    // Jackson (для Auth0 JWT и JSON-инфраструктуры)
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.17.2"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

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

    implementation("com.stripe:stripe-java:$stripe_version")

    implementation("at.favre.lib:bcrypt:0.9.0")
    // jBCrypt for password hashing (used in password reset flow)
    implementation("org.mindrot:jbcrypt:0.4")
    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.0")
    implementation("com.google.zxing:javase:3.5.0")

    implementation("io.ktor:ktor-server-status-pages-jvm:${ktor_version}")

    implementation("org.quartz-scheduler:quartz:2.3.2")
    implementation("com.eatthepath:pushy:0.14.2")        // APNs client
    implementation("com.google.firebase:firebase-admin:8.2.0") // FCM для Android
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")
    implementation("com.sun.mail:jakarta.mail:1.6.7")
}

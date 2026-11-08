import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.mcp.kotlin.sdk.server)
    api(libs.ktor.server.core)
    api(libs.ktor.server.cio)
    api(libs.ktor.server.sse)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.mcp.kotlin.sdk.testing)
    testImplementation(libs.ktor.server.test.host)
}

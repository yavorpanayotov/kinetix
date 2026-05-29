plugins {
    id("kinetix.kotlin-library")
    id("kinetix.kotlin-testing")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    compileOnly(libs.kafka.clients)
    compileOnly(libs.hikari)
    compileOnly(libs.flyway.core)
    compileOnly(libs.logback.classic)
    compileOnly(libs.opentelemetry.sdk.autoconfigure)
    compileOnly(libs.opentelemetry.logback.appender)
    // OTel foundation — compileOnly so services supply their own runtime versions
    compileOnly(libs.opentelemetry.sdk)
    compileOnly(libs.opentelemetry.exporter.otlp)
    compileOnly(libs.opentelemetry.grpc)
    compileOnly(libs.ktor.client.core)
    compileOnly(libs.grpc.stub)
    compileOnly(libs.grpc.netty)

    testImplementation(libs.kafka.clients)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.opentelemetry.logback.appender)
    testImplementation(libs.logback.classic)
    testImplementation(libs.opentelemetry.exporter.otlp)
    testImplementation(libs.opentelemetry.grpc)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.grpc.netty)
    testImplementation(libs.grpc.stub)
}

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
    // OTel api exposed transitively: publisher constructors and the OtelHttpServerPlugin
    // config reference OpenTelemetry in their public signatures, so consumers need it on
    // their compile classpath. Sdk/exporter stay compileOnly — services supply runtime.
    api(libs.opentelemetry.api)
    compileOnly(libs.opentelemetry.sdk)
    compileOnly(libs.opentelemetry.exporter.otlp)
    compileOnly(libs.opentelemetry.grpc)
    compileOnly(libs.ktor.client.core)
    compileOnly(libs.ktor.server.core)
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
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.grpc.netty)
    testImplementation(libs.grpc.stub)
}

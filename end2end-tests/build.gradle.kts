plugins {
    id("kinetix.kotlin-common")
    id("kinetix.kotlin-testing")
}

tasks.named<Test>("test") {
    filter {
        isFailOnNoMatchingTests = false
    }
}

dependencies {
    testImplementation(project(":common"))
    testImplementation(project(":position-service"))
    testImplementation(project(":fix-gateway"))
    testImplementation(project(":audit-service"))
    testImplementation(project(":risk-orchestrator"))
    testImplementation(project(":regulatory-service"))
    testImplementation(project(":reference-data-service"))
    testImplementation(project(":price-service"))
    testImplementation(project(":correlation-service"))
    testImplementation(project(":volatility-service"))
    testImplementation(project(":notification-service"))
    testImplementation(project(":test-support"))
    testImplementation(libs.bundles.exposed)
    testImplementation(libs.bundles.database)
    testImplementation(libs.bundles.grpc)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.kafka.clients)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.micrometer.prometheus)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.quickfixj.core)
}

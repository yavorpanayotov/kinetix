plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
    id("kinetix.kotlin-mutation")
}

application {
    mainClass.set("com.kinetix.position.ApplicationKt")
}

// Narrow the PIT mutation scope to NettingSetAssigner. The convention
// plugin's default of `com.kinetix.*` would mutation-test the whole
// position-service — far too slow and prone to OOM/hang on the
// property-based and Testcontainers-backed suites here. NettingSetAssigner
// is a single self-contained class with four MockK-backed unit tests
// covering null counterparty, empty-agreements, exception-swallow and
// happy-path branches — enough behaviour for PIT to mutate meaningfully
// while keeping the loop-driven acceptance check fast.
pitest {
    targetClasses.set("com.kinetix.position.service.NettingSetAssigner")
    targetTests.set("com.kinetix.position.service.NettingSetAssignerTest")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":proto"))
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)
    implementation(libs.bundles.grpc)
    implementation(libs.grpc.netty)
    implementation(libs.kafka.clients)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    testImplementation(project(":test-support"))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.ktor.client.mock)
}

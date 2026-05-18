plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
    id("kinetix.kotlin-mutation")
}

application {
    mainClass.set("com.kinetix.risk.ApplicationKt")
}

// Narrow the PIT mutation scope to the mapper package. The convention
// plugin's default of `com.kinetix.*` would mutation-test the whole
// module — feasible on CI but too slow for the loop-driven acceptance
// check on developer workstations. The plumbing-only goal of plan
// item 6.1 is satisfied as long as PIT generates at least one mutation
// and emits `build/reports/pitest/mutations.xml`. Override on the CLI
// with `-Ppitest.targetClasses=...` when investigating wider scope.
pitest {
    targetClasses.set("com.kinetix.risk.mapper.*")
    targetTests.set("com.kinetix.risk.mapper.*Test")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":proto"))
    implementation(libs.bundles.grpc)
    implementation(libs.grpc.netty)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)
    implementation(libs.kafka.clients)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.redis.lettuce)

    testImplementation(project(":test-support"))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.micrometer.prometheus)
}

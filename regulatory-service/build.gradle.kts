plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
    id("kinetix.kotlin-mutation")
}

application {
    mainClass.set("com.kinetix.regulatory.ApplicationKt")
}

// Narrow the PIT mutation scope to BacktestComparisonService. The convention
// plugin's default of `com.kinetix.*` would mutation-test the whole
// regulatory-service — too slow and prone to OOM/hang on the
// Testcontainers-backed integration suites here. BacktestComparisonService
// is a pure-Kotlin diff routine over backtest result records (violation
// count/rate diffs, Kupiec and Christoffersen p-value diffs, traffic-light
// zone change detection) covered by BacktestComparisonServiceTest's eight
// unit tests — enough arithmetic, boolean, and not-found branch logic for
// PIT to mutate meaningfully while keeping the loop-driven acceptance check
// fast. Override on the CLI with `-Ppitest.targetClasses=...` when
// investigating wider scope.
pitest {
    targetClasses.set("com.kinetix.regulatory.service.BacktestComparisonService*")
    targetTests.set("com.kinetix.regulatory.service.BacktestComparisonServiceTest")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kafka.clients)

    testImplementation(project(":test-support"))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.ktor.client.mock)
}

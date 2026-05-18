plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
    id("kinetix.kotlin-mutation")
}

application {
    mainClass.set("com.kinetix.audit.ApplicationKt")
}

// Narrow the PIT mutation scope to AuditHasher. The convention plugin's
// default of `com.kinetix.*` would mutation-test the whole audit-service —
// too slow and prone to OOM/hang on the Testcontainers-backed Kafka and
// Postgres suites here. AuditHasher is the hash-chain core: SHA-256 record
// hashing plus full-chain and incremental verification routines that handle
// null-vs-NULL placeholder branches, empty-list short-circuits, and
// previous-hash/record-hash mismatch detection. It's covered by
// HashChainTest's eight pure-Kotlin unit tests — enough arithmetic, boolean,
// and equality branches for PIT to mutate meaningfully while keeping the
// loop-driven acceptance check fast. Override on the CLI with
// `-Ppitest.targetClasses=...` when investigating wider scope.
pitest {
    targetClasses.set("com.kinetix.audit.persistence.AuditHasher*")
    targetTests.set("com.kinetix.audit.persistence.HashChainTest")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)
    implementation(libs.kafka.clients)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":test-support"))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
}

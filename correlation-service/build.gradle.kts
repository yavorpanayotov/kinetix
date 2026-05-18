plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
    id("kinetix.kotlin-mutation")
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

// Narrow the PIT mutation scope to the validation package. The convention
// plugin's default of `com.kinetix.*` would mutation-test the whole
// correlation-service — too slow and prone to OOM/hang on the
// Testcontainers-backed integration suites here. CorrelationPsdValidator
// is a pure-Kotlin eigenvalue check covered by CorrelationPsdValidatorTest
// — enough arithmetic and branch behaviour for PIT to mutate meaningfully
// while keeping the loop-driven acceptance check fast. Override on the
// CLI with `-Ppitest.targetClasses=...` when investigating wider scope.
pitest {
    targetClasses.set("com.kinetix.correlation.validation.*")
    targetTests.set("com.kinetix.correlation.validation.*")
}

tasks.register<Jar>("fatJar") {
    archiveFileName.set("correlation-service.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "io.ktor.server.netty.EngineMain"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}

dependencies {
    implementation(project(":common"))
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)
    implementation(libs.kafka.clients)
    implementation(libs.redis.lettuce)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":test-support"))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
}

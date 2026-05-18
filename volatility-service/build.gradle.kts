plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
    id("kinetix.kotlin-mutation")
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

// Narrow the PIT mutation scope to VolSurfaceDiff. The convention plugin's
// default of `com.kinetix.*` would mutation-test the whole volatility-service
// — too slow and prone to OOM/hang on the Testcontainers- and property-backed
// suites here. VolSurfaceDiff is a pure-Kotlin bilinear-interpolation routine
// (in log-K / sqrt-T coordinates) covered by VolSurfaceDiffTest's eight unit
// tests across identical-grid, staggered-grid, parallel-shift, off-knot
// interpolation, flat-extrapolation, missing-maturity, single-point and
// sort-order behaviour — enough arithmetic and branch logic for PIT to mutate
// meaningfully while keeping the loop-driven acceptance check fast. Override
// on the CLI with `-Ppitest.targetClasses=...` when investigating wider scope.
pitest {
    targetClasses.set("com.kinetix.volatility.routes.VolSurfaceDiff*")
    targetTests.set("com.kinetix.volatility.routes.VolSurfaceDiffTest")
}

tasks.register<Jar>("fatJar") {
    archiveFileName.set("volatility-service.jar")
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

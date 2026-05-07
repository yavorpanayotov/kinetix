plugins {
    id("kinetix.acceptance-test-conventions")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("kotest-runner-junit5").get())
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "testImplementation"(libs.findLibrary("kotest-property").get())
    "testImplementation"(libs.findLibrary("mockk").get())
    "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("*IntegrationTest")
        excludeTestsMatching("*AcceptanceTest")
        excludeTestsMatching("*End2EndTest")
        excludeTestsMatching("*LoadTest")
    }
}

val testSourceSets = the<JavaPluginExtension>().sourceSets

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests."
    group = "verification"
    testClassesDirs = testSourceSets["test"].output.classesDirs
    classpath = testSourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("*IntegrationTest")
        isFailOnNoMatchingTests = false
    }
}

val acceptanceTest by tasks.registering(Test::class) {
    description = "Runs acceptance tests."
    group = "verification"
    testClassesDirs = testSourceSets["test"].output.classesDirs
    classpath = testSourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("*AcceptanceTest")
        isFailOnNoMatchingTests = false
    }
    dependsOn(tasks.named("verifyAcceptanceTestCompliance"))
}

val end2EndTest by tasks.registering(Test::class) {
    description = "Runs end-to-end tests."
    group = "verification"
    testClassesDirs = testSourceSets["test"].output.classesDirs
    classpath = testSourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("*End2EndTest")
        isFailOnNoMatchingTests = false
    }
}

val loadTest by tasks.registering(Test::class) {
    description = "Runs load / soak tests (e.g. fix-gateway throughput, ADR-0035 §3.13)."
    group = "verification"
    testClassesDirs = testSourceSets["test"].output.classesDirs
    classpath = testSourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("*LoadTest")
        isFailOnNoMatchingTests = false
    }
    // Load tests dominate wall time and need their own forked JVM heap budget.
    maxHeapSize = "2g"
    timeout.set(java.time.Duration.ofMinutes(20))
}

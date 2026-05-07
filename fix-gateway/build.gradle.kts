plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
}

application {
    mainClass.set("com.kinetix.fix.ApplicationKt")
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

    // QuickFIX/J: ADR-0035 phase 2. fix-gateway is the sole consumer.
    implementation(libs.quickfixj.core)
    implementation(libs.quickfixj.messages.fix42)
    implementation(libs.quickfixj.messages.fix44)

    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.micrometer.prometheus)
    testImplementation(libs.kotlinx.coroutines.test)
}

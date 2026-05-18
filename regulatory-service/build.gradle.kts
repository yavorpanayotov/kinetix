plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
}

application {
    mainClass.set("com.kinetix.regulatory.ApplicationKt")
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

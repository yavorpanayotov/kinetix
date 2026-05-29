plugins {
    id("kinetix.kotlin-service")
    id("kinetix.kotlin-testing")
}

application {
    mainClass.set("com.kinetix.gateway.ApplicationKt")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kafka.clients)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.opentelemetry.sdk.testing)
}

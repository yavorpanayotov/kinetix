package com.kinetix.demo

import com.kinetix.demo.config.DemoConfig
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json

private const val DEFAULT_PORT = 8094

fun main(args: Array<String>) {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    val config = DemoConfig.fromEnv()
    if (config.demoMode) {
        log.info("demo mode enabled")
    } else {
        log.info("demo mode disabled — running as no-op")
    }

    install(ContentNegotiation) { json() }

    routing {
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
    }
}

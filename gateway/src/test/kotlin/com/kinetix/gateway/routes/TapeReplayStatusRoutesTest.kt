package com.kinetix.gateway.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

class TapeReplayStatusRoutesTest : FunSpec({

    fun TestApplicationBuilder.installModule(env: Map<String, String>) {
        application {
            install(ContentNegotiation) { json() }
            routing { tapeReplayStatusRoutes { env[it] } }
        }
    }

    test("returns LIVE when DEMO_RESET_TOKEN is absent (production mode)") {
        testApplication {
            installModule(emptyMap())
            val response = client.get("/api/v1/demo/replay-status")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText().contains("\"status\":\"LIVE\"") shouldBe true
        }
    }

    test("returns ACTIVE when demo token present and replay enabled") {
        testApplication {
            installModule(mapOf(
                "DEMO_RESET_TOKEN" to "secret",
                "DEMO_TAPE_REPLAY_ENABLED" to "true",
            ))
            val response = client.get("/api/v1/demo/replay-status")
            response.bodyAsText().contains("\"status\":\"ACTIVE\"") shouldBe true
        }
    }

    test("returns FROZEN when demo token present but replay disabled") {
        testApplication {
            installModule(mapOf(
                "DEMO_RESET_TOKEN" to "secret",
                "DEMO_TAPE_REPLAY_ENABLED" to "false",
            ))
            val response = client.get("/api/v1/demo/replay-status")
            response.bodyAsText().contains("\"status\":\"FROZEN\"") shouldBe true
        }
    }

    test("returns FROZEN when demo token present and replay flag unset (default off)") {
        testApplication {
            installModule(mapOf("DEMO_RESET_TOKEN" to "secret"))
            val response = client.get("/api/v1/demo/replay-status")
            response.bodyAsText().contains("\"status\":\"FROZEN\"") shouldBe true
        }
    }

    test("ignores malformed replay flag and falls back to FROZEN") {
        testApplication {
            installModule(mapOf(
                "DEMO_RESET_TOKEN" to "secret",
                "DEMO_TAPE_REPLAY_ENABLED" to "notabool",
            ))
            val response = client.get("/api/v1/demo/replay-status")
            response.bodyAsText().contains("\"status\":\"FROZEN\"") shouldBe true
        }
    }
})

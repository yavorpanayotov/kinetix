package com.kinetix.gateway.contract

import com.kinetix.gateway.routes.demoAdminRoutes
import com.kinetix.gateway.routes.demoScenarioRoutes
import com.kinetix.gateway.routes.setActiveScenario
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.*

private fun Application.configureDemoAdminWithScenarioRoutes(mockEngine: MockEngine) {
    val client = HttpClient(mockEngine) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
    }
    install(ContentNegotiation) { json() }
    routing {
        demoAdminRoutes(
            httpClient = client,
            positionUrl = "http://position",
            auditUrl = "http://audit",
            riskUrl = "http://risk",
            priceUrl = "http://price",
            ratesUrl = "http://rates",
            volatilityUrl = "http://volatility",
            correlationUrl = "http://correlation",
            referenceDataUrl = "http://reference-data",
            adminKey = "admin-key",
            resetToken = "reset-token",
        )
        demoScenarioRoutes()
    }
}

class GatewayDemoResetScenarioFlagAcceptanceTest : FunSpec({

    beforeTest { setActiveScenario("multi-asset") }

    test("default reset (no scenario param) forwards scenario=multi-asset to every backend") {
        val visitedQueries = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            synchronized(visitedQueries) { visitedQueries.add(request.url.encodedQuery) }
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        testApplication {
            application { configureDemoAdminWithScenarioRoutes(mockEngine) }

            val response = client.post("/api/v1/admin/demo-reset") {
                header("X-Demo-Admin-Key", "admin-key")
            }

            response.status shouldBe HttpStatusCode.OK
            visitedQueries.size shouldBe 8
            visitedQueries.forEach { it shouldBe "scenario=multi-asset" }
        }
    }

    test("scenario=stress is forwarded to every backend and updates the active scenario") {
        val visitedQueries = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            synchronized(visitedQueries) { visitedQueries.add(request.url.encodedQuery) }
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        testApplication {
            application { configureDemoAdminWithScenarioRoutes(mockEngine) }

            val resetResponse = client.post("/api/v1/admin/demo-reset?scenario=stress") {
                header("X-Demo-Admin-Key", "admin-key")
            }

            resetResponse.status shouldBe HttpStatusCode.OK
            visitedQueries.size shouldBe 8
            visitedQueries shouldContainAll listOf("scenario=stress")

            val active = client.get("/api/v1/demo/scenario").bodyAsText()
            active shouldContain "\"scenario\":\"stress\""
        }
    }

    test("equity-ls and options-book are accepted as scenario values") {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        testApplication {
            application { configureDemoAdminWithScenarioRoutes(mockEngine) }

            val equityLs = client.post("/api/v1/admin/demo-reset?scenario=equity-ls") {
                header("X-Demo-Admin-Key", "admin-key")
            }
            equityLs.status shouldBe HttpStatusCode.OK

            val optionsBook = client.post("/api/v1/admin/demo-reset?scenario=options-book") {
                header("X-Demo-Admin-Key", "admin-key")
            }
            optionsBook.status shouldBe HttpStatusCode.OK
        }
    }

    test("unknown scenario returns 400 with errorCode UNKNOWN_SCENARIO and no fan-out") {
        var fanOutCount = 0
        val mockEngine = MockEngine { _ ->
            synchronized(this) { fanOutCount++ }
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        testApplication {
            application { configureDemoAdminWithScenarioRoutes(mockEngine) }

            val response = client.post("/api/v1/admin/demo-reset?scenario=does-not-exist") {
                header("X-Demo-Admin-Key", "admin-key")
            }
            val body = response.bodyAsText()

            response.status shouldBe HttpStatusCode.BadRequest
            body shouldContain "UNKNOWN_SCENARIO"
            body shouldContain "does-not-exist"
            fanOutCount shouldBe 0
        }
    }

    test("regulatory scenario returns 400 with errorCode SCENARIO_NOT_AVAILABLE pre-Gap-4") {
        var fanOutCount = 0
        val mockEngine = MockEngine { _ ->
            synchronized(this) { fanOutCount++ }
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
            )
        }
        testApplication {
            application { configureDemoAdminWithScenarioRoutes(mockEngine) }

            val response = client.post("/api/v1/admin/demo-reset?scenario=regulatory") {
                header("X-Demo-Admin-Key", "admin-key")
            }
            val body = response.bodyAsText()

            response.status shouldBe HttpStatusCode.BadRequest
            body shouldContain "SCENARIO_NOT_AVAILABLE"
            fanOutCount shouldBe 0
        }
    }

    test("admin-key check still wins over scenario validation") {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
            )
        }
        testApplication {
            application { configureDemoAdminWithScenarioRoutes(mockEngine) }

            val response = client.post("/api/v1/admin/demo-reset?scenario=does-not-exist") {
                header("X-Demo-Admin-Key", "wrong")
            }

            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("scenario param is case-insensitive (matches SeedProfile.parse)") {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        testApplication {
            application { configureDemoAdminWithScenarioRoutes(mockEngine) }

            val response = client.post("/api/v1/admin/demo-reset?scenario=Equity-LS") {
                header("X-Demo-Admin-Key", "admin-key")
            }

            response.status shouldBe HttpStatusCode.OK
            val active = client.get("/api/v1/demo/scenario").bodyAsText()
            active shouldContain "\"scenario\":\"equity-ls\""
        }
    }
})

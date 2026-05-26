package com.kinetix.risk.routes

import com.google.protobuf.Timestamp
import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.proto.risk.StressTestRequest
import com.kinetix.proto.risk.StressTestResponse
import com.kinetix.proto.risk.StressTestServiceGrpcKt
import com.kinetix.risk.cache.InMemoryCannedStressCache
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.routes.dtos.CannedStressResultResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json

/**
 * Unit tests for the canned stress-scenario route. Verifies the route:
 *
 *  - POSTs to risk-engine with the supplied scenario name and the book's
 *    current positions,
 *  - caches the resulting delta-PV under the book id,
 *  - returns a minimal [CannedStressResultResponse] body,
 *  - GETs the cached result back, returning 404 when nothing has been seeded.
 */
class CannedStressRoutesTest : FunSpec({

    test("POST runs the named scenario and returns deltaPv + scenario + asOf") {
        val positionProvider = mockk<PositionProvider>()
        coEvery { positionProvider.getPositions(BookId("port-rates")) } returns emptyList<Position>()

        val stressTestStub = mockk<StressTestServiceGrpcKt.StressTestServiceCoroutineStub>()
        val requestSlot = slot<StressTestRequest>()
        coEvery { stressTestStub.runStressTest(capture(requestSlot), any()) } returns
            StressTestResponse.newBuilder()
                .setScenarioName("+100BPS_PARALLEL")
                .setBaseVar(10_000.0)
                .setStressedVar(18_000.0)
                .setPnlImpact(-8_000.0)
                .setCalculatedAt(Timestamp.newBuilder().setSeconds(1_716_768_000L))
                .build()

        val cache = InMemoryCannedStressCache()

        testApplication {
            install(ContentNegotiation) { json() }
            routing {
                cannedStressRoutes(positionProvider, stressTestStub, cache)
            }

            val response = client.post("/api/v1/risk/stress/port-rates/canned/+100BPS_PARALLEL")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.decodeFromString<CannedStressResultResponse>(response.bodyAsText())
            body.bookId shouldBe "port-rates"
            body.scenario shouldBe "+100BPS_PARALLEL"
            body.deltaPv shouldBe "-8000.00"
            body.asOf shouldContain "2024-05-27"
        }

        requestSlot.captured.scenarioName shouldBe "+100BPS_PARALLEL"
        requestSlot.captured.bookId.value shouldBe "port-rates"
    }

    test("POST caches the result so subsequent GET returns it") {
        val positionProvider = mockk<PositionProvider>()
        coEvery { positionProvider.getPositions(BookId("port-cache")) } returns emptyList<Position>()

        val stressTestStub = mockk<StressTestServiceGrpcKt.StressTestServiceCoroutineStub>()
        coEvery { stressTestStub.runStressTest(any(), any()) } returns
            StressTestResponse.newBuilder()
                .setScenarioName("+100BPS_PARALLEL")
                .setPnlImpact(-1_234.56)
                .setCalculatedAt(Timestamp.newBuilder().setSeconds(1_716_768_000L))
                .build()

        val cache = InMemoryCannedStressCache()

        testApplication {
            install(ContentNegotiation) { json() }
            routing {
                cannedStressRoutes(positionProvider, stressTestStub, cache)
            }

            client.post("/api/v1/risk/stress/port-cache/canned/+100BPS_PARALLEL").status shouldBe HttpStatusCode.OK

            val getResponse = client.get("/api/v1/risk/stress/port-cache/canned")
            getResponse.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<CannedStressResultResponse>(getResponse.bodyAsText())
            body.bookId shouldBe "port-cache"
            body.deltaPv shouldBe "-1234.56"
        }
    }

    test("GET returns 404 when no canned result has been cached") {
        val positionProvider = mockk<PositionProvider>()
        val stressTestStub = mockk<StressTestServiceGrpcKt.StressTestServiceCoroutineStub>()
        val cache = InMemoryCannedStressCache()

        testApplication {
            install(ContentNegotiation) { json() }
            routing {
                cannedStressRoutes(positionProvider, stressTestStub, cache)
            }

            val response = client.get("/api/v1/risk/stress/empty-book/canned")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST forwards the book's positions to the stress engine") {
        val positionProvider = mockk<PositionProvider>()
        coEvery { positionProvider.getPositions(BookId("port-positions")) } returns emptyList<Position>()

        val stressTestStub = mockk<StressTestServiceGrpcKt.StressTestServiceCoroutineStub>()
        coEvery { stressTestStub.runStressTest(any(), any()) } returns
            StressTestResponse.newBuilder()
                .setScenarioName("+100BPS_PARALLEL")
                .setPnlImpact(0.0)
                .setCalculatedAt(Timestamp.newBuilder().setSeconds(1_716_768_000L))
                .build()

        val cache = InMemoryCannedStressCache()

        testApplication {
            install(ContentNegotiation) { json() }
            routing {
                cannedStressRoutes(positionProvider, stressTestStub, cache)
            }

            client.post("/api/v1/risk/stress/port-positions/canned/+100BPS_PARALLEL")
        }

        coVerify(exactly = 1) {
            positionProvider.getPositions(BookId("port-positions"))
        }
    }
})

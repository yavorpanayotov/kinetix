package com.kinetix.risk.routes

import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.proto.risk.StressTestRequest
import com.kinetix.proto.risk.StressTestResponse
import com.kinetix.proto.risk.StressTestServiceGrpcKt
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.persistence.LatestStressBatchRepository
import com.kinetix.risk.routes.dtos.BatchScenarioResultDto
import com.kinetix.risk.routes.dtos.BatchStressRunResultResponse
import com.kinetix.risk.service.BatchStressTestService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json

/**
 * Unit tests for persist-and-fetch of the latest batch stress result
 * (issue kx-kjse). The Scenarios tab must populate on cold open without a
 * fresh "Run All Scenarios" click, so:
 *
 *  - POST …/batch persists the computed batch via [LatestStressBatchRepository],
 *  - GET …/batch returns the most recent persisted batch (same DTO shape),
 *    or 404 when nothing has been stored for the book yet.
 */
class BatchStressPersistenceRoutesTest : FunSpec({

    fun stressResponse(scenarioName: String, pnlImpact: Double) =
        StressTestResponse.newBuilder()
            .setScenarioName(scenarioName)
            .setBaseVar(50_000.0)
            .setStressedVar(80_000.0)
            .setPnlImpact(pnlImpact)
            .build()

    test("POST batch persists the computed result for the book") {
        val positionProvider = mockk<PositionProvider>()
        coEvery { positionProvider.getPositions(BookId("port-1")) } returns emptyList<Position>()

        val stressTestStub = mockk<StressTestServiceGrpcKt.StressTestServiceCoroutineStub>()
        coEvery { stressTestStub.runStressTest(any(), any()) } answers {
            val req = firstArg<StressTestRequest>()
            stressResponse(req.scenarioName, -100_000.0)
        }

        val batchService = BatchStressTestService(stressTestStub, positionProvider)

        val store = mutableMapOf<String, BatchStressRunResultResponse>()
        val repository = object : LatestStressBatchRepository {
            override suspend fun save(bookId: BookId, result: BatchStressRunResultResponse) {
                store[bookId.value] = result
            }
            override suspend fun findLatestByBookId(bookId: BookId) = store[bookId.value]
        }

        testApplication {
            install(ContentNegotiation) { json() }
            routing {
                riskRoutes(
                    varCalculationService = mockk(relaxed = true),
                    varCache = mockk(relaxed = true),
                    positionProvider = positionProvider,
                    stressTestStub = stressTestStub,
                    regulatoryStub = mockk(relaxed = true),
                    batchStressTestService = batchService,
                    latestStressBatchRepository = repository,
                )
            }

            val response = client.post("/api/v1/risk/stress/port-1/batch") {
                contentType(ContentType.Application.Json)
                setBody("""{"scenarioNames":["GFC_2008","COVID_2020"]}""")
            }
            response.status shouldBe HttpStatusCode.OK
        }

        store.containsKey("port-1") shouldBe true
        store["port-1"]!!.results.size shouldBe 2
    }

    test("GET batch returns the most recent persisted result") {
        val stored = BatchStressRunResultResponse(
            results = listOf(
                BatchScenarioResultDto("GFC_2008", "50000.00", "80000.00", "-400000.00"),
                BatchScenarioResultDto("COVID_2020", "50000.00", "70000.00", "-150000.00"),
            ),
            failedScenarios = emptyList(),
            worstScenarioName = "GFC_2008",
            worstPnlImpact = "-400000.00",
        )
        val repository = object : LatestStressBatchRepository {
            override suspend fun save(bookId: BookId, result: BatchStressRunResultResponse) {}
            override suspend fun findLatestByBookId(bookId: BookId) =
                if (bookId.value == "port-1") stored else null
        }

        testApplication {
            install(ContentNegotiation) { json() }
            routing {
                riskRoutes(
                    varCalculationService = mockk(relaxed = true),
                    varCache = mockk(relaxed = true),
                    positionProvider = mockk(relaxed = true),
                    stressTestStub = mockk(relaxed = true),
                    regulatoryStub = mockk(relaxed = true),
                    latestStressBatchRepository = repository,
                )
            }

            val response = client.get("/api/v1/risk/stress/port-1/batch")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<BatchStressRunResultResponse>(response.bodyAsText())
            body.results.size shouldBe 2
            body.results[0].scenarioName shouldBe "GFC_2008"
            body.worstScenarioName shouldBe "GFC_2008"
        }
    }

    test("GET batch returns 404 when nothing has been persisted for the book") {
        val repository = object : LatestStressBatchRepository {
            override suspend fun save(bookId: BookId, result: BatchStressRunResultResponse) {}
            override suspend fun findLatestByBookId(bookId: BookId): BatchStressRunResultResponse? = null
        }

        testApplication {
            install(ContentNegotiation) { json() }
            routing {
                riskRoutes(
                    varCalculationService = mockk(relaxed = true),
                    varCache = mockk(relaxed = true),
                    positionProvider = mockk(relaxed = true),
                    stressTestStub = mockk(relaxed = true),
                    regulatoryStub = mockk(relaxed = true),
                    latestStressBatchRepository = repository,
                )
            }

            val response = client.get("/api/v1/risk/stress/empty-book/batch")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})

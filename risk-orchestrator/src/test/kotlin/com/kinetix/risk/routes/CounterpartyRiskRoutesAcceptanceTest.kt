package com.kinetix.risk.routes

import com.kinetix.proto.risk.CalculateCVAResponse
import com.kinetix.proto.risk.CalculatePFEResponse
import com.kinetix.proto.risk.CounterpartyRiskServiceGrpcKt.CounterpartyRiskServiceCoroutineStub
import com.kinetix.proto.risk.ExposureProfile
import com.kinetix.risk.client.GrpcCounterpartyRiskClient
import com.kinetix.risk.client.HttpPositionServiceClient
import com.kinetix.risk.client.HttpReferenceDataServiceClient
import com.kinetix.risk.client.dtos.CounterpartyDto
import com.kinetix.risk.client.dtos.NettingAgreementDto
import com.kinetix.risk.grpc.FakeCounterpartyRiskService
import com.kinetix.risk.grpc.GrpcFakeServer
import com.kinetix.risk.model.CounterpartyExposureSnapshot
import com.kinetix.risk.model.ExposureAtTenor
import com.kinetix.risk.persistence.CounterpartyExposureHistoryTable
import com.kinetix.risk.persistence.DatabaseTestSetup
import com.kinetix.risk.persistence.ExposedCounterpartyExposureRepository
import com.kinetix.risk.routes.dtos.CounterpartyExposureResponse
import com.kinetix.risk.routes.dtos.CVAResponse
import com.kinetix.risk.service.CounterpartyRiskOrchestrationService
import com.kinetix.risk.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

private val TENORS = listOf(
    ExposureAtTenor("1M", 1.0 / 12, 500_000.0, 750_000.0, 900_000.0),
    ExposureAtTenor("1Y", 1.0, 1_200_000.0, 1_800_000.0, 2_100_000.0),
)

private fun snapshot(
    counterpartyId: String = "CP-GS",
    netExposure: Double = 2_000_000.0,
    peakPfe: Double = 1_800_000.0,
    cva: Double? = 12_500.0,
) = CounterpartyExposureSnapshot(
    id = 1L,
    counterpartyId = counterpartyId,
    calculatedAt = Instant.parse("2026-03-24T10:00:00Z"),
    pfeProfile = TENORS,
    currentNetExposure = netExposure,
    peakPfe = peakPfe,
    cva = cva,
    cvaEstimated = false,
)

@Serializable
private data class NetCollateralBody(
    val counterpartyId: String,
    val collateralReceived: String,
    val collateralPosted: String,
)

class CounterpartyRiskRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedCounterpartyExposureRepository(db)

    val fakeCounterpartyRiskService = FakeCounterpartyRiskService()
    val grpcServer = GrpcFakeServer(fakeCounterpartyRiskService)
    val counterpartyRiskStub = CounterpartyRiskServiceCoroutineStub(grpcServer.channel())
    val counterpartyRiskClient = GrpcCounterpartyRiskClient(counterpartyRiskStub)

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json() }
    }

    // Reference-data stub: returns CP-GS with all fields needed for PFE and CVA tests.
    // CP-MISSING returns 404.
    val referenceDataBackend = BackendStubServer {
        get("/api/v1/counterparties/{counterpartyId}") {
            val id = call.parameters["counterpartyId"]
            if (id == "CP-MISSING") {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(
                    CounterpartyDto(
                        counterpartyId = id ?: "CP-GS",
                        legalName = "Goldman Sachs",
                        lgd = 0.4,
                        cdsSpreadBps = 65.0,
                        ratingSp = "A+",
                        sector = "FINANCIALS",
                    )
                )
            }
        }
        get("/api/v1/counterparties/{counterpartyId}/netting-sets") {
            call.respond(
                listOf(NettingAgreementDto("NS-001", "CP-GS", "ISDA_2002", true, 0.0, "USD"))
            )
        }
    }

    // Position-service stub: returns zero collateral and CP-GS → AAPL→NS-001 netting sets.
    val positionServiceBackend = BackendStubServer {
        get("/api/v1/counterparties/{counterpartyId}/collateral/net") {
            val id = call.parameters["counterpartyId"] ?: ""
            call.respond(NetCollateralBody(counterpartyId = id, collateralReceived = "0.0", collateralPosted = "0.0"))
        }
        get("/api/v1/counterparties/{counterpartyId}/instrument-netting-sets") {
            val id = call.parameters["counterpartyId"]
            // The PFE test exercises CP-GS with one netting assignment; all others return empty.
            if (id == "CP-GS") {
                call.respond(mapOf("AAPL" to "NS-001"))
            } else {
                call.respond(emptyMap<String, String>())
            }
        }
    }

    val referenceDataClient = HttpReferenceDataServiceClient(httpClient, referenceDataBackend.baseUrl)
    val positionServiceClient = HttpPositionServiceClient(httpClient, positionServiceBackend.baseUrl)

    val service = CounterpartyRiskOrchestrationService(
        referenceDataClient = referenceDataClient,
        counterpartyRiskClient = counterpartyRiskClient,
        positionServiceClient = positionServiceClient,
        repository = repository,
    )

    beforeEach {
        newSuspendedTransaction(db = db) { CounterpartyExposureHistoryTable.deleteAll() }
        fakeCounterpartyRiskService.calculatePFERequests.clear()
        fakeCounterpartyRiskService.calculateCVARequests.clear()
        fakeCounterpartyRiskService.calculatePFEHandler = { CalculatePFEResponse.getDefaultInstance() }
        fakeCounterpartyRiskService.calculateCVAHandler = { CalculateCVAResponse.getDefaultInstance() }
    }

    afterSpec {
        grpcServer.close()
        referenceDataBackend.close()
        positionServiceBackend.close()
        httpClient.close()
    }

    fun Application.configureTestApp() {
        install(ContentNegotiation) { json() }
        routing {
            counterpartyRiskRoutes(service)
        }
    }

    test("GET /api/v1/counterparty-risk/ returns all latest exposures") {
        repository.save(snapshot("CP-GS"))
        repository.save(snapshot("CP-JPM"))

        testApplication {
            application { configureTestApp() }
            val response = client.get("/api/v1/counterparty-risk/")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<List<CounterpartyExposureResponse>>(response.bodyAsText())
            body.size shouldBe 2
            body.map { it.counterpartyId }.toSet() shouldBe setOf("CP-GS", "CP-JPM")
        }
    }

    test("GET /api/v1/counterparty-risk/{id} returns latest snapshot for counterparty") {
        repository.save(snapshot("CP-GS"))

        testApplication {
            application { configureTestApp() }
            val response = client.get("/api/v1/counterparty-risk/CP-GS")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<CounterpartyExposureResponse>(response.bodyAsText())
            body.counterpartyId shouldBe "CP-GS"
            body.currentNetExposure shouldBe 2_000_000.0
            body.peakPfe shouldBe 1_800_000.0
            body.pfeProfile.size shouldBe 2
        }
    }

    test("GET /api/v1/counterparty-risk/{id} returns 404 when no snapshot exists") {
        testApplication {
            application { configureTestApp() }
            val response = client.get("/api/v1/counterparty-risk/CP-NEW")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /api/v1/counterparty-risk/{id}/history returns historical snapshots") {
        repository.save(snapshot("CP-GS", netExposure = 2_000_000.0))
        repository.save(snapshot("CP-GS", netExposure = 1_800_000.0))

        testApplication {
            application { configureTestApp() }
            val response = client.get("/api/v1/counterparty-risk/CP-GS/history")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<List<CounterpartyExposureResponse>>(response.bodyAsText())
            body.size shouldBe 2
        }
    }

    test("POST /api/v1/counterparty-risk/{id}/pfe computes and returns PFE snapshot") {
        fakeCounterpartyRiskService.calculatePFEHandler = {
            CalculatePFEResponse.newBuilder()
                .setCounterpartyId("CP-GS")
                .setNettingSetId("NS-001")
                .setGrossExposure(3_000_000.0)
                .setNetExposure(2_000_000.0)
                .addExposureProfile(
                    ExposureProfile.newBuilder()
                        .setTenor("1M").setTenorYears(1.0 / 12)
                        .setExpectedExposure(500_000.0).setPfe95(750_000.0).setPfe99(900_000.0)
                        .build()
                )
                .addExposureProfile(
                    ExposureProfile.newBuilder()
                        .setTenor("1Y").setTenorYears(1.0)
                        .setExpectedExposure(1_200_000.0).setPfe95(1_800_000.0).setPfe99(2_100_000.0)
                        .build()
                )
                .build()
        }

        testApplication {
            application { configureTestApp() }
            val response = client.post("/api/v1/counterparty-risk/CP-GS/pfe") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "positions": [
                            {"instrumentId": "AAPL", "marketValue": 1000000.0, "assetClass": "EQUITY", "volatility": 0.25, "sector": "TECHNOLOGY"}
                        ]
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<CounterpartyExposureResponse>(response.bodyAsText())
            body.counterpartyId shouldBe "CP-GS"
            body.currentNetExposure shouldBe 2_000_000.0
        }
    }

    test("POST /api/v1/counterparty-risk/{id}/pfe returns 400 when counterparty not found") {
        testApplication {
            application { configureTestApp() }
            val response = client.post("/api/v1/counterparty-risk/CP-MISSING/pfe") {
                contentType(ContentType.Application.Json)
                setBody("""{"positions": []}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("POST /api/v1/counterparty-risk/{id}/cva returns 404 when no PFE snapshot exists") {
        testApplication {
            application { configureTestApp() }
            val response = client.post("/api/v1/counterparty-risk/CP-NEW/cva")
            response.status shouldBe HttpStatusCode.NotFound
            response.bodyAsText() shouldContain "No PFE snapshot"
        }
    }

    test("GET /api/v1/counterparty-risk/{id} surfaces agreementStatus from reference-data") {
        // The default reference-data stub returns an ACTIVE agreement for CP-GS.
        repository.save(snapshot("CP-GS"))

        testApplication {
            application { configureTestApp() }
            val response = client.get("/api/v1/counterparty-risk/CP-GS")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<CounterpartyExposureResponse>(response.bodyAsText())
            body.agreementStatus shouldBe "ACTIVE"
        }
    }

    test("POST /api/v1/counterparty-risk/{id}/cva computes CVA from latest PFE profile") {
        repository.save(snapshot("CP-GS"))

        fakeCounterpartyRiskService.calculateCVAHandler = {
            CalculateCVAResponse.newBuilder()
                .setCounterpartyId("CP-GS")
                .setCva(12_500.0)
                .setIsEstimated(false)
                .setHazardRate(0.0065)
                .setPd1Y(0.0065)
                .build()
        }

        testApplication {
            application { configureTestApp() }
            val response = client.post("/api/v1/counterparty-risk/CP-GS/cva")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<CVAResponse>(response.bodyAsText())
            body.counterpartyId shouldBe "CP-GS"
            body.cva shouldBe 12_500.0
            body.isEstimated shouldBe false
        }
    }
})

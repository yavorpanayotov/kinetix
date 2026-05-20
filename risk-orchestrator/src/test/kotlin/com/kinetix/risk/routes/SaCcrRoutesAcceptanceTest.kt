package com.kinetix.risk.routes

import com.kinetix.proto.risk.CalculateSaCcrResponse
import com.kinetix.proto.risk.SaCcrServiceGrpcKt.SaCcrServiceCoroutineStub
import com.kinetix.risk.client.GrpcSaCcrClient
import com.kinetix.risk.client.HttpPositionServiceClient
import com.kinetix.risk.client.HttpReferenceDataServiceClient
import com.kinetix.risk.client.dtos.CounterpartyDto
import com.kinetix.risk.client.dtos.CounterpartyTradeDto
import com.kinetix.risk.client.dtos.NettingAgreementDto
import com.kinetix.risk.grpc.FakeSaCcrService
import com.kinetix.risk.grpc.GrpcFakeServer
import com.kinetix.risk.routes.dtos.SaCcrResponse
import com.kinetix.risk.routes.dtos.SaCcrSummaryResponse
import com.kinetix.risk.service.SaCcrService
import com.kinetix.risk.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

private fun NetCollateralBody(counterpartyId: String) =
    mapOf(
        "counterpartyId" to counterpartyId,
        "collateralReceived" to "0.0",
        "collateralPosted" to "0.0",
    )

private fun trade(tradeId: String, instrumentId: String) = CounterpartyTradeDto(
    tradeId = tradeId,
    instrumentId = instrumentId,
    assetClass = "RATES",
    side = "BUY",
    quantity = "100",
    priceAmount = "1000",
    priceCurrency = "USD",
    counterpartyId = "CP-GS",
)

private fun saCcrGrpcResponse(nettingSetId: String, counterpartyId: String = "CP-GS") =
    CalculateSaCcrResponse.newBuilder()
        .setNettingSetId(nettingSetId)
        .setCounterpartyId(counterpartyId)
        .setReplacementCost(100_000.0)
        .setPfeAddon(525_000.0)
        .setMultiplier(1.0)
        .setEad(875_000.0)
        .setAlpha(1.4)
        .build()

private fun Application.configureTestApp(service: SaCcrService) {
    install(ContentNegotiation) { json() }
    routing {
        saCcrRoutes(service)
    }
}

class SaCcrRoutesAcceptanceTest : FunSpec({

    val fakeSaCcrService = FakeSaCcrService()
    val grpcServer = GrpcFakeServer(fakeSaCcrService)
    val saCcrStub = SaCcrServiceCoroutineStub(grpcServer.channel())
    val saCcrClient = GrpcSaCcrClient(saCcrStub)

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json() }
    }

    // Reference-data stub: CP-GS has two distinct netting agreements (one ISDA, one GMRA).
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
                        sector = "FINANCIALS",
                        lgd = 0.4,
                    ),
                )
            }
        }
        get("/api/v1/counterparties/{counterpartyId}/netting-sets") {
            call.respond(
                listOf(
                    NettingAgreementDto("NS-ISDA", "CP-GS", "ISDA_2002", true, 0.0, "USD"),
                    NettingAgreementDto("NS-GMRA", "CP-GS", "GMRA", true, 0.0, "USD"),
                ),
            )
        }
    }

    // Position-service stub: CP-GS has trades split across the two netting agreements.
    val positionServiceBackend = BackendStubServer {
        get("/api/v1/counterparties/{counterpartyId}/trades") {
            call.respond(
                listOf(
                    trade("T1", "INS-ISDA-1"),
                    trade("T2", "INS-ISDA-2"),
                    trade("T3", "INS-GMRA-1"),
                ),
            )
        }
        get("/api/v1/counterparties/{counterpartyId}/instrument-netting-sets") {
            call.respond(
                mapOf(
                    "INS-ISDA-1" to "NS-ISDA",
                    "INS-ISDA-2" to "NS-ISDA",
                    "INS-GMRA-1" to "NS-GMRA",
                ),
            )
        }
        get("/api/v1/counterparties/{counterpartyId}/collateral/net") {
            val id = call.parameters["counterpartyId"] ?: ""
            call.respond(NetCollateralBody(id))
        }
    }

    val referenceDataClient = HttpReferenceDataServiceClient(httpClient, referenceDataBackend.baseUrl)
    val positionServiceClient = HttpPositionServiceClient(httpClient, positionServiceBackend.baseUrl)

    val service = SaCcrService(
        referenceDataClient = referenceDataClient,
        saCcrClient = saCcrClient,
        positionServiceClient = positionServiceClient,
    )

    beforeEach {
        fakeSaCcrService.calculateSaCcrRequests.clear()
        // Echo back the requested netting-set id so per-set wiring is observable.
        fakeSaCcrService.calculateSaCcrHandler = { req -> saCcrGrpcResponse(req.nettingSetId) }
    }

    afterSpec {
        grpcServer.close()
        referenceDataBackend.close()
        positionServiceBackend.close()
        httpClient.close()
    }

    test("GET /sa-ccr returns one SA-CCR result per netting set") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-GS/sa-ccr")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<SaCcrSummaryResponse>(response.bodyAsText())
            body.counterpartyId shouldBe "CP-GS"
            body.nettingSets shouldHaveSize 2
            body.nettingSets.map { it.nettingSetId } shouldContainExactlyInAnyOrder
                listOf("NS-ISDA", "NS-GMRA")
        }
    }

    test("GET /sa-ccr totalEad is the sum of per-netting-set EADs") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-GS/sa-ccr")
            val body = Json.decodeFromString<SaCcrSummaryResponse>(response.bodyAsText())
            body.totalEad shouldBe body.nettingSets.sumOf { it.ead }
            body.totalEad shouldBe 875_000.0 * 2
        }
    }

    test("trades are not netted across netting-set boundaries — each set sees only its own trades") {
        testApplication {
            application { configureTestApp(service) }
            client.get("/api/v1/counterparty/CP-GS/sa-ccr")
        }
        // The fake gRPC service records what each per-set call received.
        val isdaReq = fakeSaCcrService.calculateSaCcrRequests.single { it.nettingSetId == "NS-ISDA" }
        val gmraReq = fakeSaCcrService.calculateSaCcrRequests.single { it.nettingSetId == "NS-GMRA" }
        isdaReq.positionsList.map { it.instrumentId } shouldContainExactlyInAnyOrder
            listOf("INS-ISDA-1", "INS-ISDA-2")
        gmraReq.positionsList.map { it.instrumentId } shouldContainExactlyInAnyOrder
            listOf("INS-GMRA-1")
    }

    test("GET /sa-ccr?nettingSetId returns only the requested netting set") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-GS/sa-ccr?nettingSetId=NS-GMRA")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<SaCcrResponse>(response.bodyAsText())
            body.nettingSetId shouldBe "NS-GMRA"
            body.counterpartyId shouldBe "CP-GS"
            body.alpha shouldBe 1.4
        }
    }

    test("GET /sa-ccr?nettingSetId returns 404 for an unknown netting set") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-GS/sa-ccr?nettingSetId=NS-UNKNOWN")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /sa-ccr returns 404 when counterparty not found") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-MISSING/sa-ccr")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /sa-ccr with collateral query param passes it through") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-GS/sa-ccr?collateral=500000")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("SA-CCR response contains pfe_addon field labelled distinctly from MC PFE") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-GS/sa-ccr?nettingSetId=NS-ISDA")
            val body = Json.decodeFromString<SaCcrResponse>(response.bodyAsText())
            // The field is named pfeAddon to make clear it is the SA-CCR regulatory add-on.
            body.pfeAddon shouldBe 525_000.0
            body.replacementCost shouldBe 100_000.0
        }
    }
})

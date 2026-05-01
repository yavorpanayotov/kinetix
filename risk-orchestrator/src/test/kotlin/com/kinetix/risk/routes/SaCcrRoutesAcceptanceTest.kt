package com.kinetix.risk.routes

import com.kinetix.proto.risk.CalculateSaCcrResponse
import com.kinetix.proto.risk.SaCcrServiceGrpcKt.SaCcrServiceCoroutineStub
import com.kinetix.risk.client.GrpcSaCcrClient
import com.kinetix.risk.client.HttpReferenceDataServiceClient
import com.kinetix.risk.client.dtos.CounterpartyDto
import com.kinetix.risk.grpc.FakeSaCcrService
import com.kinetix.risk.grpc.GrpcFakeServer
import com.kinetix.risk.routes.dtos.SaCcrResponse
import com.kinetix.risk.service.SaCcrService
import com.kinetix.risk.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun saCcrGrpcResponse(counterpartyId: String = "CP-GS") =
    CalculateSaCcrResponse.newBuilder()
        .setNettingSetId("NS-GS-001")
        .setCounterpartyId(counterpartyId)
        .setReplacementCost(100_000.0)
        .setPfeAddon(525_000.0)
        .setMultiplier(1.0)
        .setEad(875_000.0)
        .setAlpha(1.4)
        .build()

private val COUNTERPARTY_GS = CounterpartyDto(
    counterpartyId = "CP-GS",
    legalName = "Goldman Sachs",
    sector = "FINANCIALS",
    lgd = 0.4,
)

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

    val referenceDataBackend = BackendStubServer {
        get("/api/v1/counterparties/{counterpartyId}") {
            val id = call.parameters["counterpartyId"]
            if (id == "CP-MISSING") {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(CounterpartyDto(counterpartyId = id ?: "CP-GS", legalName = "Goldman Sachs", sector = "FINANCIALS", lgd = 0.4))
            }
        }
    }

    val referenceDataClient = HttpReferenceDataServiceClient(httpClient, referenceDataBackend.baseUrl)

    val service = SaCcrService(
        referenceDataClient = referenceDataClient,
        saCcrClient = saCcrClient,
    )

    beforeEach {
        fakeSaCcrService.calculateSaCcrRequests.clear()
        fakeSaCcrService.calculateSaCcrHandler = { saCcrGrpcResponse() }
    }

    afterSpec {
        grpcServer.close()
        referenceDataBackend.close()
        httpClient.close()
    }

    test("GET /api/v1/counterparty/{id}/sa-ccr returns SA-CCR result") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-GS/sa-ccr")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.decodeFromString<SaCcrResponse>(response.bodyAsText())
            body.counterpartyId shouldBe "CP-GS"
            body.ead shouldBe 875_000.0
            body.alpha shouldBe 1.4
        }
    }

    test("GET /api/v1/counterparty/{id}/sa-ccr returns 404 when counterparty not found") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-MISSING/sa-ccr")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /api/v1/counterparty/{id}/sa-ccr with collateral query param passes it through") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-GS/sa-ccr?collateral=500000")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("SA-CCR response contains pfe_addon field labelled distinctly from MC PFE") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/counterparty/CP-GS/sa-ccr")
            val body = Json.decodeFromString<SaCcrResponse>(response.bodyAsText())
            // The field is named pfeAddon to make clear it is the SA-CCR regulatory add-on
            body.pfeAddon shouldBe 525_000.0
            body.replacementCost shouldBe 100_000.0
        }
    }
})

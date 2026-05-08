package com.kinetix.risk.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.proto.risk.AttributionServiceGrpcKt.AttributionServiceCoroutineStub
import com.kinetix.proto.risk.BrinsonAttributionResponse as ProtoBrinsonAttributionResponse
import com.kinetix.proto.risk.SectorAttributionResult
import com.kinetix.risk.client.GrpcAttributionClient
import com.kinetix.risk.client.HttpBenchmarkServiceClient
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.dtos.BenchmarkConstituentDto
import com.kinetix.risk.client.dtos.BenchmarkDetailDto
import com.kinetix.risk.grpc.FakeAttributionService
import com.kinetix.risk.grpc.GrpcFakeServer
import com.kinetix.risk.routes.dtos.BrinsonAttributionResponse
import com.kinetix.risk.service.BenchmarkAttributionService
import com.kinetix.risk.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val BOOK_ID = BookId("BOOK-EQ-01")

private fun position(instrumentId: String, marketValue: Double) = Position(
    bookId = BOOK_ID,
    instrumentId = InstrumentId(instrumentId),
    quantity = BigDecimal.ONE,
    averageCost = Money(BigDecimal.valueOf(marketValue), USD),
    marketPrice = Money(BigDecimal.valueOf(marketValue), USD),
    assetClass = AssetClass.EQUITY,
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private val POSITIONS = listOf(
    position("AAPL", 700_000.0),
    position("MSFT", 300_000.0),
)

private val BENCHMARK_DETAIL = BenchmarkDetailDto(
    benchmarkId = "SP500",
    name = "S&P 500",
    description = null,
    createdAt = "2026-01-01T00:00:00Z",
    constituents = listOf(
        BenchmarkConstituentDto("AAPL", "0.0700", "2026-03-25"),
        BenchmarkConstituentDto("MSFT", "0.0650", "2026-03-25"),
    ),
)

private fun brinsonProtoResponse() = ProtoBrinsonAttributionResponse.newBuilder()
    .addSectors(
        SectorAttributionResult.newBuilder()
            .setSectorLabel("AAPL")
            .setPortfolioWeight(0.70)
            .setBenchmarkWeight(0.07)
            .setPortfolioReturn(0.0)
            .setBenchmarkReturn(0.0)
            .setAllocationEffect(0.028)
            .setSelectionEffect(0.0)
            .setInteractionEffect(0.0)
            .setTotalActiveContribution(0.028)
            .build()
    )
    .addSectors(
        SectorAttributionResult.newBuilder()
            .setSectorLabel("MSFT")
            .setPortfolioWeight(0.30)
            .setBenchmarkWeight(0.065)
            .setPortfolioReturn(0.0)
            .setBenchmarkReturn(0.0)
            .setAllocationEffect(0.012)
            .setSelectionEffect(0.0)
            .setInteractionEffect(0.0)
            .setTotalActiveContribution(0.012)
            .build()
    )
    .setTotalActiveReturn(0.040)
    .setTotalAllocationEffect(0.040)
    .setTotalSelectionEffect(0.0)
    .setTotalInteractionEffect(0.0)
    .build()

private fun Application.configureTestApp(service: BenchmarkAttributionService) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respondText(
                cause.message ?: "Bad request",
                status = HttpStatusCode.BadRequest,
            )
        }
    }
    routing {
        benchmarkAttributionRoutes(service)
    }
}

class BenchmarkAttributionRoutesAcceptanceTest : FunSpec({

    val fakeAttributionService = FakeAttributionService()
    val grpcServer = GrpcFakeServer(fakeAttributionService)
    val attributionStub = AttributionServiceCoroutineStub(grpcServer.channel())
    val attributionEngineClient = GrpcAttributionClient(attributionStub)

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json() }
    }

    val benchmarkBackend = BackendStubServer {
        get("/api/v1/benchmarks/{benchmarkId}") {
            val id = call.parameters["benchmarkId"]
            if (id == "MISSING") {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(BENCHMARK_DETAIL)
            }
        }
    }

    val benchmarkServiceClient = HttpBenchmarkServiceClient(httpClient, benchmarkBackend.baseUrl)

    // Fixed position provider backed by an in-memory list of test positions
    val standardPositionProvider = object : PositionProvider {
        override suspend fun getPositions(bookId: BookId): List<Position> = POSITIONS
    }

    val service = BenchmarkAttributionService(
        positionProvider = standardPositionProvider,
        benchmarkServiceClient = benchmarkServiceClient,
        attributionEngineClient = attributionEngineClient,
    )

    beforeEach {
        fakeAttributionService.calculateBrinsonAttributionRequests.clear()
        fakeAttributionService.calculateBrinsonAttributionHandler = { brinsonProtoResponse() }
    }

    afterSpec {
        grpcServer.close()
        benchmarkBackend.close()
        httpClient.close()
    }

    test("GET /api/v1/books/{bookId}/attribution returns Brinson attribution result") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/books/BOOK-EQ-01/attribution?benchmarkId=SP500&asOfDate=2026-03-25")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.decodeFromString<BrinsonAttributionResponse>(response.bodyAsText())
            body.bookId shouldBe "BOOK-EQ-01"
            body.benchmarkId shouldBe "SP500"
            body.asOfDate shouldBe "2026-03-25"
            body.sectors.size shouldBe 2
            body.totalActiveReturn shouldBe 0.040
            body.totalAllocationEffect shouldBe 0.040
        }
    }

    test("GET /api/v1/books/{bookId}/attribution returns sectors with allocation and selection effects") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/books/BOOK-EQ-01/attribution?benchmarkId=SP500&asOfDate=2026-03-25")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.decodeFromString<BrinsonAttributionResponse>(response.bodyAsText())
            val aaplSector = body.sectors.find { it.sectorLabel == "AAPL" }
            aaplSector?.portfolioWeight shouldBe 0.70
            aaplSector?.benchmarkWeight shouldBe 0.07
            aaplSector?.allocationEffect shouldBe 0.028
        }
    }

    test("GET /api/v1/books/{bookId}/attribution uses today when asOfDate is omitted") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/books/BOOK-EQ-01/attribution?benchmarkId=SP500")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    test("GET /api/v1/books/{bookId}/attribution returns 400 when benchmarkId is missing") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/books/BOOK-EQ-01/attribution")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /api/v1/books/{bookId}/attribution returns 400 when asOfDate is invalid") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/books/BOOK-EQ-01/attribution?benchmarkId=SP500&asOfDate=not-a-date")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /api/v1/books/{bookId}/attribution returns 400 when benchmark not found") {
        testApplication {
            application { configureTestApp(service) }
            val response = client.get("/api/v1/books/BOOK-EQ-01/attribution?benchmarkId=MISSING&asOfDate=2026-03-25")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /api/v1/books/{bookId}/attribution returns 400 when book has no positions") {
        val emptyPositionProvider = object : PositionProvider {
            override suspend fun getPositions(bookId: BookId): List<Position> = emptyList()
        }
        val serviceWithEmpty = BenchmarkAttributionService(
            positionProvider = emptyPositionProvider,
            benchmarkServiceClient = benchmarkServiceClient,
            attributionEngineClient = attributionEngineClient,
        )

        testApplication {
            application { configureTestApp(serviceWithEmpty) }
            val response = client.get("/api/v1/books/BOOK-EQ-01/attribution?benchmarkId=SP500&asOfDate=2026-03-25")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }
})

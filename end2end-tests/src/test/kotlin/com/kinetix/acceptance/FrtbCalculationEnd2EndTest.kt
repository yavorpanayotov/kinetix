package com.kinetix.acceptance

import com.kinetix.regulatory.client.RiskOrchestratorClient
import com.kinetix.regulatory.dtos.FrtbCalculationResponse
import com.kinetix.regulatory.dtos.FrtbHistoryResponse
import com.kinetix.regulatory.dtos.FrtbResultResponse
import com.kinetix.regulatory.dtos.RiskClassChargeDto
import com.kinetix.regulatory.module
import com.kinetix.regulatory.persistence.DatabaseConfig
import com.kinetix.regulatory.persistence.DatabaseFactory
import com.kinetix.regulatory.persistence.ExposedFrtbCalculationRepository
import com.kinetix.testsupport.containers.TestcontainerCaps
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant

class FrtbCalculationEnd2EndTest : BehaviorSpec({

    val regulatoryDb = TestcontainerCaps.tunePostgres(
        PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:latest-pg17")
                .asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName("regulatory_test")
            .withUsername("test")
            .withPassword("test"),
        withTimescale = true,
    )

    lateinit var frtbRepo: ExposedFrtbCalculationRepository
    val riskClient = mockk<RiskOrchestratorClient>()
    val json = Json { ignoreUnknownKeys = true }

    val bookId = "port-frtb-e2e"
    val firstCalculatedAt = Instant.now()
    val secondCalculatedAt = Instant.now().plusSeconds(300)

    fun stubFrtbResult(calculatedAt: Instant) = FrtbResultResponse(
        bookId = bookId,
        sbmCharges = listOf(
            RiskClassChargeDto("EQUITY", "1200.50", "300.25", "150.10", "1650.85"),
            RiskClassChargeDto("GIRR", "800.00", "200.00", "100.00", "1100.00"),
            RiskClassChargeDto("FX", "400.00", "0.00", "50.00", "450.00"),
        ),
        totalSbmCharge = "3200.85",
        grossJtd = "500.00",
        hedgeBenefit = "75.00",
        netDrc = "425.00",
        exoticNotional = "10000.00",
        otherNotional = "5000.00",
        totalRrao = "15.00",
        totalCapitalCharge = "3640.85",
        calculatedAt = calculatedAt.toString(),
    )

    beforeSpec {
        regulatoryDb.start()
        val db = DatabaseFactory.init(
            DatabaseConfig(
                jdbcUrl = regulatoryDb.jdbcUrl,
                username = regulatoryDb.username,
                password = regulatoryDb.password,
            )
        )
        frtbRepo = ExposedFrtbCalculationRepository(db)

        coEvery { riskClient.calculateFrtb(bookId) } returnsMany listOf(
            stubFrtbResult(firstCalculatedAt),
            stubFrtbResult(secondCalculatedAt),
        )
    }

    afterSpec {
        regulatoryDb.stop()
    }

    given("a real PostgreSQL database with Flyway migrations") {
        `when`("an FRTB calculation is triggered and then queried") {
            then("the full calculate → latest → history flow works end-to-end") {
                testApplication {
                    application {
                        module(frtbRepo, riskClient)
                    }

                    // --- Latest returns 404 when no calculations exist ---
                    val latestEmpty = client.get("/api/v1/regulatory/frtb/$bookId/latest")
                    latestEmpty.status shouldBe HttpStatusCode.NotFound

                    // --- History is empty initially ---
                    val historyEmpty = client.get("/api/v1/regulatory/frtb/$bookId/history")
                    historyEmpty.status shouldBe HttpStatusCode.OK
                    val emptyHistory = json.decodeFromString<FrtbHistoryResponse>(historyEmpty.bodyAsText())
                    emptyHistory.calculations.size shouldBe 0

                    // --- Trigger FRTB calculation ---
                    val calcResponse = client.post("/api/v1/regulatory/frtb/$bookId/calculate")
                    calcResponse.status shouldBe HttpStatusCode.Created
                    val calculation = json.decodeFromString<FrtbCalculationResponse>(calcResponse.bodyAsText())

                    calculation.id.shouldNotBeEmpty()
                    calculation.bookId shouldBe bookId
                    calculation.totalSbmCharge shouldBe "3200.85"
                    calculation.grossJtd shouldBe "500.00"
                    calculation.hedgeBenefit shouldBe "75.00"
                    calculation.netDrc shouldBe "425.00"
                    calculation.exoticNotional shouldBe "10000.00"
                    calculation.otherNotional shouldBe "5000.00"
                    calculation.totalRrao shouldBe "15.00"
                    calculation.totalCapitalCharge shouldBe "3640.85"
                    calculation.storedAt.shouldNotBeEmpty()

                    // --- SbM breakdown has all 3 risk classes ---
                    calculation.sbmCharges.size shouldBe 3
                    val equity = calculation.sbmCharges.first { it.riskClass == "EQUITY" }
                    equity.deltaCharge shouldBe "1200.50"
                    equity.vegaCharge shouldBe "300.25"
                    equity.curvatureCharge shouldBe "150.10"
                    equity.totalCharge shouldBe "1650.85"

                    val girr = calculation.sbmCharges.first { it.riskClass == "GIRR" }
                    girr.totalCharge shouldBe "1100.00"

                    val fx = calculation.sbmCharges.first { it.riskClass == "FX" }
                    fx.totalCharge shouldBe "450.00"

                    // --- Latest now returns the calculation ---
                    val latestResponse = client.get("/api/v1/regulatory/frtb/$bookId/latest")
                    latestResponse.status shouldBe HttpStatusCode.OK
                    val latest = json.decodeFromString<FrtbCalculationResponse>(latestResponse.bodyAsText())
                    latest.id shouldBe calculation.id
                    latest.totalCapitalCharge shouldBe "3640.85"

                    // --- Trigger a second calculation ---
                    val secondCalcResponse = client.post("/api/v1/regulatory/frtb/$bookId/calculate")
                    secondCalcResponse.status shouldBe HttpStatusCode.Created
                    val secondCalc = json.decodeFromString<FrtbCalculationResponse>(secondCalcResponse.bodyAsText())
                    secondCalc.id shouldNotBe calculation.id

                    // --- Latest returns the second (most recent) calculation ---
                    val latestAfterSecond = client.get("/api/v1/regulatory/frtb/$bookId/latest")
                    latestAfterSecond.status shouldBe HttpStatusCode.OK
                    val latestRecord = json.decodeFromString<FrtbCalculationResponse>(latestAfterSecond.bodyAsText())
                    latestRecord.id shouldBe secondCalc.id

                    // --- History returns both calculations, most recent first ---
                    val historyResponse = client.get("/api/v1/regulatory/frtb/$bookId/history")
                    historyResponse.status shouldBe HttpStatusCode.OK
                    val history = json.decodeFromString<FrtbHistoryResponse>(historyResponse.bodyAsText())
                    history.calculations.size shouldBe 2
                    history.calculations[0].id shouldBe secondCalc.id
                    history.calculations[1].id shouldBe calculation.id
                    history.limit shouldBe 20
                    history.offset shouldBe 0

                    // --- History respects pagination ---
                    val paginatedResponse = client.get("/api/v1/regulatory/frtb/$bookId/history?limit=1&offset=0")
                    paginatedResponse.status shouldBe HttpStatusCode.OK
                    val paginated = json.decodeFromString<FrtbHistoryResponse>(paginatedResponse.bodyAsText())
                    paginated.calculations.size shouldBe 1
                    paginated.limit shouldBe 1
                    paginated.offset shouldBe 0

                    // --- Different portfolio returns 404 for latest ---
                    val otherLatest = client.get("/api/v1/regulatory/frtb/port-other/latest")
                    otherLatest.status shouldBe HttpStatusCode.NotFound
                }
            }
        }
    }
})

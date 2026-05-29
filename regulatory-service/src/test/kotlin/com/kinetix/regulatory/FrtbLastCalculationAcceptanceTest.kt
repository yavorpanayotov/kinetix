package com.kinetix.regulatory

import com.kinetix.regulatory.client.RiskOrchestratorClient
import com.kinetix.regulatory.dtos.FrtbResultResponse
import com.kinetix.regulatory.dtos.RiskClassChargeDto
import com.kinetix.regulatory.persistence.DatabaseTestSetup
import com.kinetix.regulatory.persistence.ExposedFrtbCalculationRepository
import com.kinetix.regulatory.persistence.FrtbCalculationsTable
import com.kinetix.regulatory.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Full-wire acceptance test for the "last FRTB calculation" query that backs the
 * Regulatory tab default view: instead of an empty "Click Calculate FRTB" state,
 * the tab loads the most recent persisted calculation for the selected book.
 *
 * Uses real Postgres (Testcontainers) for persistence and a real in-process HTTP
 * server (BackendStubServer) standing in for the risk orchestrator, so the FRTB
 * calculation is persisted and the latest-query travels the full HTTP + DB stack.
 */
class FrtbLastCalculationAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val frtbRepo = ExposedFrtbCalculationRepository(db)

    // The stub returns whatever FRTB result the current test sets; calculatedAt is
    // varied between calls so "most recent" is unambiguous.
    var stubResult = frtbResult(
        bookId = "book-1",
        totalCapitalCharge = "0.00",
        calculatedAt = "2025-01-01T00:00:00Z",
    )

    val riskBackend = BackendStubServer {
        post("/api/v1/regulatory/frtb/{bookId}") {
            call.respond(stubResult)
        }
    }
    val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
    val riskClient = RiskOrchestratorClient(httpClient, riskBackend.baseUrl)

    afterSpec {
        riskBackend.close()
        httpClient.close()
    }

    beforeEach {
        newSuspendedTransaction(db = db) {
            FrtbCalculationsTable.deleteAll()
        }
    }

    test("a book with a prior FRTB calculation — GET /latest is called — returns the most recent calculation with its results so the tab is not empty") {
        testApplication {
            application { module(frtbRepo, riskClient) }

            // First (older) calculation.
            stubResult = frtbResult(
                bookId = "book-1",
                totalCapitalCharge = "111111.00",
                calculatedAt = "2025-01-10T09:00:00Z",
            )
            client.post("/api/v1/regulatory/frtb/book-1/calculate").status shouldBe HttpStatusCode.Created

            // Second (newer) calculation — this is the one the tab should show.
            stubResult = frtbResult(
                bookId = "book-1",
                totalCapitalCharge = "987654.00",
                calculatedAt = "2025-02-20T15:30:00Z",
            )
            client.post("/api/v1/regulatory/frtb/book-1/calculate").status shouldBe HttpStatusCode.Created

            val response = client.get("/api/v1/regulatory/frtb/book-1/latest")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]?.jsonPrimitive?.content shouldBe "book-1"
            body["totalCapitalCharge"]?.jsonPrimitive?.content shouldBe "987654.00"
            body["calculatedAt"]?.jsonPrimitive?.content shouldBe "2025-02-20T15:30:00Z"
        }
    }

    test("a book with no FRTB calculations — GET /latest is called — returns 404 so the tab falls back to the empty state") {
        testApplication {
            application { module(frtbRepo, riskClient) }

            val response = client.get("/api/v1/regulatory/frtb/book-never-calculated/latest")

            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})

private fun frtbResult(
    bookId: String,
    totalCapitalCharge: String,
    calculatedAt: String,
): FrtbResultResponse = FrtbResultResponse(
    bookId = bookId,
    sbmCharges = listOf(
        RiskClassChargeDto("GIRR", "100.00", "50.00", "25.00", "175.00"),
    ),
    totalSbmCharge = "175.00",
    grossJtd = "200.00",
    hedgeBenefit = "50.00",
    netDrc = "150.00",
    exoticNotional = "10.00",
    otherNotional = "5.00",
    totalRrao = "15.00",
    totalCapitalCharge = totalCapitalCharge,
    calculatedAt = calculatedAt,
)

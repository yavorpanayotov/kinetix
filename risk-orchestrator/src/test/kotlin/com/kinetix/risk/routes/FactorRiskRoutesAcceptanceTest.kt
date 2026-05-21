package com.kinetix.risk.routes

import com.kinetix.risk.model.FactorContribution
import com.kinetix.risk.model.FactorDecompositionSnapshot
import com.kinetix.risk.persistence.DatabaseTestSetup
import com.kinetix.risk.persistence.ExposedFactorDecompositionRepository
import com.kinetix.risk.persistence.FactorDecompositionSnapshotsTable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class FactorRiskRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedFactorDecompositionRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) { FactorDecompositionSnapshotsTable.deleteAll() }
    }

    fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                factorRiskRoutes(repository)
            }
            block()
        }
    }

    val sampleSnapshot = FactorDecompositionSnapshot(
        bookId = "BOOK-1",
        calculatedAt = Instant.parse("2026-03-24T10:00:00Z"),
        totalVar = 50_000.0,
        systematicVar = 38_000.0,
        idiosyncraticVar = 12_000.0,
        rSquared = 0.576,
        concentrationWarning = false,
        factors = listOf(
            FactorContribution(
                factorType = "EQUITY_BETA",
                factorExposure = 250_000.0,
                varContribution = 30_000.0,
                pnlAttribution = 4_200.0,
                pctOfTotal = 0.60,
                loading = 1.2,
                loadingMethod = "OLS_REGRESSION",
            ),
            FactorContribution(
                factorType = "RATES_DURATION",
                factorExposure = -75_000.0,
                varContribution = 8_000.0,
                pnlAttribution = -1_100.0,
                pctOfTotal = 0.16,
                loading = -0.5,
                loadingMethod = "ANALYTICAL",
            ),
        ),
    )

    test("GET /api/v1/books/{bookId}/factor-risk/latest returns the most recent snapshot") {
        repository.save(sampleSnapshot)

        testApp {
            val response = client.get("/api/v1/books/BOOK-1/factor-risk/latest")

            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]?.jsonPrimitive?.content shouldBe "BOOK-1"
            body["totalVar"]?.jsonPrimitive?.double shouldBe 50_000.0
            body["systematicVar"]?.jsonPrimitive?.double shouldBe 38_000.0
            body["idiosyncraticVar"]?.jsonPrimitive?.double shouldBe 12_000.0
            body["rSquared"]?.jsonPrimitive?.double shouldBe 0.576
            body["concentrationWarning"]?.jsonPrimitive?.boolean shouldBe false
            body["calculatedAt"]?.jsonPrimitive?.content shouldBe "2026-03-24T10:00:00Z"
        }
    }

    test("GET /api/v1/books/{bookId}/factor-risk/latest returns 404 when no snapshot exists") {
        testApp {
            val response = client.get("/api/v1/books/UNKNOWN/factor-risk/latest")

            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /api/v1/books/{bookId}/factor-risk/latest includes factor contributions") {
        repository.save(sampleSnapshot)

        testApp {
            val response = client.get("/api/v1/books/BOOK-1/factor-risk/latest")

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val factors = body["factors"]?.jsonArray!!
            factors.size shouldBe 2

            val equityFactor = factors.first { it.jsonObject["factorType"]?.jsonPrimitive?.content == "EQUITY_BETA" }.jsonObject
            equityFactor["factorExposure"]?.jsonPrimitive?.double shouldBe 250_000.0
            equityFactor["varContribution"]?.jsonPrimitive?.double shouldBe 30_000.0
            equityFactor["pnlAttribution"]?.jsonPrimitive?.double shouldBe 4_200.0
            equityFactor["pctOfTotal"]?.jsonPrimitive?.double shouldBe 0.60
            equityFactor["loadingMethod"]?.jsonPrimitive?.content shouldBe "OLS_REGRESSION"
        }
    }

    test("GET /api/v1/books/{bookId}/factor-risk returns history in descending order") {
        repository.save(sampleSnapshot.copy(totalVar = 50_000.0, calculatedAt = Instant.parse("2026-03-23T10:00:00Z")))
        repository.save(sampleSnapshot.copy(totalVar = 60_000.0, calculatedAt = Instant.parse("2026-03-24T10:00:00Z")))

        testApp {
            val response = client.get("/api/v1/books/BOOK-1/factor-risk")

            response.status shouldBe HttpStatusCode.OK

            val arr = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            arr.size shouldBe 2
            arr[0].jsonObject["totalVar"]?.jsonPrimitive?.double shouldBe 60_000.0
            arr[1].jsonObject["totalVar"]?.jsonPrimitive?.double shouldBe 50_000.0
        }
    }

    test("GET /api/v1/books/{bookId}/factor-risk respects the limit query parameter") {
        repository.save(sampleSnapshot.copy(calculatedAt = Instant.parse("2026-03-22T10:00:00Z")))
        repository.save(sampleSnapshot.copy(calculatedAt = Instant.parse("2026-03-23T10:00:00Z")))
        repository.save(sampleSnapshot.copy(calculatedAt = Instant.parse("2026-03-24T10:00:00Z")))

        testApp {
            val response = client.get("/api/v1/books/BOOK-1/factor-risk?limit=2")

            response.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(response.bodyAsText()).jsonArray.size shouldBe 2
        }
    }

    test("GET /api/v1/books/{bookId}/factor-risk returns an empty array when no snapshots exist") {
        testApp {
            val response = client.get("/api/v1/books/EMPTY-BOOK/factor-risk")

            response.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(response.bodyAsText()).jsonArray.size shouldBe 0
        }
    }
})

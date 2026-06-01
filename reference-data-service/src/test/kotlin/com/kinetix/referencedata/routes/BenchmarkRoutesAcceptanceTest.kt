package com.kinetix.referencedata.routes

import com.kinetix.common.model.CreditSpread
import com.kinetix.common.model.DividendYield
import com.kinetix.common.model.InstrumentId
import com.kinetix.referencedata.cache.ReferenceDataCache
import com.kinetix.referencedata.kafka.ReferenceDataPublisher
import com.kinetix.referencedata.model.Benchmark
import com.kinetix.referencedata.model.BenchmarkConstituent
import com.kinetix.referencedata.module
import com.kinetix.referencedata.persistence.DatabaseTestSetup
import com.kinetix.referencedata.persistence.ExposedBenchmarkRepository
import com.kinetix.referencedata.persistence.ExposedCreditSpreadRepository
import com.kinetix.referencedata.persistence.ExposedDividendYieldRepository
import com.kinetix.referencedata.service.BenchmarkService
import com.kinetix.referencedata.service.ReferenceDataIngestionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class BenchmarkRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val dividendYieldRepo = ExposedDividendYieldRepository(db)
    val creditSpreadRepo = ExposedCreditSpreadRepository(db)
    val noOpCache = object : ReferenceDataCache {
        override suspend fun putDividendYield(dividendYield: DividendYield) = Unit
        override suspend fun getDividendYield(instrumentId: InstrumentId): DividendYield? = null
        override suspend fun putCreditSpread(creditSpread: CreditSpread) = Unit
        override suspend fun getCreditSpread(instrumentId: InstrumentId): CreditSpread? = null
    }
    val noOpPublisher = object : ReferenceDataPublisher {
        override suspend fun publishDividendYield(dividendYield: DividendYield) = Unit
        override suspend fun publishCreditSpread(creditSpread: CreditSpread) = Unit
    }
    val ingestionService = ReferenceDataIngestionService(
        dividendYieldRepo, creditSpreadRepo, noOpCache, noOpPublisher,
    )
    val benchmarkRepo = ExposedBenchmarkRepository(db)
    val benchmarkService = BenchmarkService(benchmarkRepo)

    val NOW = Instant.parse("2026-03-25T09:00:00Z")
    val TODAY = LocalDate.of(2026, 3, 25)

    fun sampleBenchmark(id: String = "SP500") = Benchmark(
        benchmarkId = id,
        name = "S&P 500",
        description = "Large-cap US equity index",
        createdAt = NOW,
    )

    fun sampleConstituents(benchmarkId: String = "SP500") = listOf(
        BenchmarkConstituent(benchmarkId, "AAPL", BigDecimal("0.0700"), TODAY),
        BenchmarkConstituent(benchmarkId, "MSFT", BigDecimal("0.0650"), TODAY),
        BenchmarkConstituent(benchmarkId, "NVDA", BigDecimal("0.0600"), TODAY),
    )

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE benchmark_returns, benchmark_constituents, benchmarks RESTART IDENTITY CASCADE")
        }
    }

    test("GET /api/v1/benchmarks returns empty list when no benchmarks exist") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.get("/api/v1/benchmarks")
            response.status shouldBe HttpStatusCode.OK
            val body: JsonArray = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 0
        }
    }

    test("GET /api/v1/benchmarks returns list of benchmarks") {
        benchmarkRepo.save(sampleBenchmark())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.get("/api/v1/benchmarks")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonArray = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 1
            body[0].jsonObject["benchmarkId"]?.jsonPrimitive?.content shouldBe "SP500"
            body[0].jsonObject["name"]?.jsonPrimitive?.content shouldBe "S&P 500"
            body[0].jsonObject["description"]?.jsonPrimitive?.content shouldBe "Large-cap US equity index"
        }
    }

    test("POST /api/v1/benchmarks creates a benchmark and returns 201") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.post("/api/v1/benchmarks") {
                contentType(ContentType.Application.Json)
                setBody("""{"benchmarkId":"MSCIW","name":"MSCI World","description":"Global developed market index"}""")
            }
            response.status shouldBe HttpStatusCode.Created

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["benchmarkId"]?.jsonPrimitive?.content shouldBe "MSCIW"
            body["name"]?.jsonPrimitive?.content shouldBe "MSCI World"

            val saved = benchmarkRepo.findById("MSCIW")!!
            saved.benchmarkId shouldBe "MSCIW"
            saved.name shouldBe "MSCI World"
            saved.description shouldBe "Global developed market index"
        }
    }

    test("POST /api/v1/benchmarks creates a benchmark without description") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.post("/api/v1/benchmarks") {
                contentType(ContentType.Application.Json)
                setBody("""{"benchmarkId":"SPXTR","name":"S&P 500 Total Return"}""")
            }
            response.status shouldBe HttpStatusCode.Created

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["benchmarkId"]?.jsonPrimitive?.content shouldBe "SPXTR"

            val saved = benchmarkRepo.findById("SPXTR")!!
            saved.description shouldBe null
        }
    }

    test("GET /api/v1/benchmarks/{id} returns 404 when benchmark does not exist") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.get("/api/v1/benchmarks/MISSING")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /api/v1/benchmarks/{id} returns benchmark with constituents") {
        benchmarkRepo.save(sampleBenchmark())
        benchmarkRepo.replaceConstituents("SP500", sampleConstituents())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.get("/api/v1/benchmarks/SP500?asOfDate=2026-03-25")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["benchmarkId"]?.jsonPrimitive?.content shouldBe "SP500"
            body["name"]?.jsonPrimitive?.content shouldBe "S&P 500"

            val constituents = body["constituents"]?.jsonArray ?: error("missing constituents")
            constituents.size shouldBe 3
            constituents[0].jsonObject["instrumentId"]?.jsonPrimitive?.content shouldBe "AAPL"
            constituents[0].jsonObject["weight"]?.jsonPrimitive?.content shouldBe "0.0700"
        }
    }

    test("GET /api/v1/benchmarks/{id} returns the most recent constituents on or before asOfDate") {
        // Constituents are a point-in-time snapshot; a query for a later date must
        // resolve the latest snapshot on or before it (not require an exact match).
        // This is what lets attribution work when callers default asOfDate to today.
        benchmarkRepo.save(sampleBenchmark())
        benchmarkRepo.replaceConstituents("SP500", sampleConstituents()) // dated 2026-03-25

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.get("/api/v1/benchmarks/SP500?asOfDate=2026-05-01")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val constituents = body["constituents"]?.jsonArray ?: error("missing constituents")
            constituents.size shouldBe 3
            constituents[0].jsonObject["instrumentId"]?.jsonPrimitive?.content shouldBe "AAPL"
        }
    }

    test("PUT /api/v1/benchmarks/{id}/constituents replaces constituent weights") {
        benchmarkRepo.save(sampleBenchmark())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.put("/api/v1/benchmarks/SP500/constituents") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "asOfDate": "2026-03-25",
                        "constituents": [
                            {"instrumentId": "AAPL", "weight": "0.0700"},
                            {"instrumentId": "MSFT", "weight": "0.0650"}
                        ]
                    }
                """.trimIndent())
            }
            response.status shouldBe HttpStatusCode.NoContent

            val saved = benchmarkRepo.findConstituents("SP500", TODAY)
            saved.size shouldBe 2
            saved.map { it.instrumentId }.toSet() shouldBe setOf("AAPL", "MSFT")
        }
    }

    test("PUT /api/v1/benchmarks/{id}/constituents returns 404 when benchmark does not exist") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.put("/api/v1/benchmarks/MISSING/constituents") {
                contentType(ContentType.Application.Json)
                setBody("""{"asOfDate":"2026-03-25","constituents":[]}""")
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST /api/v1/benchmarks/{id}/returns records a daily return and returns 201") {
        benchmarkRepo.save(sampleBenchmark())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.post("/api/v1/benchmarks/SP500/returns") {
                contentType(ContentType.Application.Json)
                setBody("""{"returnDate":"2026-03-25","dailyReturn":"0.0125"}""")
            }
            response.status shouldBe HttpStatusCode.Created

            val saved = benchmarkRepo.findReturns("SP500", TODAY, TODAY)
            saved.size shouldBe 1
            saved[0].dailyReturn shouldBe BigDecimal("0.0125")
        }
    }

    test("POST /api/v1/benchmarks/{id}/returns returns 404 when benchmark does not exist") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.post("/api/v1/benchmarks/MISSING/returns") {
                contentType(ContentType.Application.Json)
                setBody("""{"returnDate":"2026-03-25","dailyReturn":"0.01"}""")
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /api/v1/benchmarks/{id} with invalid asOfDate returns 400") {
        benchmarkRepo.save(sampleBenchmark())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, benchmarkService = benchmarkService) }

            val response = client.get("/api/v1/benchmarks/SP500?asOfDate=not-a-date")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }
})

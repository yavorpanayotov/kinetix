package com.kinetix.referencedata.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.CreditSpread
import com.kinetix.common.model.DividendYield
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.LiquidityTier
import com.kinetix.referencedata.cache.ReferenceDataCache
import com.kinetix.referencedata.kafka.ReferenceDataPublisher
import com.kinetix.referencedata.model.InstrumentLiquidity
import com.kinetix.referencedata.module
import com.kinetix.referencedata.persistence.DatabaseTestSetup
import com.kinetix.referencedata.persistence.ExposedCreditSpreadRepository
import com.kinetix.referencedata.persistence.ExposedDividendYieldRepository
import com.kinetix.referencedata.persistence.ExposedInstrumentLiquidityRepository
import com.kinetix.referencedata.service.InstrumentLiquidityService
import com.kinetix.referencedata.service.ReferenceDataIngestionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import java.time.Instant

class LiquidityRoutesAcceptanceTest : FunSpec({

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
    val liquidityRepo = ExposedInstrumentLiquidityRepository(db)
    val liquidityService = InstrumentLiquidityService(liquidityRepo)

    val NOW = Instant.now()
    val STALE = NOW.minusSeconds(3 * 24 * 3600)  // 3 days ago

    fun sampleLiquidity(instrumentId: String = "AAPL", adv: Double = 10_000_000.0) = InstrumentLiquidity(
        instrumentId = instrumentId,
        adv = adv,
        bidAskSpreadBps = 5.0,
        assetClass = AssetClass.EQUITY,
        liquidityTier = LiquidityTier.LIQUID,
        advUpdatedAt = NOW,
        createdAt = NOW,
        updatedAt = NOW,
    )

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE instrument_liquidity RESTART IDENTITY CASCADE")
        }
    }

    test("GET /api/v1/liquidity/{id} returns liquidity data for a known instrument") {
        liquidityRepo.upsert(sampleLiquidity())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.get("/api/v1/liquidity/AAPL")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["instrumentId"]?.jsonPrimitive?.content shouldBe "AAPL"
            body["adv"]?.jsonPrimitive?.content?.toDouble() shouldBe 10_000_000.0
            body["bidAskSpreadBps"]?.jsonPrimitive?.content?.toDouble() shouldBe 5.0
            body["assetClass"]?.jsonPrimitive?.content shouldBe "EQUITY"
            body["advStale"]?.jsonPrimitive?.content?.toBoolean() shouldBe false
        }
    }

    test("GET /api/v1/liquidity/{id} returns 404 when instrument not found") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.get("/api/v1/liquidity/UNKNOWN")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /api/v1/liquidity/{id} marks advStale true when ADV older than 2 days") {
        val stale = sampleLiquidity().copy(advUpdatedAt = STALE)
        liquidityRepo.upsert(stale)

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.get("/api/v1/liquidity/AAPL")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["advStale"]?.jsonPrimitive?.content?.toBoolean() shouldBe true
            body["advStalenessDays"]?.jsonPrimitive?.content?.toInt()!! >= 2
        }
    }

    test("POST /api/v1/liquidity creates or updates a liquidity record and returns 201") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.post("/api/v1/liquidity") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "instrumentId": "MSFT",
                        "adv": 25000000.0,
                        "bidAskSpreadBps": 3.5,
                        "assetClass": "EQUITY"
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.Created

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["instrumentId"]?.jsonPrimitive?.content shouldBe "MSFT"
            body["adv"]?.jsonPrimitive?.content?.toDouble() shouldBe 25_000_000.0

            val saved = liquidityRepo.findById("MSFT")!!
            saved.instrumentId shouldBe "MSFT"
            saved.adv shouldBe 25_000_000.0
        }
    }

    test("GET /api/v1/liquidity/{id} includes liquidityTier in response") {
        val liquidSample = sampleLiquidity().copy(adv = 25_000_000.0) // adv=25M, spread=5bps → LIQUID
        liquidityRepo.upsert(liquidSample)

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.get("/api/v1/liquidity/AAPL")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["liquidityTier"]?.jsonPrimitive?.content shouldBe "LIQUID"
        }
    }

    test("POST /api/v1/liquidity classifies HIGH_LIQUID for high ADV and tight spread") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.post("/api/v1/liquidity") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "instrumentId": "SPY",
                        "adv": 80000000.0,
                        "bidAskSpreadBps": 1.0,
                        "assetClass": "EQUITY"
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.Created

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["liquidityTier"]?.jsonPrimitive?.content shouldBe "HIGH_LIQUID"

            val saved = liquidityRepo.findById("SPY")!!
            saved.liquidityTier.name shouldBe "HIGH_LIQUID"
        }
    }

    test("POST /api/v1/liquidity classifies ILLIQUID for low ADV") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.post("/api/v1/liquidity") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "instrumentId": "ILLIQ-1",
                        "adv": 100000.0,
                        "bidAskSpreadBps": 200.0,
                        "assetClass": "FIXED_INCOME"
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.Created

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["liquidityTier"]?.jsonPrimitive?.content shouldBe "ILLIQUID"

            val saved = liquidityRepo.findById("ILLIQ-1")!!
            saved.liquidityTier.name shouldBe "ILLIQUID"
        }
    }

    test("POST /api/v1/liquidity accepts advShares, marketDepthScore, and source fields") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.post("/api/v1/liquidity") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "instrumentId": "AAPL",
                        "adv": 50000000.0,
                        "bidAskSpreadBps": 2.0,
                        "assetClass": "EQUITY",
                        "advShares": 450000.0,
                        "marketDepthScore": 9.5,
                        "source": "bloomberg"
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.Created

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["advShares"]?.jsonPrimitive?.content?.toDouble() shouldBe 450_000.0
            body["marketDepthScore"]?.jsonPrimitive?.content?.toDouble() shouldBe 9.5
            body["source"]?.jsonPrimitive?.content shouldBe "bloomberg"

            val saved = liquidityRepo.findById("AAPL")!!
            saved.advShares shouldBe 450_000.0
            saved.marketDepthScore shouldBe 9.5
            saved.source shouldBe "bloomberg"
        }
    }

    test("POST /api/v1/liquidity defaults new fields when not provided") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.post("/api/v1/liquidity") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "instrumentId": "MSFT",
                        "adv": 25000000.0,
                        "bidAskSpreadBps": 3.5,
                        "assetClass": "EQUITY"
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.Created

            val saved = liquidityRepo.findById("MSFT")!!
            saved.advShares shouldBe null
            saved.marketDepthScore shouldBe null
            saved.source shouldBe "unknown"
        }
    }

    test("GET /api/v1/liquidity/{id} includes advShares, marketDepthScore, and source in response") {
        val withNewFields = sampleLiquidity().copy(
            advShares = 500_000.0,
            marketDepthScore = 8.5,
            source = "bloomberg",
        )
        liquidityRepo.upsert(withNewFields)

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.get("/api/v1/liquidity/AAPL")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["advShares"]?.jsonPrimitive?.content?.toDouble() shouldBe 500_000.0
            body["marketDepthScore"]?.jsonPrimitive?.content?.toDouble() shouldBe 8.5
            body["source"]?.jsonPrimitive?.content shouldBe "bloomberg"
        }
    }

    test("GET /api/v1/liquidity/batch returns liquidity for multiple instruments") {
        liquidityRepo.upsert(sampleLiquidity("AAPL"))
        liquidityRepo.upsert(sampleLiquidity("MSFT", adv = 25_000_000.0))

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, liquidityService = liquidityService) }

            val response = client.get("/api/v1/liquidity/batch?ids=AAPL,MSFT")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonArray = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 2
        }
    }
})

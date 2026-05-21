package com.kinetix.referencedata.routes

import com.kinetix.common.model.CreditSpread
import com.kinetix.common.model.Desk
import com.kinetix.common.model.DeskId
import com.kinetix.common.model.Division
import com.kinetix.common.model.DivisionId
import com.kinetix.common.model.DividendYield
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Trader
import com.kinetix.common.model.TraderId
import com.kinetix.referencedata.cache.ReferenceDataCache
import com.kinetix.referencedata.kafka.ReferenceDataPublisher
import com.kinetix.referencedata.module
import com.kinetix.referencedata.persistence.DatabaseTestSetup
import com.kinetix.referencedata.persistence.ExposedCreditSpreadRepository
import com.kinetix.referencedata.persistence.ExposedDeskRepository
import com.kinetix.referencedata.persistence.ExposedDividendYieldRepository
import com.kinetix.referencedata.persistence.ExposedDivisionRepository
import com.kinetix.referencedata.persistence.ExposedTraderRepository
import com.kinetix.referencedata.service.ReferenceDataIngestionService
import com.kinetix.referencedata.service.TraderService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.math.BigDecimal

class TraderRoutesAcceptanceTest : FunSpec({

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
    val divisionRepo = ExposedDivisionRepository(db)
    val deskRepo = ExposedDeskRepository(db)
    val traderRepo = ExposedTraderRepository(db)
    val traderService = TraderService(traderRepo, deskRepo)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE traders, desks, divisions RESTART IDENTITY CASCADE")
        }
        divisionRepo.save(Division(id = DivisionId("equities"), name = "Equities"))
        deskRepo.save(
            Desk(id = DeskId("equity-growth"), name = "Equity Growth", divisionId = DivisionId("equities")),
        )
    }

    test("GET /api/v1/traders returns traders with trader_id and timestamp fields") {
        traderRepo.save(
            Trader(
                id = TraderId("tr-eg-001"),
                name = "Sarah Chen",
                deskId = DeskId("equity-growth"),
                email = "sarah.chen@kinetix.test",
                notionalLimitUsd = BigDecimal("150000000"),
            ),
        )

        testApplication {
            application {
                module(
                    dividendYieldRepo,
                    creditSpreadRepo,
                    ingestionService,
                    traderService = traderService,
                )
            }

            val response = client.get("/api/v1/traders")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonArray = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 1
            val trader = body[0].jsonObject
            trader["trader_id"]?.jsonPrimitive?.content shouldBe "tr-eg-001"
            trader["id"] shouldBe null
            trader["name"]?.jsonPrimitive?.content shouldBe "Sarah Chen"
            trader["deskId"]?.jsonPrimitive?.content shouldBe "equity-growth"
            trader["created_at"]?.jsonPrimitive?.content shouldContain "T"
            trader["updated_at"]?.jsonPrimitive?.content shouldContain "T"
        }
    }

    test("GET /api/v1/traders/{id} returns a single trader keyed by trader_id") {
        traderRepo.save(
            Trader(
                id = TraderId("tr-eg-001"),
                name = "Sarah Chen",
                deskId = DeskId("equity-growth"),
            ),
        )

        testApplication {
            application {
                module(
                    dividendYieldRepo,
                    creditSpreadRepo,
                    ingestionService,
                    traderService = traderService,
                )
            }

            val response = client.get("/api/v1/traders/tr-eg-001")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["trader_id"]?.jsonPrimitive?.content shouldBe "tr-eg-001"
            body["created_at"]?.jsonPrimitive?.content shouldContain "T"
            body["updated_at"]?.jsonPrimitive?.content shouldContain "T"
        }
    }

    test("GET /api/v1/traders/{id} returns 404 when not found") {
        testApplication {
            application {
                module(
                    dividendYieldRepo,
                    creditSpreadRepo,
                    ingestionService,
                    traderService = traderService,
                )
            }

            client.get("/api/v1/traders/unknown").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST /api/v1/traders accepts trader_id and echoes trader_id with timestamps") {
        testApplication {
            application {
                module(
                    dividendYieldRepo,
                    creditSpreadRepo,
                    ingestionService,
                    traderService = traderService,
                )
            }

            val response = client.post("/api/v1/traders") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"trader_id":"tr-eg-002","name":"Marcus Webb","deskId":"equity-growth"}""",
                )
            }
            response.status shouldBe HttpStatusCode.Created

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["trader_id"]?.jsonPrimitive?.content shouldBe "tr-eg-002"
            body["name"]?.jsonPrimitive?.content shouldBe "Marcus Webb"
            body["created_at"]?.jsonPrimitive?.content shouldContain "T"
            body["updated_at"]?.jsonPrimitive?.content shouldContain "T"

            traderService.findById(TraderId("tr-eg-002"))?.name shouldBe "Marcus Webb"
        }
    }

    test("GET /api/v1/desks/{deskId}/traders lists traders on a desk with trader_id") {
        traderRepo.save(
            Trader(id = TraderId("tr-eg-001"), name = "Sarah Chen", deskId = DeskId("equity-growth")),
        )

        testApplication {
            application {
                module(
                    dividendYieldRepo,
                    creditSpreadRepo,
                    ingestionService,
                    traderService = traderService,
                )
            }

            val response = client.get("/api/v1/desks/equity-growth/traders")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonArray = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 1
            body[0].jsonObject["trader_id"]?.jsonPrimitive?.content shouldBe "tr-eg-001"
        }
    }
})

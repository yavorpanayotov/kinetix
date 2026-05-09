package com.kinetix.referencedata.routes

import com.kinetix.common.model.CreditSpread
import com.kinetix.common.model.DividendYield
import com.kinetix.common.model.InstrumentId
import com.kinetix.referencedata.cache.ReferenceDataCache
import com.kinetix.referencedata.kafka.ReferenceDataPublisher
import com.kinetix.referencedata.model.Counterparty
import com.kinetix.referencedata.model.NettingAgreement
import com.kinetix.referencedata.module
import com.kinetix.referencedata.persistence.DatabaseTestSetup
import com.kinetix.referencedata.persistence.ExposedCounterpartyRepository
import com.kinetix.referencedata.persistence.ExposedCreditSpreadRepository
import com.kinetix.referencedata.persistence.ExposedDividendYieldRepository
import com.kinetix.referencedata.persistence.ExposedNettingAgreementRepository
import com.kinetix.referencedata.service.CounterpartyService
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
import java.math.BigDecimal
import java.time.Instant

class CounterpartyRoutesAcceptanceTest : FunSpec({

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
    val counterpartyRepo = ExposedCounterpartyRepository(db)
    val nettingRepo = ExposedNettingAgreementRepository(db)
    val counterpartyService = CounterpartyService(counterpartyRepo, nettingRepo)

    val NOW = Instant.parse("2026-03-24T10:00:00Z")

    fun sampleCounterparty(id: String = "CP-GS") = Counterparty(
        counterpartyId = id,
        legalName = "Goldman Sachs Bank USA",
        shortName = "Goldman Sachs",
        lei = "784F5XWPLTWKTBV3E584",
        ratingSp = "A+",
        ratingMoodys = "A1",
        ratingFitch = "A+",
        sector = "FINANCIALS",
        country = "US",
        isFinancial = true,
        pd1y = BigDecimal("0.00050"),
        lgd = BigDecimal("0.400000"),
        cdsSpreadBps = BigDecimal("65.00"),
        createdAt = NOW,
        updatedAt = NOW,
    )

    fun sampleNettingAgreement(nettingSetId: String = "NS-GS-001", counterpartyId: String = "CP-GS") = NettingAgreement(
        nettingSetId = nettingSetId,
        counterpartyId = counterpartyId,
        agreementType = "ISDA_2002",
        closeOutNetting = true,
        csaThreshold = BigDecimal("5000000.000000"),
        currency = "USD",
        createdAt = NOW,
        updatedAt = NOW,
    )

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE netting_agreements, counterparty_master RESTART IDENTITY CASCADE")
        }
    }

    test("GET /api/v1/counterparties returns list of all counterparties") {
        counterpartyRepo.upsert(sampleCounterparty())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.get("/api/v1/counterparties")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonArray = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 1
            body[0].jsonObject["counterpartyId"]?.jsonPrimitive?.content shouldBe "CP-GS"
        }
    }

    test("GET /api/v1/counterparties/{id} returns counterparty when found") {
        counterpartyRepo.upsert(sampleCounterparty())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.get("/api/v1/counterparties/CP-GS")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["counterpartyId"]?.jsonPrimitive?.content shouldBe "CP-GS"
            body["legalName"]?.jsonPrimitive?.content shouldBe "Goldman Sachs Bank USA"
            body["ratingSp"]?.jsonPrimitive?.content shouldBe "A+"
            body["sector"]?.jsonPrimitive?.content shouldBe "FINANCIALS"
            body["isFinancial"]?.jsonPrimitive?.content?.toBoolean() shouldBe true
            body["lgd"]?.jsonPrimitive?.content?.toDouble() shouldBe 0.4
            body["cdsSpreadBps"]?.jsonPrimitive?.content?.toDouble() shouldBe 65.0
        }
    }

    test("GET /api/v1/counterparties/{id} returns 404 when not found") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.get("/api/v1/counterparties/UNKNOWN")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("POST /api/v1/counterparties creates counterparty and returns 201") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.post("/api/v1/counterparties") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "counterpartyId": "CP-TEST",
                        "legalName": "Test Bank Inc.",
                        "shortName": "Test Bank",
                        "sector": "FINANCIALS",
                        "isFinancial": true,
                        "lgd": 0.40,
                        "ratingSp": "BBB",
                        "cdsSpreadBps": 120.0
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.Created

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["counterpartyId"]?.jsonPrimitive?.content shouldBe "CP-TEST"
            body["legalName"]?.jsonPrimitive?.content shouldBe "Test Bank Inc."
            body["ratingSp"]?.jsonPrimitive?.content shouldBe "BBB"

            val saved = counterpartyRepo.findById("CP-TEST")!!
            saved.counterpartyId shouldBe "CP-TEST"
            saved.sector shouldBe "FINANCIALS"
        }
    }

    test("POST /api/v1/counterparties rejects invalid lgd") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.post("/api/v1/counterparties") {
                contentType(ContentType.Application.Json)
                setBody("""{"counterpartyId":"CP-X","legalName":"X","shortName":"X","lgd":0.0}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /api/v1/counterparties/{id}/netting-sets returns netting agreements for counterparty") {
        counterpartyRepo.upsert(sampleCounterparty())
        nettingRepo.upsert(sampleNettingAgreement())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.get("/api/v1/counterparties/CP-GS/netting-sets")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonArray = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 1
            body[0].jsonObject["nettingSetId"]?.jsonPrimitive?.content shouldBe "NS-GS-001"
            body[0].jsonObject["agreementType"]?.jsonPrimitive?.content shouldBe "ISDA_2002"
        }
    }

    test("POST /api/v1/netting-agreements creates netting agreement and returns 201") {
        counterpartyRepo.upsert(sampleCounterparty("CP-TEST"))

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.post("/api/v1/netting-agreements") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "nettingSetId": "NS-TEST-001",
                        "counterpartyId": "CP-TEST",
                        "agreementType": "ISDA_2002",
                        "closeOutNetting": true,
                        "currency": "USD"
                    }
                    """.trimIndent()
                )
            }
            response.status shouldBe HttpStatusCode.Created

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["nettingSetId"]?.jsonPrimitive?.content shouldBe "NS-TEST-001"
            body["counterpartyId"]?.jsonPrimitive?.content shouldBe "CP-TEST"
            body["agreementType"]?.jsonPrimitive?.content shouldBe "ISDA_2002"
            body["closeOutNetting"]?.jsonPrimitive?.content?.toBoolean() shouldBe true

            val saved = nettingRepo.findById("NS-TEST-001")!!
            saved.nettingSetId shouldBe "NS-TEST-001"
        }
    }

    test("GET /api/v1/netting-agreements/{id} returns agreement when found") {
        counterpartyRepo.upsert(sampleCounterparty())
        nettingRepo.upsert(sampleNettingAgreement())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.get("/api/v1/netting-agreements/NS-GS-001")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["nettingSetId"]?.jsonPrimitive?.content shouldBe "NS-GS-001"
            body["counterpartyId"]?.jsonPrimitive?.content shouldBe "CP-GS"
        }
    }

    test("GET /api/v1/netting-agreements/{id} returns 404 when not found") {
        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.get("/api/v1/netting-agreements/NS-UNKNOWN")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("response includes agreementStatus=EXPIRED when expiryDate is in the past") {
        counterpartyRepo.upsert(sampleCounterparty())
        val expiredAgreement = sampleNettingAgreement().copy(
            expiryDate = Instant.parse("2025-12-01T00:00:00Z"),
        )
        nettingRepo.upsert(expiredAgreement)

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.get("/api/v1/netting-agreements/NS-GS-001")
            response.status shouldBe HttpStatusCode.OK
            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["agreementStatus"]?.jsonPrimitive?.content shouldBe "EXPIRED"
            body["expiryDate"]?.jsonPrimitive?.content shouldBe "2025-12-01T00:00:00Z"
        }
    }

    test("response includes agreementStatus=ACTIVE when expiryDate is null") {
        counterpartyRepo.upsert(sampleCounterparty())
        nettingRepo.upsert(sampleNettingAgreement())

        testApplication {
            application { module(dividendYieldRepo, creditSpreadRepo, ingestionService, counterpartyService = counterpartyService) }

            val response = client.get("/api/v1/netting-agreements/NS-GS-001")
            response.status shouldBe HttpStatusCode.OK
            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["agreementStatus"]?.jsonPrimitive?.content shouldBe "ACTIVE"
        }
    }
})

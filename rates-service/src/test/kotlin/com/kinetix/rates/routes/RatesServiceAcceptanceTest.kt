package com.kinetix.rates.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.CurvePoint
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.RateSource
import com.kinetix.common.model.RiskFreeRate
import com.kinetix.common.model.Tenor
import com.kinetix.common.model.YieldCurve
import com.kinetix.rates.module
import com.kinetix.rates.persistence.DatabaseTestSetup
import com.kinetix.rates.persistence.ExposedForwardCurveRepository
import com.kinetix.rates.persistence.ExposedRiskFreeRateRepository
import com.kinetix.rates.persistence.ExposedYieldCurveRepository
import com.kinetix.rates.service.RateIngestionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Contract tests verifying that the rates-service HTTP response shape can be
 * consumed by the risk-orchestrator's HttpRatesServiceClient DTOs.
 *
 * The orchestrator expects:
 *   YieldCurveDto     { curveId, currency, tenors:[{label,days,rate}], asOfDate, source }
 *   RiskFreeRateDto   { currency, tenor, rate, asOfDate, source }
 *   ForwardCurveDto   { instrumentId, assetClass, points:[{tenor,value}], asOfDate, source }
 */
class RatesServiceAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val yieldCurveRepo = ExposedYieldCurveRepository(db)
    val riskFreeRateRepo = ExposedRiskFreeRateRepository(db)
    val forwardCurveRepo = ExposedForwardCurveRepository(db)
    val ingestionService = mockk<RateIngestionService>(relaxed = true)

    val USD = Currency.getInstance("USD")
    val AS_OF = Instant.parse("2026-01-15T12:00:00Z")

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE yield_curve_tenors, yield_curves, risk_free_rates, forward_curve_points, forward_curves RESTART IDENTITY CASCADE")
        }
    }

    test("yield curve response shape matches YieldCurveDto consumed by risk-orchestrator") {
        val curve = YieldCurve(
            curveId = "USD-TREASURY",
            currency = USD,
            tenors = listOf(
                Tenor("1M", 30, BigDecimal("0.0430")),
                Tenor("1Y", 365, BigDecimal("0.0510")),
            ),
            asOf = AS_OF,
            source = RateSource.CENTRAL_BANK,
        )
        yieldCurveRepo.save(curve)

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/yield-curves/USD-TREASURY/latest")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            // Fields the orchestrator's YieldCurveDto requires
            body["curveId"]?.jsonPrimitive?.content shouldBe "USD-TREASURY"
            body["currency"]?.jsonPrimitive?.content shouldBe "USD"
            body["asOfDate"]?.jsonPrimitive?.content shouldBe AS_OF.toString()
            body["source"]?.jsonPrimitive?.content shouldBe "CENTRAL_BANK"

            val tenors: JsonArray = body["tenors"]!!.jsonArray
            tenors.size shouldBe 2

            val firstTenor: JsonObject = tenors[0].jsonObject
            firstTenor["label"]?.jsonPrimitive?.content shouldBe "1M"
            firstTenor["days"]?.jsonPrimitive?.int shouldBe 30
            // rate is serialized as a plain-string BigDecimal — orchestrator parses it as BigDecimal(rate)
            firstTenor["rate"] shouldNotBe null
        }
    }

    test("risk-free rate response shape matches RiskFreeRateDto consumed by risk-orchestrator") {
        val rate = RiskFreeRate(
            currency = USD,
            tenor = "3M",
            rate = 0.0525,
            asOfDate = AS_OF,
            source = RateSource.CENTRAL_BANK,
        )
        riskFreeRateRepo.save(rate)

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/risk-free/USD/latest?tenor=3M")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            // Fields the orchestrator's RiskFreeRateDto requires
            body["currency"]?.jsonPrimitive?.content shouldBe "USD"
            body["tenor"]?.jsonPrimitive?.content shouldBe "3M"
            body["asOfDate"]?.jsonPrimitive?.content shouldBe AS_OF.toString()
            body["source"]?.jsonPrimitive?.content shouldBe "CENTRAL_BANK"
            // rate is serialized as a plain-string — orchestrator parses it as toDouble()
            body["rate"] shouldNotBe null
        }
    }

    test("forward curve response shape matches ForwardCurveDto consumed by risk-orchestrator") {
        val curve = ForwardCurve(
            instrumentId = InstrumentId("EURUSD"),
            assetClass = AssetClass.FX,
            points = listOf(
                CurvePoint("1M", 1.0855),
                CurvePoint("3M", 1.0870),
            ),
            asOfDate = AS_OF,
            source = RateSource.REUTERS,
        )
        forwardCurveRepo.save(curve)

        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/forwards/EURUSD/latest")
            response.status shouldBe HttpStatusCode.OK

            val body: JsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            // Fields the orchestrator's ForwardCurveDto requires
            body["instrumentId"]?.jsonPrimitive?.content shouldBe "EURUSD"
            body["assetClass"]?.jsonPrimitive?.content shouldBe "FX"
            body["asOfDate"]?.jsonPrimitive?.content shouldBe AS_OF.toString()
            body["source"]?.jsonPrimitive?.content shouldBe "REUTERS"

            val points: JsonArray = body["points"]!!.jsonArray
            points.size shouldBe 2

            val firstPoint: JsonObject = points[0].jsonObject
            firstPoint["tenor"]?.jsonPrimitive?.content shouldBe "1M"
            // value is serialized as a plain-string — orchestrator parses it as toDouble()
            firstPoint["value"] shouldNotBe null
        }
    }

    test("yield curve endpoint returns 404 when curve does not exist") {
        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/yield-curves/UNKNOWN/latest")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("risk-free rate endpoint returns 404 when rate does not exist") {
        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/risk-free/JPY/latest?tenor=O/N")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("forward curve endpoint returns 404 when instrument does not exist") {
        testApplication {
            application { module(yieldCurveRepo, riskFreeRateRepo, forwardCurveRepo, ingestionService) }

            val response = client.get("/api/v1/rates/forwards/UNKNOWN/latest")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})

package com.kinetix.gateway.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * Regression: risk-orchestrator's [RiskMappers.toDto] emits
 * `"positionGreeks": null` (via `takeIf { isNotEmpty() }`) when no per-position
 * Greeks are computed for a book. The gateway client DTO must accept that null
 * on the wire and normalise to an empty list at the domain edge — otherwise
 * cached VaR reads from risk-orchestrator's Redis-backed cache surface as
 * `400 invalid_request_body` on `GET /api/v1/risk/var/{bookId}`.
 */
class ValuationResultDtoTest : FunSpec({

    val json = Json { ignoreUnknownKeys = true }

    val baseJson = """
        {
            "bookId": "balanced-income",
            "calculationType": "PARAMETRIC",
            "confidenceLevel": "CL_95",
            "varValue": "1000.00",
            "expectedShortfall": "1250.00",
            "componentBreakdown": [],
            "calculatedAt": "2026-05-28T08:00:00Z"
        }
    """.trimIndent()

    test("deserialises with positionGreeks=null (the wire shape risk-orchestrator emits)") {
        val withNull = baseJson.dropLast(1) + ",\"positionGreeks\": null }"

        val dto = json.decodeFromString<ValuationResultDto>(withNull)

        dto.positionGreeks shouldBe null
        dto.toDomain().positionGreeks shouldBe emptyList()
    }

    test("deserialises with positionGreeks absent altogether") {
        val dto = json.decodeFromString<ValuationResultDto>(baseJson)

        dto.toDomain().positionGreeks shouldBe emptyList()
    }

    test("preserves positionGreeks when populated") {
        val populated = baseJson.dropLast(1) + """
            ,"positionGreeks": [
              {"instrumentId":"AAPL","delta":"0.50","gamma":"0.01","vega":"0.20","theta":"-0.05","rho":"0.10"}
            ] }
        """.trimIndent()

        val dto = json.decodeFromString<ValuationResultDto>(populated)
        val domain = dto.toDomain()

        domain.positionGreeks.size shouldBe 1
        domain.positionGreeks[0].instrumentId shouldBe "AAPL"
        domain.positionGreeks[0].delta shouldBe 0.50
    }
})

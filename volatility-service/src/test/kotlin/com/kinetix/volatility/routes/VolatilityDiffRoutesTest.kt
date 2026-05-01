package com.kinetix.volatility.routes

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import com.kinetix.common.model.VolatilitySource
import com.kinetix.volatility.module
import com.kinetix.volatility.persistence.VolSurfaceRepository
import com.kinetix.volatility.service.VolatilityIngestionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant

class VolatilityDiffRoutesTest : FunSpec({

    val volSurfaceRepo = mockk<VolSurfaceRepository>()
    val ingestionService = mockk<VolatilityIngestionService>()

    val BASE_DATE = Instant.parse("2026-03-24T12:00:00Z")
    val COMPARE_DATE = Instant.parse("2026-03-23T12:00:00Z")

    val baseSurface = VolSurface(
        instrumentId = InstrumentId("AAPL"),
        asOf = BASE_DATE,
        points = listOf(
            VolPoint(BigDecimal("100"), 30, BigDecimal("0.2500")),
            VolPoint(BigDecimal("110"), 30, BigDecimal("0.2200")),
            VolPoint(BigDecimal("100"), 90, BigDecimal("0.2800")),
        ),
        source = VolatilitySource.BLOOMBERG,
    )

    val compareSurface = VolSurface(
        instrumentId = InstrumentId("AAPL"),
        asOf = COMPARE_DATE,
        points = listOf(
            VolPoint(BigDecimal("100"), 30, BigDecimal("0.2400")),
            VolPoint(BigDecimal("110"), 30, BigDecimal("0.2100")),
            VolPoint(BigDecimal("100"), 90, BigDecimal("0.2700")),
        ),
        source = VolatilitySource.BLOOMBERG,
    )

    test("diff endpoint returns point-by-point vol differences between two surfaces") {
        coEvery { volSurfaceRepo.findLatest(InstrumentId("AAPL")) } returns baseSurface
        coEvery {
            volSurfaceRepo.findAtOrBefore(InstrumentId("AAPL"), COMPARE_DATE)
        } returns compareSurface

        testApplication {
            application { module(volSurfaceRepo, ingestionService) }

            val response = client.get(
                "/api/v1/volatility/AAPL/surface/diff?compareDate=${COMPARE_DATE}"
            )
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["instrumentId"]?.jsonPrimitive?.content shouldBe "AAPL"
            body["baseDate"]?.jsonPrimitive?.content shouldBe BASE_DATE.toString()
            body["compareDate"]?.jsonPrimitive?.content shouldBe COMPARE_DATE.toString()

            val diffs = body["diffs"]!!.jsonArray
            diffs.size shouldBe 3

            // 0.2500 - 0.2400 = 0.0100
            val first = diffs[0].jsonObject
            first["strike"]?.jsonPrimitive?.double shouldBe 100.0
            first["maturityDays"]?.jsonPrimitive?.int shouldBe 30
            first["baseVol"]?.jsonPrimitive?.double shouldBe 0.25
            first["compareVol"]?.jsonPrimitive?.double shouldBe 0.24
            // diff is approximately 0.01 (allow floating-point tolerance)
            val diff = first["diff"]?.jsonPrimitive?.double ?: 0.0
            (diff > 0.009 && diff < 0.011) shouldBe true
        }
    }

    test("diff endpoint returns 404 when no base surface exists") {
        coEvery { volSurfaceRepo.findLatest(InstrumentId("UNKNOWN")) } returns null

        testApplication {
            application { module(volSurfaceRepo, ingestionService) }

            val response = client.get(
                "/api/v1/volatility/UNKNOWN/surface/diff?compareDate=${COMPARE_DATE}"
            )
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("diff endpoint returns 404 when compare surface does not exist") {
        coEvery { volSurfaceRepo.findLatest(InstrumentId("AAPL")) } returns baseSurface
        coEvery {
            volSurfaceRepo.findAtOrBefore(InstrumentId("AAPL"), COMPARE_DATE)
        } returns null

        testApplication {
            application { module(volSurfaceRepo, ingestionService) }

            val response = client.get(
                "/api/v1/volatility/AAPL/surface/diff?compareDate=${COMPARE_DATE}"
            )
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("diff endpoint returns 400 when compareDate parameter is missing") {
        testApplication {
            application { module(volSurfaceRepo, ingestionService) }

            val response = client.get("/api/v1/volatility/AAPL/surface/diff")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("diff endpoint returns 400 when compareDate is not a valid ISO-8601 instant") {
        testApplication {
            application { module(volSurfaceRepo, ingestionService) }

            val response = client.get("/api/v1/volatility/AAPL/surface/diff?compareDate=not-a-date")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    // Union-grid interpolation: when the two surfaces have different strike grids, the diff
    // must cover the union of both grids. Missing points are filled by bilinear interpolation
    // in (log K, sqrt T) space — see VolSurfaceDiffTest for the per-scenario interpolation
    // contract. This test exercises the route end-to-end and asserts that the union grid is
    // assembled correctly and that interpolated diffs reflect the surface skew rather than
    // step-jumping to the nearest knot.
    test("diff uses union of both strike grids and bilinear-interpolates missing points") {
        // Base has strikes 100, 110, 120 at maturity 30
        val baseSurfaceUnionGrid = VolSurface(
            instrumentId = InstrumentId("AAPL"),
            asOf = BASE_DATE,
            points = listOf(
                VolPoint(BigDecimal("100"), 30, BigDecimal("0.2000")),
                VolPoint(BigDecimal("110"), 30, BigDecimal("0.2200")),
                VolPoint(BigDecimal("120"), 30, BigDecimal("0.2500")),
            ),
            source = VolatilitySource.BLOOMBERG,
        )
        // Compare has only strikes 100 and 115 at maturity 30 — missing 110 and 120
        val compareSurfaceUnionGrid = VolSurface(
            instrumentId = InstrumentId("AAPL"),
            asOf = COMPARE_DATE,
            points = listOf(
                VolPoint(BigDecimal("100"), 30, BigDecimal("0.1900")),
                VolPoint(BigDecimal("115"), 30, BigDecimal("0.2100")),
            ),
            source = VolatilitySource.BLOOMBERG,
        )

        coEvery { volSurfaceRepo.findLatest(InstrumentId("AAPL")) } returns baseSurfaceUnionGrid
        coEvery {
            volSurfaceRepo.findAtOrBefore(InstrumentId("AAPL"), COMPARE_DATE)
        } returns compareSurfaceUnionGrid

        testApplication {
            application { module(volSurfaceRepo, ingestionService) }

            val response = client.get(
                "/api/v1/volatility/AAPL/surface/diff?compareDate=${COMPARE_DATE}"
            )
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val diffs = body["diffs"]!!.jsonArray

            // Union of {100,110,120} and {100,115} is {100,110,115,120}
            diffs.size shouldBe 4

            val byStrike = diffs.associate {
                it.jsonObject["strike"]!!.jsonPrimitive.double to it.jsonObject
            }

            // Strike 100 — exact match in both: 0.2000 - 0.1900 = 0.0100
            val diff100 = byStrike[100.0]!!["diff"]!!.jsonPrimitive.double
            (diff100 > 0.009 && diff100 < 0.011) shouldBe true

            // Strike 110 — base has it (0.2200); compare interpolates between 100 (0.19) and
            // 115 (0.21) in log-K space. log(110) sits at ~68% of the way from log(100) to
            // log(115), so compare ≈ 0.19 + 0.68 * 0.02 ≈ 0.2036; diff ≈ 0.0164.
            val diff110 = byStrike[110.0]!!["diff"]!!.jsonPrimitive.double
            (diff110 > 0.014 && diff110 < 0.020) shouldBe true

            // Strike 115 — compare has 0.2100; base interpolates between 110 (0.22) and 120
            // (0.25) in log-K. log(115) sits ~51% of the way; base ≈ 0.22 + 0.51 * 0.03 ≈
            // 0.2353; diff ≈ 0.0253.
            byStrike.containsKey(115.0) shouldBe true
            val diff115 = byStrike[115.0]!!["diff"]!!.jsonPrimitive.double
            (diff115 > 0.020 && diff115 < 0.030) shouldBe true

            // Strike 120 — base has 0.2500; compare clamps to its right boundary (115, 0.21)
            // because 120 is outside compare's available strike range. diff = 0.04.
            val diff120 = byStrike[120.0]!!["diff"]!!.jsonPrimitive.double
            (diff120 > 0.039 && diff120 < 0.041) shouldBe true
        }
    }
})

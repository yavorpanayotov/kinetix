package com.kinetix.risk.routes

import com.kinetix.common.model.*
import com.kinetix.risk.model.*
import com.kinetix.risk.routes.dtos.HypotheticalTradeDto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

class WhatIfMapperTest : FunSpec({

    test("maps HypotheticalTradeDto to domain HypotheticalTrade") {
        val dto = HypotheticalTradeDto(
            instrumentId = "SPY",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "500",
            priceAmount = "450.00",
            priceCurrency = "USD",
                    instrumentType = "CASH_EQUITY",
        )

        val domain = dto.toDomain()

        domain.instrumentId shouldBe InstrumentId("SPY")
        domain.assetClass shouldBe AssetClass.EQUITY
        domain.side shouldBe Side.BUY
        domain.quantity shouldBe BigDecimal("500")
        domain.price.amount shouldBe BigDecimal("450.00")
        domain.price.currency shouldBe USD
    }

    test("maps SELL side correctly") {
        val dto = HypotheticalTradeDto(
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "SELL",
            quantity = "100",
            priceAmount = "175.00",
            priceCurrency = "USD",
                    instrumentType = "CASH_EQUITY",
        )

        val domain = dto.toDomain()

        domain.side shouldBe Side.SELL
    }

    test("maps WhatIfResult to WhatIfResponse") {
        val positionRisk = PositionRisk(
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            marketValue = BigDecimal("17000.00"),
            delta = 0.85,
            gamma = 0.02,
            vega = 1500.0,
            varContribution = BigDecimal("5000.00"),
            esContribution = BigDecimal("6250.00"),
            percentageOfTotal = BigDecimal("100.00"),
        )
        val greeks = GreeksResult(
            assetClassGreeks = listOf(
                GreekValues(AssetClass.EQUITY, delta = 0.85, gamma = 0.02, vega = 1500.0),
            ),
            theta = -45.0,
            rho = 120.0,
        )
        val whatIfResult = WhatIfResult(
            baseVaR = 5000.0,
            baseExpectedShortfall = 6250.0,
            baseGreeks = greeks,
            basePositionRisk = listOf(positionRisk),
            hypotheticalVaR = 7000.0,
            hypotheticalExpectedShortfall = 8750.0,
            hypotheticalGreeks = greeks,
            hypotheticalPositionRisk = listOf(positionRisk),
            varChange = 2000.0,
            esChange = 2500.0,
            calculatedAt = Instant.parse("2026-02-28T10:00:00Z"),
        )

        val response = whatIfResult.toResponse()

        response.baseVaR shouldBe "5000.00"
        response.baseExpectedShortfall shouldBe "6250.00"
        response.hypotheticalVaR shouldBe "7000.00"
        response.hypotheticalExpectedShortfall shouldBe "8750.00"
        response.varChange shouldBe "2000.00"
        response.esChange shouldBe "2500.00"
        response.calculatedAt shouldBe "2026-02-28T10:00:00Z"
        response.basePositionRisk shouldBe listOf(positionRisk.toDto())
        response.hypotheticalPositionRisk shouldBe listOf(positionRisk.toDto())
        response.baseGreeks!!.bookId shouldBe ""
        response.baseGreeks!!.theta shouldBe "-45.000000"
        response.baseGreeks!!.rho shouldBe "120.000000"
    }

    test("maps WhatIfResult with null greeks") {
        val whatIfResult = WhatIfResult(
            baseVaR = 5000.0,
            baseExpectedShortfall = 6250.0,
            baseGreeks = null,
            basePositionRisk = emptyList(),
            hypotheticalVaR = 7000.0,
            hypotheticalExpectedShortfall = 8750.0,
            hypotheticalGreeks = null,
            hypotheticalPositionRisk = emptyList(),
            varChange = 2000.0,
            esChange = 2500.0,
            calculatedAt = Instant.parse("2026-02-28T10:00:00Z"),
        )

        val response = whatIfResult.toResponse()

        response.baseGreeks shouldBe null
        response.hypotheticalGreeks shouldBe null
    }

    test("maps all asset classes from DTO to domain") {
        for (assetClass in AssetClass.entries) {
            val dto = HypotheticalTradeDto(
                instrumentId = "TEST",
                assetClass = assetClass.name,
                side = "BUY",
                quantity = "1",
                priceAmount = "100.00",
                priceCurrency = "USD",
                        instrumentType = "CASH_EQUITY",
        )
            dto.toDomain().assetClass shouldBe assetClass
        }
    }

    test("throws IllegalArgumentException for invalid assetClass string") {
        val dto = HypotheticalTradeDto(
            instrumentId = "SPY",
            assetClass = "CRYPTO",
            side = "BUY",
            quantity = "100",
            priceAmount = "450.00",
            priceCurrency = "USD",
                    instrumentType = "CASH_EQUITY",
        )

        shouldThrow<IllegalArgumentException> {
            dto.toDomain()
        }
    }

    test("throws IllegalArgumentException for invalid side string") {
        val dto = HypotheticalTradeDto(
            instrumentId = "SPY",
            assetClass = "EQUITY",
            side = "SHORT",
            quantity = "100",
            priceAmount = "450.00",
            priceCurrency = "USD",
                    instrumentType = "CASH_EQUITY",
        )

        shouldThrow<IllegalArgumentException> {
            dto.toDomain()
        }
    }
})

package com.kinetix.risk.margin

import com.kinetix.common.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun position(
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    averageCost: String = "150.00",
    marketPrice: String = "170.00",
) = Position(
    bookId = BookId("port-1"),
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = Money(BigDecimal(averageCost), USD),
    marketPrice = Money(BigDecimal(marketPrice), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class MarginCalculatorTest : FunSpec({

    val calculator = MarginCalculator()

    test("calculates initial margin for equity positions") {
        val positions = listOf(
            position(instrumentId = "AAPL", assetClass = AssetClass.EQUITY, quantity = "100", marketPrice = "170.00"),
        )

        val result = calculator.calculate(positions)

        // AAPL: abs(100 * 170) * 0.15 = 17000 * 0.15 = 2550
        result.initialMargin shouldBe BigDecimal("2550.00")
        result.currency shouldBe USD
    }

    test("calculates variation margin from MTM change") {
        val positions = listOf(
            position(instrumentId = "AAPL", assetClass = AssetClass.EQUITY, quantity = "100", marketPrice = "170.00"),
        )
        val previousMTM = BigDecimal("15000.00")

        val result = calculator.calculate(positions, previousMTM)

        // Current MTM = 100 * 170 = 17000
        // Variation margin = abs(17000 - 15000) = 2000
        result.variationMargin shouldBe BigDecimal("2000.00")
    }

    test("margin increases with portfolio risk") {
        val lowRiskPositions = listOf(
            position(instrumentId = "BOND1", assetClass = AssetClass.FIXED_INCOME, quantity = "100", marketPrice = "100.00"),
        )
        val highRiskPositions = listOf(
            position(instrumentId = "OIL1", assetClass = AssetClass.COMMODITY, quantity = "100", marketPrice = "100.00"),
        )

        val lowRiskResult = calculator.calculate(lowRiskPositions)
        val highRiskResult = calculator.calculate(highRiskPositions)

        // Fixed income rate = 5%, Commodity rate = 20%
        // Low risk: 10000 * 0.05 = 500
        // High risk: 10000 * 0.20 = 2000
        (highRiskResult.initialMargin > lowRiskResult.initialMargin) shouldBe true
    }

    test("returns zero margin for empty portfolio") {
        val result = calculator.calculate(emptyList())

        result.initialMargin shouldBe BigDecimal.ZERO
        result.variationMargin shouldBe BigDecimal.ZERO
        result.totalMargin shouldBe BigDecimal.ZERO
    }

    test("calculates margin across multiple asset classes") {
        val positions = listOf(
            position(instrumentId = "AAPL", assetClass = AssetClass.EQUITY, quantity = "100", marketPrice = "170.00"),
            position(instrumentId = "BOND1", assetClass = AssetClass.FIXED_INCOME, quantity = "1000", marketPrice = "98.50"),
            position(instrumentId = "EUR/USD", assetClass = AssetClass.FX, quantity = "100000", marketPrice = "1.08"),
        )

        val result = calculator.calculate(positions)

        // EQUITY: 17000 * 0.15 = 2550
        // FIXED_INCOME: 98500 * 0.05 = 4925
        // FX: 108000 * 0.03 = 3240
        // Total = 10715
        result.initialMargin shouldBe BigDecimal("10715.00")
    }

    test("total margin includes both initial and variation margin") {
        val positions = listOf(
            position(instrumentId = "AAPL", assetClass = AssetClass.EQUITY, quantity = "100", marketPrice = "170.00"),
        )
        val previousMTM = BigDecimal("15000.00")

        val result = calculator.calculate(positions, previousMTM)

        // IM = 2550, VM = 2000, total = 4550
        result.totalMargin shouldBe BigDecimal("4550.00")
    }
})

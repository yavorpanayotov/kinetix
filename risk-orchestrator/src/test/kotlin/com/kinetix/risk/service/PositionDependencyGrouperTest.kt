package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.risk.model.DiscoveredDependency
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun position(
    instrumentId: String,
    assetClass: AssetClass = AssetClass.EQUITY,
) = Position(
    bookId = BookId("port-1"),
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    quantity = BigDecimal("100"),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal("170.00"), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class PositionDependencyGrouperTest : FunSpec({

    val grouper = PositionDependencyGrouper()

    test("groups per-instrument dependencies under matching position instrumentId") {
        val positions = listOf(position("AAPL"), position("TSLA"))
        val dependencies = listOf(
            DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY"),
            DiscoveredDependency("HISTORICAL_PRICES", "AAPL", "EQUITY"),
            DiscoveredDependency("SPOT_PRICE", "TSLA", "EQUITY"),
        )

        val result = grouper.group(positions, dependencies)

        result shouldHaveSize 2
        result["AAPL"]!! shouldHaveSize 2
        result["AAPL"]!!.map { it.dataType } shouldContainExactlyInAnyOrder listOf("SPOT_PRICE", "HISTORICAL_PRICES")
        result["TSLA"]!! shouldHaveSize 1
        result["TSLA"]!![0].dataType shouldBe "SPOT_PRICE"
    }

    test("assigns shared dependency with matching assetClass to all positions of that asset class") {
        val positions = listOf(
            position("AAPL", AssetClass.EQUITY),
            position("TSLA", AssetClass.EQUITY),
            position("UST10Y", AssetClass.FIXED_INCOME),
        )
        val dependencies = listOf(
            DiscoveredDependency("YIELD_CURVE", "", "FIXED_INCOME"),
        )

        val result = grouper.group(positions, dependencies)

        result["AAPL"] shouldBe null
        result["TSLA"] shouldBe null
        result["UST10Y"]!! shouldHaveSize 1
        result["UST10Y"]!![0].dataType shouldBe "YIELD_CURVE"
    }

    test("assigns portfolio-level dependency to all positions") {
        val positions = listOf(
            position("AAPL", AssetClass.EQUITY),
            position("UST10Y", AssetClass.FIXED_INCOME),
        )
        val dependencies = listOf(
            DiscoveredDependency("CORRELATION_MATRIX", "", ""),
        )

        val result = grouper.group(positions, dependencies)

        result shouldHaveSize 2
        result["AAPL"]!! shouldHaveSize 1
        result["AAPL"]!![0].dataType shouldBe "CORRELATION_MATRIX"
        result["UST10Y"]!! shouldHaveSize 1
        result["UST10Y"]!![0].dataType shouldBe "CORRELATION_MATRIX"
    }

    test("returns empty map for empty inputs") {
        grouper.group(emptyList(), emptyList()).shouldBeEmpty()
        grouper.group(listOf(position("AAPL")), emptyList()).shouldBeEmpty()
        grouper.group(emptyList(), listOf(DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY"))).shouldBeEmpty()
    }

    test("mixed portfolio: equity gets SPOT_PRICE and CORRELATION_MATRIX, fixed income gets YIELD_CURVE, CREDIT_SPREAD, and CORRELATION_MATRIX") {
        val positions = listOf(
            position("AAPL", AssetClass.EQUITY),
            position("UST10Y", AssetClass.FIXED_INCOME),
        )
        val dependencies = listOf(
            DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY"),
            DiscoveredDependency("YIELD_CURVE", "", "FIXED_INCOME"),
            DiscoveredDependency("CREDIT_SPREAD", "UST10Y", "FIXED_INCOME"),
            DiscoveredDependency("CORRELATION_MATRIX", "", ""),
        )

        val result = grouper.group(positions, dependencies)

        result shouldHaveSize 2

        result["AAPL"]!!.map { it.dataType } shouldContainExactlyInAnyOrder listOf("SPOT_PRICE", "CORRELATION_MATRIX")
        result["UST10Y"]!!.map { it.dataType } shouldContainExactlyInAnyOrder listOf("YIELD_CURVE", "CREDIT_SPREAD", "CORRELATION_MATRIX")
    }
})

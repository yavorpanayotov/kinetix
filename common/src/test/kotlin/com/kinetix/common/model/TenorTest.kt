package com.kinetix.common.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal

class TenorTest : FunSpec({

    test("create Tenor with valid fields") {
        val tenor = Tenor("1Y", 365, BigDecimal("0.05"))
        tenor.label shouldBe "1Y"
        tenor.days shouldBe 365
        tenor.rate shouldBe BigDecimal("0.05")
    }

    test("Tenor with blank label throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            Tenor("", 365, BigDecimal("0.05"))
        }
        shouldThrow<IllegalArgumentException> {
            Tenor("  ", 365, BigDecimal("0.05"))
        }
    }

    test("Tenor with zero days throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            Tenor("X", 0, BigDecimal("0.05"))
        }
    }

    test("Tenor with negative days throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            Tenor("X", -1, BigDecimal("0.05"))
        }
    }

    test("Tenor with negative rate is allowed (ECB negative-rates era)") {
        // Negative rates are a legitimate market reality — ECB deposit rate
        // was -0.50% from 2019 through 2022. Curve-level invariants live in
        // rates-service YieldCurveValidation; the Tenor constructor must not
        // reject negative rates.
        val tenor = Tenor("2Y", 730, BigDecimal("-0.005"))
        tenor.rate shouldBe BigDecimal("-0.005")
    }

    test("Tenor with zero rate is allowed") {
        val tenor = Tenor("1Y", 365, BigDecimal.ZERO)
        tenor.rate.compareTo(BigDecimal.ZERO) shouldBe 0
    }

    test("Tenors are ordered by days") {
        val oneMonth = Tenor("1M", 30, BigDecimal("0.04"))
        val oneYear = Tenor("1Y", 365, BigDecimal("0.05"))
        oneMonth shouldBeLessThan oneYear
        oneYear shouldBeGreaterThan oneMonth
    }

    test("Tenors with same days compare as equal") {
        val a = Tenor("A", 365, BigDecimal("0.04"))
        val b = Tenor("B", 365, BigDecimal("0.05"))
        a.compareTo(b) shouldBe 0
    }

    test("list of Tenors can be sorted by maturity") {
        val tenors = listOf(
            Tenor("10Y", 3650, BigDecimal("0.05")),
            Tenor("1M", 30, BigDecimal("0.03")),
            Tenor("1Y", 365, BigDecimal("0.04")),
        )
        val sorted = tenors.sorted()
        sorted[0].label shouldBe "1M"
        sorted[1].label shouldBe "1Y"
        sorted[2].label shouldBe "10Y"
    }

    test("Tenor.oneMonth creates 30-day tenor") {
        val tenor = Tenor.oneMonth(BigDecimal("0.04"))
        tenor.label shouldBe "1M"
        tenor.days shouldBe 30
    }

    test("Tenor.threeMonths creates 90-day tenor") {
        val tenor = Tenor.threeMonths(BigDecimal("0.04"))
        tenor.label shouldBe "3M"
        tenor.days shouldBe 90
    }

    test("Tenor.oneYear creates 365-day tenor") {
        val tenor = Tenor.oneYear(BigDecimal("0.05"))
        tenor.label shouldBe "1Y"
        tenor.days shouldBe 365
    }

    test("Tenor.fiveYears creates 1825-day tenor") {
        val tenor = Tenor.fiveYears(BigDecimal("0.05"))
        tenor.label shouldBe "5Y"
        tenor.days shouldBe 1825
    }

    test("Tenor.tenYears creates 3650-day tenor") {
        val tenor = Tenor.tenYears(BigDecimal("0.05"))
        tenor.label shouldBe "10Y"
        tenor.days shouldBe 3650
    }

    test("Tenor.thirtyYears creates 10950-day tenor") {
        val tenor = Tenor.thirtyYears(BigDecimal("0.05"))
        tenor.label shouldBe "30Y"
        tenor.days shouldBe 10950
    }

    test("equal Tenors are equal") {
        Tenor("1Y", 365, BigDecimal("0.05")) shouldBe Tenor("1Y", 365, BigDecimal("0.05"))
    }

    test("Tenors with different labels but same days are not equal") {
        Tenor("A", 365, BigDecimal("0.05")) shouldNotBe Tenor("B", 365, BigDecimal("0.05"))
    }
})

package com.kinetix.position.settlement

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * Settlement cadence varies by asset class: equities settle T+2,
 * bonds T+1, FX T+0 (same day for most spot pairs). A trade booked
 * with the wrong settlement date misses the clearing cycle and
 * triggers a fail-settle workflow that Operations spends an
 * afternoon untangling. The validator pins the per-asset-class
 * expected delta.
 */
class SettlementDateValidationTest : FunSpec({

    val tradeDate = LocalDate.of(2026, 5, 28) // Thursday

    test("EQUITY settles T+2 (expected delta 2 business days)") {
        validateSettlementDate(
            assetClass = "EQUITY",
            tradeDate = tradeDate,
            settlementDate = tradeDate.plusDays(2),
        )
    }

    test("BOND settles T+1") {
        validateSettlementDate("BOND", tradeDate, tradeDate.plusDays(1))
    }

    test("FX settles T+0 (same day)") {
        validateSettlementDate("FX", tradeDate, tradeDate)
    }

    test("rejects EQUITY at T+1 (wrong cycle)") {
        shouldThrow<IllegalArgumentException> {
            validateSettlementDate("EQUITY", tradeDate, tradeDate.plusDays(1))
        }
    }

    test("rejects BOND at T+2") {
        shouldThrow<IllegalArgumentException> {
            validateSettlementDate("BOND", tradeDate, tradeDate.plusDays(2))
        }
    }

    test("rejects FX at T+1") {
        shouldThrow<IllegalArgumentException> {
            validateSettlementDate("FX", tradeDate, tradeDate.plusDays(1))
        }
    }

    test("rejects past-dated settlement") {
        shouldThrow<IllegalArgumentException> {
            validateSettlementDate("EQUITY", tradeDate, tradeDate.minusDays(1))
        }
    }

    test("rejects unsupported asset class") {
        shouldThrow<IllegalArgumentException> {
            validateSettlementDate("UNKNOWN", tradeDate, tradeDate.plusDays(2))
        }
    }

    test("error message names the asset class and the expected delta") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateSettlementDate("EQUITY", tradeDate, tradeDate.plusDays(5))
        }
        ex.message!!.contains("EQUITY") shouldBe true
        ex.message!!.contains("T+2") shouldBe true
    }
})

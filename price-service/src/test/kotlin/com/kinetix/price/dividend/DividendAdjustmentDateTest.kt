package com.kinetix.price.dividend

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * On the ex-dividend date the price drops by the dividend amount —
 * historical-price series have to be adjusted for that drop so a
 * back-test doesn't read it as a real loss. The adjuster keys off
 * the ex-date (the date from which the buyer is no longer entitled
 * to the dividend), not the record date (which is the cut-off for
 * the issuer's holder register). Mis-keying on the record date
 * back-dates the adjustment by one trading day and corrupts the
 * series.
 */
class DividendAdjustmentDateTest : FunSpec({

    val divAmount = 0.50  // $0.50/share dividend
    val price = 100.00

    test("price on the ex-date applies the dividend adjustment") {
        val adjusted = applyDividendAdjustment(
            rawPrice = price,
            priceDate = LocalDate.of(2026, 6, 15),
            exDate = LocalDate.of(2026, 6, 15),
            dividendPerShare = divAmount,
        )
        adjusted shouldBe price - divAmount
    }

    test("price one day before ex-date is NOT adjusted") {
        applyDividendAdjustment(price, LocalDate.of(2026, 6, 14), LocalDate.of(2026, 6, 15), divAmount) shouldBe price
    }

    test("price one day after ex-date IS adjusted (we adjust all post-ex prices)") {
        val after = applyDividendAdjustment(price, LocalDate.of(2026, 6, 16), LocalDate.of(2026, 6, 15), divAmount)
        after shouldBe price - divAmount
    }

    test("zero dividend leaves the price unchanged") {
        applyDividendAdjustment(price, LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 15), 0.0) shouldBe price
    }

    test("price far before the ex-date is unchanged") {
        applyDividendAdjustment(price, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 15), divAmount) shouldBe price
    }
})

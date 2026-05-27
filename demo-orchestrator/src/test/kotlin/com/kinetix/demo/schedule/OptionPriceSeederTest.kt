package com.kinetix.demo.schedule

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OptionPriceSeederTest : FunSpec({

    // kx-bt2 acceptance: SPX-OPT-5000C seed price in [120, 250] and
    // VIX-OPT-20C in a vol-consistent range (typically $1-$5). A vol PM
    // looking at the derivatives-book blotter must not see flat $50 across
    // all options.

    val demoAsOf: Clock = Clock.fixed(
        Instant.parse("2026-05-27T12:00:00Z"),
        ZoneOffset.UTC,
    )

    test("derives SPX-OPT-5000C seed price inside the [120, 250] acceptance band") {
        val seeds = OptionPriceSeeder.computeSeeds(demoAsOf)
        val spx = seeds.getValue("SPX-OPT-5000C")
        spx.shouldBeGreaterThanOrEqualTo(BigDecimal("120"))
        spx.shouldBeLessThanOrEqualTo(BigDecimal("250"))
    }

    test("derives VIX-OPT-20C seed price inside the [1, 5] vol-consistent band") {
        val seeds = OptionPriceSeeder.computeSeeds(demoAsOf)
        val vix = seeds.getValue("VIX-OPT-20C")
        vix.shouldBeGreaterThanOrEqualTo(BigDecimal("1"))
        vix.shouldBeLessThanOrEqualTo(BigDecimal("5"))
    }

    test("emits a seed for every derivatives-book option spec") {
        val seeds = OptionPriceSeeder.computeSeeds(demoAsOf)
        seeds.keys shouldContainAll setOf("SPX-OPT-5000C", "VIX-OPT-20C")
    }

    test("seeds are deterministic for a fixed as-of clock") {
        val first = OptionPriceSeeder.computeSeeds(demoAsOf)
        val second = OptionPriceSeeder.computeSeeds(demoAsOf)
        first shouldBe second
    }
})

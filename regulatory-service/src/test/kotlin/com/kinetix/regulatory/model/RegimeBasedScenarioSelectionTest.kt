package com.kinetix.regulatory.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Stress scenarios are not uniformly applicable: a 2008-style liquidity-
 * crunch scenario is interesting in elevated and crisis regimes but adds
 * almost no signal in a calm market where realised volatility is in line
 * with the previous quarter. Risk runs in normal regimes save compute by
 * dropping these, while crisis regimes pull in additional scenarios
 * (Volmageddon, SVB) that a normal regime ignores.
 *
 * This test pins the selection contract: NORMAL keeps the standard base
 * library; ELEVATED adds taper-style scenarios; STRESSED pulls in GFC and
 * COVID; CRISIS escalates to the full historical-replay library including
 * tail-event scenarios (Volmageddon, SVB, LTCM).
 */
class RegimeBasedScenarioSelectionTest : FunSpec({

    test("NORMAL regime selects the standard base library") {
        val scenarios = RegimeScenarioSelector.scenariosFor(MarketRegime.NORMAL)
        scenarios shouldContainAll listOf("base-1pct-rates", "base-equity-shock")
        scenarios shouldNotContain "gfc-2008"
        scenarios shouldNotContain "volmageddon-2018"
    }

    test("ELEVATED regime adds taper-tantrum and rates-spike scenarios") {
        val scenarios = RegimeScenarioSelector.scenariosFor(MarketRegime.ELEVATED)
        scenarios shouldContain "taper-tantrum-2013"
        scenarios shouldContain "rates-spike-200bp"
        // Base library still present.
        scenarios shouldContain "base-1pct-rates"
    }

    test("STRESSED regime pulls in GFC and COVID historical replays") {
        val scenarios = RegimeScenarioSelector.scenariosFor(MarketRegime.STRESSED)
        scenarios shouldContain "gfc-2008"
        scenarios shouldContain "covid-2020"
        // Elevated-tier scenarios remain.
        scenarios shouldContain "taper-tantrum-2013"
    }

    test("CRISIS regime escalates to the full tail-event library") {
        val scenarios = RegimeScenarioSelector.scenariosFor(MarketRegime.CRISIS)
        scenarios shouldContain "volmageddon-2018"
        scenarios shouldContain "svb-2023"
        scenarios shouldContain "ltcm-1998"
        // Lower-tier scenarios remain.
        scenarios shouldContainAll listOf("gfc-2008", "taper-tantrum-2013", "base-equity-shock")
    }

    test("each tier's scenario set is a strict superset of the tier below it") {
        val normal = RegimeScenarioSelector.scenariosFor(MarketRegime.NORMAL).toSet()
        val elevated = RegimeScenarioSelector.scenariosFor(MarketRegime.ELEVATED).toSet()
        val stressed = RegimeScenarioSelector.scenariosFor(MarketRegime.STRESSED).toSet()
        val crisis = RegimeScenarioSelector.scenariosFor(MarketRegime.CRISIS).toSet()
        elevated.containsAll(normal) shouldBe true
        (elevated.size > normal.size) shouldBe true
        stressed.containsAll(elevated) shouldBe true
        (stressed.size > elevated.size) shouldBe true
        crisis.containsAll(stressed) shouldBe true
        (crisis.size > stressed.size) shouldBe true
    }

    test("scenariosFor returns a stable, ordered list (NORMAL set is deterministic)") {
        val first = RegimeScenarioSelector.scenariosFor(MarketRegime.NORMAL)
        val second = RegimeScenarioSelector.scenariosFor(MarketRegime.NORMAL)
        first shouldBe second
    }
})

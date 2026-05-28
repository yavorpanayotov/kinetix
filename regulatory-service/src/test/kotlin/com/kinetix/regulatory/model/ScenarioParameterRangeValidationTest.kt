package com.kinetix.regulatory.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Stress-scenario parameters arrive from operator-authored YAML. A
 * fat-finger that sets the rates shock to +5000 (5000bps instead of
 * +500bps) sends the risk engine on a wild ride and prints garbage for
 * every position — by the time it finishes, the operator has lost an
 * hour and the regulator has to be told the scenario was invalid. This
 * validator pins down the supported ranges: rates shocks must sit in
 * [-500bps, +500bps]; FX shocks must sit in [-20%, +20%].
 *
 * Edge inclusivity: both ends inclusive (a -500bp move is realistic in
 * a serious crisis and must be allowed; +501bps is not).
 */
class ScenarioParameterRangeValidationTest : FunSpec({

    test("rates shock at +500bps is accepted") {
        validateRatesShockBp(500) shouldBe 500
    }
    test("rates shock at -500bps is accepted (boundary)") {
        validateRatesShockBp(-500) shouldBe -500
    }
    test("rates shock at +501bps is rejected") {
        shouldThrow<IllegalArgumentException> { validateRatesShockBp(501) }
    }
    test("rates shock at -501bps is rejected") {
        shouldThrow<IllegalArgumentException> { validateRatesShockBp(-501) }
    }
    test("rates shock of 0bps (no shock) is accepted") {
        validateRatesShockBp(0) shouldBe 0
    }

    test("FX shock at +20% is accepted") {
        validateFxShockPercent(0.20) shouldBe 0.20
    }
    test("FX shock at -20% is accepted") {
        validateFxShockPercent(-0.20) shouldBe -0.20
    }
    test("FX shock at +20.01% is rejected") {
        shouldThrow<IllegalArgumentException> { validateFxShockPercent(0.2001) }
    }
    test("FX shock at -20.01% is rejected") {
        shouldThrow<IllegalArgumentException> { validateFxShockPercent(-0.2001) }
    }
    test("FX shock of 0% is accepted") {
        validateFxShockPercent(0.0) shouldBe 0.0
    }

    test("the rejection message names the parameter and the supported range") {
        val ex = shouldThrow<IllegalArgumentException> { validateRatesShockBp(5000) }
        ex.message!!.contains("rates") shouldBe true
        ex.message!!.contains("[-500, +500]") shouldBe true
    }
})

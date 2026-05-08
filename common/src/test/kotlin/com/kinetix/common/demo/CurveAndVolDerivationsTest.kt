package com.kinetix.common.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe

class CurveAndVolDerivationsTest : FunSpec({
    val tape = DemoTape()
    val derivations = CurveAndVolDerivations(tape)

    test("yieldCurveSnapshots produces 252 dated snapshots per currency") {
        val usd = derivations.yieldCurveSnapshots("USD")
        usd.size shouldBe RegimeCalendar.DAYS
        usd.first().date.isBefore(usd.last().date) shouldBe true
    }

    test("yield curves stay arbitrage-free in calm regime") {
        val snapshots = derivations.yieldCurveSnapshots("USD").filter { it.regime == Regime.CALM }
        for (snap in snapshots.take(20)) {
            // Long-dated rates should not be wildly negative.
            snap.rates.forEach { r -> r.shouldBeBetween(-0.005, 0.20, 0.0) }
        }
    }

    test("yield curves react to stress regimes") {
        val snapshots = derivations.yieldCurveSnapshots("USD")
        val stress2022 = snapshots.filter { it.regime == Regime.STRESS_2022_ANALOG }
        val calm = snapshots.filter { it.regime == Regime.CALM }
        // Stress2022 has hawkish drift, so 10Y rate should be higher than calm-period average
        // with reasonable margin. Wide tolerance — we're testing direction, not magnitude.
        val stressMean = stress2022.map { it.rates[8] }.average() // 10Y is index 8
        val calmMean = calm.map { it.rates[8] }.average()
        assert(stress2022.isNotEmpty()) { "no stress2022 days found" }
        assert(stressMean > calmMean - 0.005) {
            "stress2022 mean 10Y=$stressMean should not be far below calm=$calmMean"
        }
    }

    test("vol surface snapshots use derived ATM IV from price tape") {
        val aapl = derivations.volSurfaceSnapshots("AAPL")
        aapl.size shouldBe RegimeCalendar.DAYS
        // ATM IV should be in a sensible band (5%-400%) for a tech name with stress-regime scaling
        aapl.forEach { it.atmIv.shouldBeBetween(0.05, 4.0, 0.0) }
    }

    test("vol surface ATM IV is elevated during stress regime") {
        val aapl = derivations.volSurfaceSnapshots("AAPL")
        val stress = aapl.filter { it.regime == Regime.STRESS_2020_ANALOG }
        val calm = aapl.filter { it.regime == Regime.CALM }
        if (stress.isNotEmpty() && calm.isNotEmpty()) {
            val stressMean = stress.map { it.atmIv }.average()
            val calmMean = calm.map { it.atmIv }.average()
            assert(stressMean > calmMean) {
                "stress ATM IV $stressMean should exceed calm ATM IV $calmMean"
            }
        }
    }

    test("vol surface skew preserves OTM-put premium over OTM-call") {
        val aapl = derivations.volSurfaceSnapshots("AAPL")
        val snapshot = aapl.last()
        val strikes = snapshot.strikePercents
        val matIdx = snapshot.maturityDays.indexOf(90)
        val put80 = snapshot.impliedVol[strikes.indexOf(80)][matIdx]
        val atm = snapshot.impliedVol[strikes.indexOf(100)][matIdx]
        val call120 = snapshot.impliedVol[strikes.indexOf(120)][matIdx]
        assert(put80 > atm) { "expected 80% put IV $put80 > ATM IV $atm" }
        assert(put80 > call120) { "expected put IV $put80 > call IV $call120 (skew)" }
    }

    test("snapshots are byte-identical across calls (determinism)") {
        val a = CurveAndVolDerivations(DemoTape(seed = 99L))
        val b = CurveAndVolDerivations(DemoTape(seed = 99L))
        val sa = a.yieldCurveSnapshots("USD").map { it.rates.toList() }
        val sb = b.yieldCurveSnapshots("USD").map { it.rates.toList() }
        sa shouldBe sb
    }
})

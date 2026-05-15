package com.kinetix.common.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import java.security.MessageDigest

private fun sha256Hex(s: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

class DemoTapeTest : FunSpec({
    val tape = DemoTape()

    test("price tape has 252 days for every instrument in the universe") {
        for (spec in DemoTapeUniverse.SPECS) {
            // Each daily return is well-defined for every day we expose.
            tape.dailyReturn(spec.symbol, 0)
            tape.priceOn(spec.symbol, 0)
            tape.priceOn(spec.symbol, RegimeCalendar.DAYS - 1)
        }
    }

    test("most recent close equals the configured anchor price") {
        for (spec in DemoTapeUniverse.SPECS) {
            tape.priceOn(spec.symbol, 0) shouldBe spec.startPrice
        }
    }

    test("price paths stay strictly positive") {
        for (spec in DemoTapeUniverse.SPECS) {
            for (day in 0 until RegimeCalendar.DAYS) {
                val p = tape.priceOn(spec.symbol, day)
                assert(p > 0.0) { "${spec.symbol} day=$day produced non-positive price=$p" }
            }
        }
    }

    test("realised vol on AAPL is in the right ballpark") {
        // ~24% annual configured vol, 60-day rolling window — expect something in 0.15-0.40
        // (band wide enough to cover stress regimes).
        val v = tape.realisedVol("AAPL", endDay = 0, window = 60)
        v.shouldBeBetween(0.15, 0.40, 0.0)
    }

    test("AAPL and MSFT realised correlation is meaningfully positive") {
        // Both have high market beta + tech sector loading; correlation should be strong.
        val rho = tape.realisedCorrelation("AAPL", "MSFT", endDay = 0, window = 120)
        rho.shouldBeBetween(0.40, 0.95, 0.0)
    }

    test("AAPL and US10Y are weakly correlated") {
        val rho = tape.realisedCorrelation("AAPL", "US10Y", endDay = 0, window = 120)
        rho.shouldBeBetween(-0.40, 0.40, 0.0)
    }

    test("stress windows produce elevated realised vol for SPX-like instruments") {
        val cal = tape.calendar
        // Pick a 20-day window centred on the 2020-analog stress (around day 180).
        val stressVol = tape.realisedVol("IDX-SPX", endDay = 175, window = 20)
        val calmVol = tape.realisedVol("IDX-SPX", endDay = 230, window = 20)
        assert(stressVol > calmVol * 1.3) {
            "expected stress vol $stressVol >> calm vol $calmVol"
        }
        cal.stressWindows() // ensure exposed
    }

    test("historical 99% VaR on AAPL is sensible") {
        val var99 = tape.historicalVaR("AAPL", confidence = 0.99, window = 252)
        // Daily 99% VaR for ~24% annual vol: ~24% / sqrt(252) * 2.33 ≈ 0.035, with fat tails ~0.05.
        var99.shouldBeBetween(0.020, 0.080, 0.0)
    }

    test("historicalVaR honours endDay by sourcing the trailing window from that point") {
        // A window in the stress regime should produce a strictly larger VaR than one in
        // the calm/recovery regime — proving that endDay actually slides the sample.
        val stressVaR = tape.historicalVaR("IDX-SPX", confidence = 0.99, window = 40, endDay = 170)
        val calmVaR = tape.historicalVaR("IDX-SPX", confidence = 0.99, window = 40, endDay = 230)
        assert(stressVaR > calmVaR) {
            "expected stress-window VaR $stressVaR > calm-window VaR $calmVaR — endDay was likely ignored"
        }
    }

    test("historicalVaR with endDay near the series end clips the window without crashing") {
        // Asking for a 60-day window ending at day 240 only has 12 days of data (240..251);
        // the function should return a non-negative number, not throw.
        val v = tape.historicalVaR("AAPL", confidence = 0.99, window = 60, endDay = 240)
        assert(v.isFinite()) { "expected a finite VaR, got $v" }
    }

    test("same seed produces byte-identical output") {
        val a = DemoTape(seed = 12345L)
        val b = DemoTape(seed = 12345L)
        for (spec in DemoTapeUniverse.SPECS.take(5)) {
            for (day in listOf(0, 50, 100, 200)) {
                a.priceOn(spec.symbol, day) shouldBe b.priceOn(spec.symbol, day)
            }
        }
    }

    test("different seeds produce different output") {
        val a = DemoTape(seed = 1L)
        val b = DemoTape(seed = 2L)
        a.priceOn("AAPL", 100) shouldBe a.priceOn("AAPL", 100)
        assert(a.priceOn("AAPL", 100) != b.priceOn("AAPL", 100))
    }

    test("golden SHA-256 pins the full price tape for seed=42") {
        // Hashes every (symbol, day, price) triple over the 252-day window. Symbol order
        // is taken from DemoTapeUniverse.SPECS (declaration order is stable). Any drift in
        // factor synthesis, GARCH state, idiosyncratic draws, or regime calendar will flip
        // this hash. Regenerate ONLY when the algorithm change is intentional.
        val tape = DemoTape(seed = 42L)
        val sb = StringBuilder()
        for (spec in DemoTapeUniverse.SPECS) {
            for (day in 0 until RegimeCalendar.DAYS) {
                sb.append(spec.symbol).append('|').append(day).append('|')
                    .append("%.10f".format(java.util.Locale.ROOT, tape.priceOn(spec.symbol, day))).append('\n')
            }
        }
        val golden = "45377f5b0bf92f2877ed4ccf08930653ed49a44b9065222f05b35a23215fbb1b"
        sha256Hex(sb.toString()) shouldBe golden
    }
})

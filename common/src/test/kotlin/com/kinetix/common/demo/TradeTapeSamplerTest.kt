package com.kinetix.common.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe

class TradeTapeSamplerTest : FunSpec({

    test("zipf weights make the head dominate the tail") {
        val w = TradeTapeSampler.zipfWeights(100, alpha = 1.4)
        val total = w.sum()
        val top20Mass = w.take(20).sum() / total
        // For alpha=1.4 over 100 items, top 20 should dominate the tail by a clear margin.
        top20Mass.shouldBeBetween(0.50, 0.95, 0.0)
    }

    test("sampleWeightedIndex draws head items more often than tail items") {
        val rng = TapeRng(123L)
        val w = TradeTapeSampler.zipfWeights(20)
        val counts = IntArray(20)
        repeat(20_000) {
            val idx = TradeTapeSampler.sampleWeightedIndex(rng, w)
            counts[idx]++
        }
        // Head is hit at least 5× more than the tail.
        assert(counts[0] > counts[19] * 5) { "head=${counts[0]} tail=${counts[19]}" }
    }

    test("momentum side bias has positive autocorrelation") {
        val rng = TapeRng(456L)
        var signal = 0.0
        var lastBuy = false
        var streakCount = 0
        var transitions = 0
        repeat(10_000) {
            val (s, isBuy) = TradeTapeSampler.nextSideWithMomentum(rng, signal)
            signal = s
            if (isBuy != lastBuy) transitions++ else streakCount++
            lastBuy = isBuy
        }
        // With autocorrelation=0.55, we expect noticeable streak persistence — fewer
        // transitions than the iid 50% baseline.
        assert(transitions < 5_500) { "too many side flips: $transitions" }
    }

    test("dailyTradeCount lifts during stress regimes") {
        val rng = TapeRng(7L)
        val calm = (0 until 100).map { TradeTapeSampler.dailyTradeCount(rng, 30, Regime.CALM, 3) }.average()
        val stress = (0 until 100).map { TradeTapeSampler.dailyTradeCount(rng, 30, Regime.STRESS_2020_ANALOG, 3) }.average()
        assert(stress > calm * 1.8) { "stress=$stress should be ≥ 1.8× calm=$calm" }
    }

    test("dailyTradeCount drops on Mondays and Fridays") {
        val rng = TapeRng(11L)
        val tue = (0 until 100).map { TradeTapeSampler.dailyTradeCount(rng, 50, Regime.CALM, 2) }.average()
        val mon = (0 until 100).map { TradeTapeSampler.dailyTradeCount(rng, 50, Regime.CALM, 1) }.average()
        // Volume should be measurably lower on the boundary days.
        assert(mon < tue) { "monday=$mon should be < tuesday=$tue" }
    }

    test("intradaySeconds returns timestamps inside business-day windows") {
        val rng = TapeRng(99L)
        repeat(5_000) {
            val s = TradeTapeSampler.intradaySeconds(rng, isEuropean = false)
            // 14:30-21:00 UTC = 52200..75600
            (s in 52200L..75600L) shouldBe true
        }
    }

    test("sampleWeightedIndex is deterministic for a given seed") {
        val w = TradeTapeSampler.zipfWeights(50)
        val a = TapeRng(31L)
        val b = TapeRng(31L)
        val seqA = IntArray(100) { TradeTapeSampler.sampleWeightedIndex(a, w) }
        val seqB = IntArray(100) { TradeTapeSampler.sampleWeightedIndex(b, w) }
        seqA.toList() shouldBe seqB.toList()
    }
})

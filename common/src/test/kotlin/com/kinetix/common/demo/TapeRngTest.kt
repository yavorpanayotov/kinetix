package com.kinetix.common.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import kotlin.math.sqrt

class TapeRngTest : FunSpec({
    test("same seed produces byte-identical sequence") {
        val a = TapeRng(42L)
        val b = TapeRng(42L)
        val seqA = LongArray(100) { a.nextLong() }
        val seqB = LongArray(100) { b.nextLong() }
        seqA.toList() shouldBe seqB.toList()
    }

    test("different seeds diverge") {
        val a = TapeRng(42L)
        val b = TapeRng(43L)
        val aFirst = a.nextLong()
        val bFirst = b.nextLong()
        (aFirst != bFirst) shouldBe true
    }

    test("uniform draws stay within [0, 1)") {
        val rng = TapeRng(123L)
        repeat(10_000) {
            val u = rng.nextUniform()
            u.shouldBeBetween(0.0, 1.0 - Double.MIN_VALUE, 0.0)
        }
    }

    test("standard normal mean and variance approximate 0 and 1") {
        val rng = TapeRng(7L)
        val n = 50_000
        var sum = 0.0
        var sumSq = 0.0
        repeat(n) {
            val z = rng.nextNormal()
            sum += z
            sumSq += z * z
        }
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        mean.shouldBeBetween(-0.02, 0.02, 0.0)
        sqrt(variance).shouldBeBetween(0.98, 1.02, 0.0)
    }

    test("standardised Student-t (df=5) variance approximates 1") {
        val rng = TapeRng(11L)
        val n = 50_000
        var sumSq = 0.0
        var sum = 0.0
        repeat(n) {
            val t = rng.nextStandardisedStudentT(5)
            sum += t
            sumSq += t * t
        }
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        sqrt(variance).shouldBeBetween(0.95, 1.05, 0.0)
    }

    test("stableSeed is byte-stable across calls") {
        val a = TapeRng.stableSeed("AAPL")
        val b = TapeRng.stableSeed("AAPL")
        a shouldBe b
    }

    test("stableSeed differs across distinct labels") {
        val labels = listOf("AAPL", "GOOGL", "MSFT", "EURUSD", "US10Y", "GC")
        val seeds = labels.map { TapeRng.stableSeed(it) }
        seeds.toSet().size shouldBe labels.size
    }
})

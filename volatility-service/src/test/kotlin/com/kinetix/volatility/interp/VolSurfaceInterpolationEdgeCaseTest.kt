package com.kinetix.volatility.interp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class VolSurfaceInterpolationEdgeCaseTest : FunSpec({
    val grid = VolSurfaceGrid(
        strikes = listOf(90.0, 110.0),
        expiryDays = listOf(30, 90),
        vols = listOf(
            listOf(0.20, 0.22),
            listOf(0.24, 0.26),
        ),
    )

    test("exact knot returns stored vol") {
        interpolateSurfaceVol(grid, 90.0, 30) shouldBe 0.20
        interpolateSurfaceVol(grid, 110.0, 90) shouldBe 0.26
    }

    test("interior midpoint averages the four corners") {
        interpolateSurfaceVol(grid, 100.0, 60) shouldBe (0.23 plusOrMinus 1e-9)
    }

    test("below-min strike clamps to the boundary row (flat extrapolation)") {
        interpolateSurfaceVol(grid, 50.0, 10) shouldBe 0.20
    }

    test("above-max expiry clamps to the boundary column") {
        interpolateSurfaceVol(grid, 110.0, 1_000) shouldBe 0.26
    }

    test("malformed grid rejected at construction (size mismatch)") {
        shouldThrow<IllegalArgumentException> {
            VolSurfaceGrid(
                strikes = listOf(90.0),
                expiryDays = listOf(30, 90),
                vols = listOf(listOf(0.20)),
            )
        }
    }

    test("empty grid rejected") {
        shouldThrow<IllegalArgumentException> {
            VolSurfaceGrid(strikes = emptyList(), expiryDays = listOf(30), vols = emptyList())
        }
    }
})

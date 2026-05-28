package com.kinetix.position.collateral

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * A multi-leg order (a calendar spread, a strangle) settles its
 * collateral leg-by-leg as the fills come in. On a partial fill of
 * one leg, the posted collateral updates pro rata: filled-quantity
 * over total-quantity, times the leg's collateral requirement.
 * Mis-reconciling that ratio leaves the desk either over- or
 * under-collateralised on the partially-filled leg.
 */
class CollateralPartialFillReconciliationTest : FunSpec({

    val tol = 1e-9

    test("fully-filled leg reconciles to the full leg requirement") {
        reconcilePartialFillCollateral(
            legRequirement = 1_000_000.0,
            filledQuantity = 100,
            totalQuantity = 100,
        ) shouldBe 1_000_000.0
    }

    test("zero-filled leg posts zero collateral") {
        reconcilePartialFillCollateral(1_000_000.0, 0, 100) shouldBe 0.0
    }

    test("50% partial fill posts 50% collateral") {
        abs(reconcilePartialFillCollateral(1_000_000.0, 50, 100) - 500_000.0) shouldBeLessThanOrEqual tol
    }

    test("33% partial fill posts approximately 33% collateral") {
        val expected = 1_000_000.0 * 33.0 / 100.0
        abs(reconcilePartialFillCollateral(1_000_000.0, 33, 100) - expected) shouldBeLessThanOrEqual tol
    }

    test("over-filled (defensive: filled > total) clamps to total") {
        reconcilePartialFillCollateral(1_000_000.0, 105, 100) shouldBe 1_000_000.0
    }

    test("zero total-quantity returns zero collateral (no order)") {
        reconcilePartialFillCollateral(1_000_000.0, 0, 0) shouldBe 0.0
    }
})

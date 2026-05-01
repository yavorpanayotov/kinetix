package com.kinetix.volatility.routes

import com.kinetix.common.model.VolPoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class VolSurfaceDiffTest : FunSpec({

    fun vp(strike: String, maturityDays: Int, vol: String) =
        VolPoint(BigDecimal(strike), maturityDays, BigDecimal(vol))

    test("identical surfaces on identical grid produce zero diff at every point") {
        val points = listOf(
            vp("90", 30, "0.30"),
            vp("100", 30, "0.25"),
            vp("110", 30, "0.20"),
        )

        val diffs = computeUnionGridDiff(points, points)

        diffs.size shouldBe 3
        diffs.forEach { d ->
            d.diff shouldBe (0.0 plusOrMinus 1e-9)
        }
    }

    test("identical flat surfaces on staggered grids produce zero diff over the union") {
        // Both surfaces flat at vol = 0.25 — interpolation must reproduce 0.25 anywhere.
        val base = listOf(
            vp("95", 30, "0.25"),
            vp("100", 30, "0.25"),
            vp("105", 30, "0.25"),
        )
        val compare = listOf(
            vp("97", 30, "0.25"),
            vp("100", 30, "0.25"),
            vp("103", 30, "0.25"),
        )

        val diffs = computeUnionGridDiff(base, compare)

        // Union of {95,97,100,103,105} = 5 strikes
        diffs.size shouldBe 5
        diffs.forEach { d ->
            d.diff shouldBe (0.0 plusOrMinus 1e-9)
            d.baseVol shouldBe (0.25 plusOrMinus 1e-9)
            d.compareVol shouldBe (0.25 plusOrMinus 1e-9)
        }
    }

    test("parallel-shift over linear-skew surfaces yields uniform diff at every union strike") {
        // Linear skew in log K: vols at 90, 100, 110 are 0.30, 0.25, 0.20.
        // Compare is the same skew shifted up by exactly 0.01.
        val base = listOf(
            vp("90", 30, "0.30"),
            vp("100", 30, "0.25"),
            vp("110", 30, "0.20"),
        )
        val compare = listOf(
            vp("90", 30, "0.31"),
            vp("100", 30, "0.26"),
            vp("110", 30, "0.21"),
        )

        val diffs = computeUnionGridDiff(base, compare)

        diffs.size shouldBe 3
        diffs.forEach { d ->
            d.diff shouldBe (-0.01 plusOrMinus 1e-9)
        }
    }

    test("interpolation at a strike between knots returns the bilinear value, not the nearer knot") {
        // Base surface has strikes 100 and 120 at maturity 30 with vols 0.25 and 0.20.
        // Compare surface only has strike 110 at maturity 30 with vol 0.225.
        //
        // The base has no point at 110, so it must interpolate. Bilinear in log-K:
        //   t = (ln(110) - ln(100)) / (ln(120) - ln(100))
        //     ≈ (4.7005 - 4.6052) / (4.7875 - 4.6052)
        //     ≈ 0.0953 / 0.1823
        //     ≈ 0.5229
        //   interpolated vol ≈ 0.25 + 0.5229 * (0.20 - 0.25) ≈ 0.2239
        //
        // Compare has 110 = 0.225 exactly. Diff ≈ 0.2239 - 0.225 ≈ -0.00115.
        // A nearest-neighbour fallback would have picked 0.20 from the base (closer to 110
        // than 100 is, by absolute distance: 10 vs 10 — tie — but with a typical impl
        // would still produce a far worse diff). The point is that the interpolated diff
        // is a small magnitude reflecting the small departure from a perfectly linear
        // skew, not a large step jump from nearest-neighbour.
        val base = listOf(
            vp("100", 30, "0.25"),
            vp("120", 30, "0.20"),
        )
        val compare = listOf(
            vp("110", 30, "0.225"),
        )

        val diffs = computeUnionGridDiff(base, compare)

        // Find the diff at strike 110.
        val diff110 = diffs.first { it.strike == 110.0 }
        diff110.baseVol shouldBe (0.224 plusOrMinus 0.005)
        diff110.diff shouldBe (-0.00115 plusOrMinus 0.005)
    }

    test("strike outside the available grid clamps to the boundary knot (flat extrapolation)") {
        // Base has strikes 100 and 110. Compare has strike 200 (well outside base grid).
        // Base must clamp to its nearest boundary (110, vol 0.20) — no slope extrapolation.
        val base = listOf(
            vp("100", 30, "0.25"),
            vp("110", 30, "0.20"),
        )
        val compare = listOf(
            vp("200", 30, "0.30"),
        )

        val diffs = computeUnionGridDiff(base, compare)

        val diff200 = diffs.first { it.strike == 200.0 }
        diff200.baseVol shouldBe (0.20 plusOrMinus 1e-9)
        diff200.diff shouldBe (-0.10 plusOrMinus 1e-9)
    }

    test("missing maturity in one surface clamps to the nearest available maturity") {
        // Base has maturities 30 and 90 at strike 100, vols 0.25 and 0.20.
        // Compare has only maturity 60 at strike 100, vol 0.225.
        //
        // For the base at maturity 60:
        //   sqrt(60) ≈ 7.7460
        //   sqrt(30) ≈ 5.4772
        //   sqrt(90) ≈ 9.4868
        //   t = (7.7460 - 5.4772) / (9.4868 - 5.4772) ≈ 2.2688 / 4.0096 ≈ 0.5659
        //   interpolated vol ≈ 0.25 + 0.5659 * (0.20 - 0.25) ≈ 0.2217
        // Compare exact match at (100,60,0.225). Diff ≈ -0.0033.
        val base = listOf(
            vp("100", 30, "0.25"),
            vp("100", 90, "0.20"),
        )
        val compare = listOf(
            vp("100", 60, "0.225"),
        )

        val diffs = computeUnionGridDiff(base, compare)

        val diff60 = diffs.first { it.strike == 100.0 && it.maturityDays == 60 }
        diff60.baseVol shouldBe (0.2217 plusOrMinus 0.005)
        diff60.diff shouldBe (-0.00329 plusOrMinus 0.005)
    }

    test("single-point surface clamps to its only available value (flat extrapolation everywhere)") {
        // Base has only one point. Compare has multiple points on a different grid.
        // Base must return its only vol for any union-grid lookup.
        val base = listOf(
            vp("100", 30, "0.30"),
        )
        val compare = listOf(
            vp("90", 30, "0.20"),
            vp("110", 30, "0.25"),
        )

        val diffs = computeUnionGridDiff(base, compare)

        diffs.size shouldBe 3
        // Every base lookup should return 0.30 because there is only one knot.
        diffs.forEach { d ->
            d.baseVol shouldBe (0.30 plusOrMinus 1e-9)
        }
    }

    test("output is sorted by maturity then strike for stable diff display") {
        val base = listOf(
            vp("110", 30, "0.22"),
            vp("100", 60, "0.24"),
            vp("100", 30, "0.25"),
        )
        val compare = listOf(
            vp("110", 30, "0.21"),
            vp("100", 60, "0.23"),
            vp("100", 30, "0.24"),
        )

        val diffs = computeUnionGridDiff(base, compare)

        diffs.map { it.maturityDays to it.strike } shouldBe listOf(
            30 to 100.0,
            30 to 110.0,
            60 to 100.0,
        )
    }
})

package com.kinetix.rates.curve

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * USD Libor was retired in mid-2023; GBP Libor was retired in 2024.
 * Legacy systems still request GBP Libor 3M, USD Libor 6M etc. The
 * platform routes these to the corresponding overnight risk-free rate
 * (SONIA for GBP, SOFR for USD) when the requested Libor tenor is
 * past the retirement date. The fallback resolver pins the contract.
 */
class LiborToSoniaFallbackTest : FunSpec({

    test("active Libor tenor (no retirement yet) returns the Libor curve") {
        resolveRateCurve(
            currency = "GBP",
            tenor = "3M",
            requestKind = "LIBOR",
            retirementDate = "2099-12-31",   // future, not retired
            today = "2026-05-28",
        ) shouldBe "GBP_LIBOR_3M"
    }

    test("Libor retired (today >= retirement) falls back to SONIA for GBP") {
        resolveRateCurve(
            currency = "GBP",
            tenor = "3M",
            requestKind = "LIBOR",
            retirementDate = "2024-09-30",
            today = "2026-05-28",
        ) shouldBe "GBP_SONIA"
    }

    test("Libor retired falls back to SOFR for USD") {
        resolveRateCurve(
            currency = "USD",
            tenor = "6M",
            requestKind = "LIBOR",
            retirementDate = "2023-06-30",
            today = "2026-05-28",
        ) shouldBe "USD_SOFR"
    }

    test("Libor on exactly the retirement date is already fallen back (inclusive)") {
        resolveRateCurve(
            currency = "GBP",
            tenor = "3M",
            requestKind = "LIBOR",
            retirementDate = "2026-05-28",
            today = "2026-05-28",
        ) shouldBe "GBP_SONIA"
    }

    test("non-Libor request (SOFR/SONIA direct) is returned as-is regardless of retirement") {
        resolveRateCurve(
            currency = "USD",
            tenor = "ON",
            requestKind = "SOFR",
            retirementDate = "2099-12-31",
            today = "2026-05-28",
        ) shouldBe "USD_SOFR"
    }

    test("unsupported currency for Libor fallback throws") {
        val result = runCatching {
            resolveRateCurve("MNT", "3M", "LIBOR", "2024-01-01", "2026-05-28")
        }
        result.isFailure shouldBe true
    }
})

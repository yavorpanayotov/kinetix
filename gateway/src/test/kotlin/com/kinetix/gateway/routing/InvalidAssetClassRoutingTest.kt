package com.kinetix.gateway.routing

import com.kinetix.common.model.AssetClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Trade-booking and risk routes accept an asset-class string from the
 * client. When the string fails to match a known [AssetClass] (typo,
 * out-of-date client), the route must return a structured 400 with a
 * pointer to the supported values — not a 500, not a silent fall-through
 * to a default class. This test pins the parser contract.
 */
class InvalidAssetClassRoutingTest : FunSpec({

    test("parses every known asset class case-insensitively") {
        for (cls in AssetClass.entries) {
            parseAssetClassOrError(cls.name) shouldBe AssetClassParseResult.Parsed(cls)
            parseAssetClassOrError(cls.name.lowercase()) shouldBe AssetClassParseResult.Parsed(cls)
        }
    }

    test("rejects null with a NotProvided error") {
        parseAssetClassOrError(null) shouldBe AssetClassParseResult.NotProvided
    }

    test("rejects an empty string with a NotProvided error") {
        parseAssetClassOrError("") shouldBe AssetClassParseResult.NotProvided
    }

    test("rejects a typo with an Unknown error carrying the supported list") {
        val result = parseAssetClassOrError("EUQITY")
        result.shouldBeUnknown()
        (result as AssetClassParseResult.Unknown).input shouldBe "EUQITY"
        result.supported shouldBe AssetClass.entries.map { it.name }
    }

    test("rejects whitespace-padded input that does not match after trim") {
        val result = parseAssetClassOrError("   GARBAGE   ")
        result.shouldBeUnknown()
    }

    test("trims surrounding whitespace before matching (real-world clients)") {
        parseAssetClassOrError("  ${AssetClass.EQUITY.name}  ") shouldBe
            AssetClassParseResult.Parsed(AssetClass.EQUITY)
    }
})

private fun AssetClassParseResult.shouldBeUnknown() {
    check(this is AssetClassParseResult.Unknown) { "expected Unknown, got $this" }
}

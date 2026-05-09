package com.kinetix.common.demo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class SeedProfileTest : FunSpec({

    test("all five scenario variants are exposed") {
        SeedProfile.all().map { it.id } shouldContainExactlyInAnyOrder
            listOf("multi-asset", "equity-ls", "options-book", "stress", "regulatory")
    }

    test("default profile is multi-asset") {
        SeedProfile.default() shouldBe SeedProfile.MultiAsset
    }

    test("parses canonical kebab-case ids") {
        SeedProfile.parse("multi-asset") shouldBe SeedProfile.MultiAsset
        SeedProfile.parse("equity-ls") shouldBe SeedProfile.EquityLS
        SeedProfile.parse("options-book") shouldBe SeedProfile.OptionsBook
        SeedProfile.parse("stress") shouldBe SeedProfile.Stress
        SeedProfile.parse("regulatory") shouldBe SeedProfile.Regulatory
    }

    test("parse is case-insensitive") {
        SeedProfile.parse("Equity-LS") shouldBe SeedProfile.EquityLS
        SeedProfile.parse("OPTIONS-BOOK") shouldBe SeedProfile.OptionsBook
    }

    test("parse rejects unknown ids with UnknownScenarioException") {
        val ex = shouldThrow<UnknownScenarioException> {
            SeedProfile.parse("does-not-exist")
        }
        ex.scenario shouldBe "does-not-exist"
    }

    test("parseOrDefault falls back to multi-asset when null or blank") {
        SeedProfile.parseOrDefault(null) shouldBe SeedProfile.MultiAsset
        SeedProfile.parseOrDefault("") shouldBe SeedProfile.MultiAsset
        SeedProfile.parseOrDefault("   ") shouldBe SeedProfile.MultiAsset
    }

    test("parseOrDefault still throws on a non-blank unknown id") {
        shouldThrow<UnknownScenarioException> {
            SeedProfile.parseOrDefault("nope")
        }
    }

    test("regulatory profile is gated and not yet implemented") {
        SeedProfile.Regulatory.implemented shouldBe false
        SeedProfile.MultiAsset.implemented shouldBe true
        SeedProfile.EquityLS.implemented shouldBe true
        SeedProfile.OptionsBook.implemented shouldBe true
        SeedProfile.Stress.implemented shouldBe true
    }
})

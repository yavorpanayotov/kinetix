package com.kinetix.position.limit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Risk limits come in four flavours: GROSS (sum of |notional|), NET
 * (signed sum), BY_COUNTERPARTY (slice the book by counterparty
 * before applying), BY_SECTOR (slice by GICS sector). Mis-tagging
 * the limit type changes which exposure goes against it — a 50M GROSS
 * limit against a long-short pair leaves no room while a 50M NET
 * limit accepts the same pair as flat. The validator pins the
 * supported enum + reasonable defaults per asset class.
 */
class LimitTypeValidationTest : FunSpec({

    test("every documented variant resolves") {
        LimitType.parse("GROSS") shouldBe LimitType.GROSS
        LimitType.parse("NET") shouldBe LimitType.NET
        LimitType.parse("BY_COUNTERPARTY") shouldBe LimitType.BY_COUNTERPARTY
        LimitType.parse("BY_SECTOR") shouldBe LimitType.BY_SECTOR
    }

    test("parse is case-insensitive (auth feeds use mixed case)") {
        LimitType.parse("gross") shouldBe LimitType.GROSS
        LimitType.parse("By_Sector") shouldBe LimitType.BY_SECTOR
    }

    test("unknown variant throws with the supported list") {
        val ex = shouldThrow<IllegalArgumentException> { LimitType.parse("UNKNOWN") }
        ex.message!!.contains("GROSS") shouldBe true
        ex.message!!.contains("NET") shouldBe true
    }

    test("empty string throws") {
        shouldThrow<IllegalArgumentException> { LimitType.parse("") }
    }

    test("requiresGrouping is true only for the BY_* tiers") {
        LimitType.GROSS.requiresGrouping() shouldBe false
        LimitType.NET.requiresGrouping() shouldBe false
        LimitType.BY_COUNTERPARTY.requiresGrouping() shouldBe true
        LimitType.BY_SECTOR.requiresGrouping() shouldBe true
    }
})

package com.kinetix.common.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class TraderIdTest : FunSpec({

    test("create TraderId with valid value") {
        TraderId("trader-1").value shouldBe "trader-1"
    }

    test("blank TraderId throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> { TraderId("") }
    }

    test("whitespace TraderId throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> { TraderId("   ") }
    }

    test("equal TraderIds are equal") {
        TraderId("trader-1") shouldBe TraderId("trader-1")
    }

    test("different TraderIds are not equal") {
        TraderId("trader-1") shouldNotBe TraderId("trader-2")
    }
})

package com.kinetix.common.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class TraderTest : FunSpec({

    test("create Trader with required fields") {
        val trader = Trader(
            id = TraderId("trader-1"),
            name = "Alice Cohen",
            deskId = DeskId("equities-momentum"),
        )
        trader.name shouldBe "Alice Cohen"
        trader.email shouldBe null
        trader.notionalLimitUsd shouldBe null
    }

    test("blank name rejected") {
        shouldThrow<IllegalArgumentException> {
            Trader(id = TraderId("trader-1"), name = "  ", deskId = DeskId("desk-1"))
        }
    }

    test("negative notional limit rejected") {
        shouldThrow<IllegalArgumentException> {
            Trader(
                id = TraderId("trader-1"),
                name = "Alice",
                deskId = DeskId("desk-1"),
                notionalLimitUsd = BigDecimal("-1"),
            )
        }
    }

    test("zero notional limit accepted") {
        Trader(
            id = TraderId("trader-1"),
            name = "Alice",
            deskId = DeskId("desk-1"),
            notionalLimitUsd = BigDecimal.ZERO,
        ).notionalLimitUsd shouldBe BigDecimal.ZERO
    }
})

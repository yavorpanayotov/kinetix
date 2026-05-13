package com.kinetix.position.trader

import com.kinetix.common.model.TraderId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class FakeLookup(
    private val knownIds: Set<String> = emptySet(),
    private val rpcFailIds: Set<String> = emptySet(),
) : TraderLookupClient {
    override fun exists(traderId: TraderId): Boolean {
        if (traderId.value in rpcFailIds) {
            throw TraderLookupRpcException("simulated RPC failure")
        }
        return traderId.value in knownIds
    }
}

class TraderValidatorTest : FunSpec({

    test("validate accepts a known trader") {
        val validator = TraderValidator(FakeLookup(knownIds = setOf("tr-eg-001")))
        validator.validate(TraderId("tr-eg-001"))
    }

    test("validate throws UnknownTraderException for an unknown trader") {
        val validator = TraderValidator(FakeLookup(knownIds = emptySet()))
        val ex = shouldThrow<UnknownTraderException> {
            validator.validate(TraderId("ghost"))
        }
        ex.traderId shouldBe TraderId("ghost")
    }

    test("validate propagates RPC failures so booking can fail-closed") {
        val validator = TraderValidator(
            FakeLookup(knownIds = setOf("tr-eg-001"), rpcFailIds = setOf("tr-eg-001")),
        )
        shouldThrow<TraderLookupRpcException> {
            validator.validate(TraderId("tr-eg-001"))
        }
    }
})

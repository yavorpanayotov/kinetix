package com.kinetix.common.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class DemoTraderRosterTest : FunSpec({

    test("every book maps to a desk that has at least one trader") {
        for ((bookId, deskId) in DemoTraderRoster.BOOK_TO_DESK) {
            val traders = DemoTraderRoster.TRADERS_BY_DESK[deskId]
            traders.shouldNotBeNull()
            withClue("book=$bookId desk=$deskId") {
                traders.shouldNotBeEmpty()
            }
        }
    }

    test("primaryTraderFor returns the senior trader on the desk") {
        DemoTraderRoster.primaryTraderFor("tech-momentum") shouldBe "tr-tm-001"
        DemoTraderRoster.primaryTraderFor("rates") shouldBe null
    }

    test("traderForTicket is deterministic and returns a roster member") {
        val first = DemoTraderRoster.traderForTicket("tech-momentum", "seed-tape-te-000123")
        val second = DemoTraderRoster.traderForTicket("tech-momentum", "seed-tape-te-000123")
        first shouldBe second
        DemoTraderRoster.ALL_TRADER_IDS.shouldContainAll(setOfNotNull(first))
    }

    test("traderForTicket returns null for an unknown book") {
        DemoTraderRoster.traderForTicket("unknown-book", "trade-1").shouldBeNull()
    }

    test("trader ids do not collide across desks") {
        val flat = DemoTraderRoster.TRADERS_BY_DESK.values.flatten()
        flat.size shouldBe flat.toSet().size
    }
})

private fun <T> withClue(clue: String, block: () -> T): T = block()

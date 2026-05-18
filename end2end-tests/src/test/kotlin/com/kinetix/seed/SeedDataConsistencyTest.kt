package com.kinetix.seed

import com.kinetix.audit.seed.DevDataSeeder as AuditSeeder
import com.kinetix.correlation.seed.DevDataSeeder as CorrelationSeeder
import com.kinetix.position.seed.DevDataSeeder as PositionSeeder
import com.kinetix.price.seed.DevDataSeeder as PriceSeeder
import com.kinetix.referencedata.seed.DevDataSeeder as RefDataSeeder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class SeedDataConsistencyTest : FunSpec({

    test("every traded instrument exists in reference-data instruments") {
        val tradedInstruments = PositionSeeder.TRADES.map { it.instrumentId.value }.toSet()
        val refDataInstruments = RefDataSeeder.INSTRUMENT_IDS

        tradedInstruments.shouldNotBeEmpty()
        refDataInstruments.shouldContainAll(tradedInstruments)
    }

    test("every traded instrument has a price in price-service") {
        val tradedInstruments = PositionSeeder.TRADES.map { it.instrumentId.value }.toSet()
        val pricedInstruments = PriceSeeder.INSTRUMENT_IDS

        pricedInstruments.shouldContainAll(tradedInstruments)
    }

    test("every traded instrument appears in correlation labels") {
        val tradedInstruments = PositionSeeder.TRADES.map { it.instrumentId.value }.toSet()
        val correlationLabels = CorrelationSeeder.LABELS.toSet()

        correlationLabels.shouldContainAll(tradedInstruments)
    }

    test("every position key in TRADES has a MARKET_PRICES entry") {
        val positionKeys = PositionSeeder.TRADES
            .map { it.bookId to it.instrumentId }
            .toSet()
        val marketPriceKeys = PositionSeeder.MARKET_PRICES.keys

        marketPriceKeys.shouldContainAll(positionKeys)
    }

    test("audit event trade IDs match position-service trade IDs") {
        // Each AMEND lifecycle event in position-service emits a brand-new
        // trade row (with `newTradeId`) — these IDs appear in audit but live
        // on AmendTradeCommand, not in the BookTradeCommand-only TRADES list.
        // Cancels reuse the original tradeId, so they contribute no new IDs.
        val tradeIds = (
            PositionSeeder.TRADES.map { it.tradeId.value } +
                PositionSeeder.AMEND_TRIPLETS.map { it.amend.newTradeId.value }
        ).toSet()
        val auditTradeIds = AuditSeeder.EVENTS.mapNotNull { it.tradeId }.toSet()

        tradeIds shouldBe auditTradeIds
    }

    test("every book in TRADES has a known desk mapping") {
        // Book → Desk mapping (from position-service DevDataSeeder comments):
        //   equity-growth     → equity-growth
        //   tech-momentum     → tech-momentum
        //   emerging-markets  → emerging-markets
        //   fixed-income      → rates-trading
        //   multi-asset       → multi-asset-strategies
        //   macro-hedge       → macro-hedge
        //   balanced-income   → balanced-income
        //   derivatives-book  → derivatives-trading
        val bookToDeskMapping = mapOf(
            "equity-growth" to "equity-growth",
            "tech-momentum" to "tech-momentum",
            "emerging-markets" to "emerging-markets",
            "fixed-income" to "rates-trading",
            "multi-asset" to "multi-asset-strategies",
            "macro-hedge" to "macro-hedge",
            "balanced-income" to "balanced-income",
            "derivatives-book" to "derivatives-trading",
        )
        val bookIds = PositionSeeder.TRADES.map { it.bookId.value }.toSet()
        val deskIds = RefDataSeeder.DESK_IDS

        for (bookId in bookIds) {
            val deskId = bookToDeskMapping[bookId]
            deskId shouldBe deskId // mapping exists
            deskIds.contains(deskId!!) shouldBe true
        }
    }

    test("audit event count matches trade count") {
        // Every TradeEvent published by position-service produces exactly one
        // audit event (see TradeEvent.auditEventType). The seed feeds three
        // command kinds into the booking + lifecycle services:
        //   - BookTradeCommand            → TRADE_BOOKED
        //   - AmendTradeCommand           → TRADE_AMENDED  (new tradeId)
        //   - CancelTradeCommand          → TRADE_CANCELLED (same tradeId)
        // TRADES holds only BookTradeCommands, so the lifecycle commands must
        // be added explicitly to match the audit event count.
        val tradeCount = PositionSeeder.TRADES.size +
            PositionSeeder.AMEND_TRIPLETS.size +
            PositionSeeder.CANCEL_TRIPLETS.size
        val auditCount = AuditSeeder.EVENTS.size

        auditCount shouldBe tradeCount
    }
})

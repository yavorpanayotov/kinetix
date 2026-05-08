package com.kinetix.risk.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.BookId
import com.kinetix.risk.model.PositionSnapshotEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.Currency

class ReplayPositionProviderTest : FunSpec({

    test("converts snapshot entries to positions with correct fields") {
        val entries = listOf(
            PositionSnapshotEntry(
                instrumentId = "AAPL",
                assetClass = "EQUITY",
                quantity = BigDecimal("100"),
                averageCostAmount = BigDecimal("150.00"),
                marketPriceAmount = BigDecimal("170.00"),
                currency = "USD",
                marketValueAmount = BigDecimal("17000.00"),
                unrealizedPnlAmount = BigDecimal("2000.00"),
                instrumentType = "CASH_EQUITY",
            ),
            PositionSnapshotEntry(
                instrumentId = "MSFT",
                assetClass = "EQUITY",
                quantity = BigDecimal("50"),
                averageCostAmount = BigDecimal("380.00"),
                marketPriceAmount = BigDecimal("420.00"),
                currency = "USD",
                marketValueAmount = BigDecimal("21000.00"),
                unrealizedPnlAmount = BigDecimal("2000.00"),
                instrumentType = "CASH_EQUITY",
            ),
        )

        val provider = ReplayPositionProvider(entries, "port-1")
        val positions = provider.getPositions(BookId("port-1"))

        positions shouldHaveSize 2

        val aapl = positions[0]
        aapl.bookId shouldBe BookId("port-1")
        aapl.instrumentId shouldBe InstrumentId("AAPL")
        aapl.assetClass shouldBe AssetClass.EQUITY
        aapl.quantity shouldBe BigDecimal("100")
        aapl.averageCost.amount shouldBe BigDecimal("150.00")
        aapl.marketPrice.amount shouldBe BigDecimal("170.00")
        aapl.currency shouldBe Currency.getInstance("USD")

        val msft = positions[1]
        msft.instrumentId shouldBe InstrumentId("MSFT")
        msft.quantity shouldBe BigDecimal("50")
    }
})

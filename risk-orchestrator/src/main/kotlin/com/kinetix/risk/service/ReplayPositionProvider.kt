package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.model.PositionSnapshotEntry
import java.util.Currency

class ReplayPositionProvider(
    private val entries: List<PositionSnapshotEntry>,
    private val bookId: String,
) : PositionProvider {

    override suspend fun getPositions(bookId: BookId): List<Position> {
        return entries.map { entry ->
            val currency = Currency.getInstance(entry.currency)
            Position(
                bookId = BookId(this.bookId),
                instrumentId = InstrumentId(entry.instrumentId),
                assetClass = AssetClass.valueOf(entry.assetClass),
                quantity = entry.quantity,
                averageCost = Money(entry.averageCostAmount, currency),
                marketPrice = Money(entry.marketPriceAmount, currency),
                instrumentType = InstrumentTypeCode.fromString(entry.instrumentType),
            )
        }
    }
}

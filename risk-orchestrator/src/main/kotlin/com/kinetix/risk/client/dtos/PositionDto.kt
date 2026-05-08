package com.kinetix.risk.client.dtos

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.common.model.instrument.InstrumentTypeCode
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class PositionDto(
    val bookId: String,
    val instrumentId: String,
    val assetClass: String,
    val quantity: String,
    val averageCost: MoneyDto,
    val marketPrice: MoneyDto,
    val marketValue: MoneyDto,
    val unrealizedPnl: MoneyDto,
    val realizedPnl: MoneyDto,
    val instrumentType: String,
) {
    fun toDomain(): Position = Position(
        bookId = BookId(bookId),
        instrumentId = InstrumentId(instrumentId),
        assetClass = AssetClass.valueOf(assetClass),
        quantity = BigDecimal(quantity),
        averageCost = averageCost.toDomain(),
        marketPrice = marketPrice.toDomain(),
        realizedPnl = realizedPnl.toDomain(),
        instrumentType = InstrumentTypeCode.fromString(instrumentType),
    )
}

package com.kinetix.position.service

import com.kinetix.common.model.*
import java.math.BigDecimal
import java.time.Instant

data class AmendTradeCommand(
    val originalTradeId: TradeId,
    val newTradeId: TradeId,
    val bookId: BookId,
    val instrumentId: InstrumentId,
    val assetClass: AssetClass,
    val side: Side,
    val quantity: BigDecimal,
    val price: Money,
    val tradedAt: Instant,
    val instrumentType: String,
    val userId: String? = null,
    val userRole: String? = null,
    val counterpartyId: String? = null,
)

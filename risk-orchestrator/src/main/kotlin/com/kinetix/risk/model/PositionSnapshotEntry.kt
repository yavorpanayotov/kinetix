package com.kinetix.risk.model

import java.math.BigDecimal

data class PositionSnapshotEntry(
    val instrumentId: String,
    val assetClass: String,
    val quantity: BigDecimal,
    val averageCostAmount: BigDecimal,
    val marketPriceAmount: BigDecimal,
    val currency: String,
    val marketValueAmount: BigDecimal,
    val unrealizedPnlAmount: BigDecimal,
    val instrumentType: String,
)

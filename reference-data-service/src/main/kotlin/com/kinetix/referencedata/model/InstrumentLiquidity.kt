package com.kinetix.referencedata.model

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.LiquidityTier
import java.time.Instant

data class InstrumentLiquidity(
    val instrumentId: String,
    val adv: Double,
    val bidAskSpreadBps: Double,
    val assetClass: AssetClass,
    val liquidityTier: LiquidityTier,
    val advUpdatedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val advShares: Double? = null,
    val marketDepthScore: Double? = null,
    val source: String = "unknown",
    val hedgingEligible: Boolean? = null,
)

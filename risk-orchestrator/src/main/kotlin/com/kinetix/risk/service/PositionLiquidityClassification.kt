package com.kinetix.risk.service

import com.kinetix.common.model.LiquidityTier

/**
 * The result of classifying a single position into a liquidity tier and
 * a liquidation horizon. See [InstrumentLiquidityClassifier].
 *
 * [advDataMissing] is kept explicit (rather than collapsing it into the
 * tier) so the UI can distinguish "we have no ADV reference data for this
 * instrument" — a data-completeness problem — from "this instrument is
 * genuinely illiquid". The trader-review walkthrough showed JPM (the
 * largest US bank holding) flagged ILLIQUID with no visual signal that
 * the cause was a missing seed row.
 */
data class PositionLiquidityClassification(
    val tier: LiquidityTier,
    val horizonDays: Int,
    val advDataMissing: Boolean,
)

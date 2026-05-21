package com.kinetix.common.model

import java.time.Instant

data class ForwardCurve(
    val instrumentId: InstrumentId,
    val assetClass: AssetClass,
    val points: List<CurvePoint>,
    val asOfDate: Instant,
    val source: RateSource,
) {
    init {
        require(points.isNotEmpty()) { "Forward curve must have at least one point" }
    }
}

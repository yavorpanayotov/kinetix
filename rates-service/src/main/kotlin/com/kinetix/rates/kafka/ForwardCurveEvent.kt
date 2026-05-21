package com.kinetix.rates.kafka

import com.kinetix.common.model.ForwardCurve
import kotlinx.serialization.Serializable

@Serializable
data class CurvePointEvent(val tenor: String, val value: String)

@Serializable
data class ForwardCurveEvent(
    val instrumentId: String,
    val assetClass: String,
    val points: List<CurvePointEvent>,
    val asOfDate: String,
    val source: String,
) {
    companion object {
        fun from(curve: ForwardCurve) = ForwardCurveEvent(
            instrumentId = curve.instrumentId.value,
            assetClass = curve.assetClass.name,
            points = curve.points.map { CurvePointEvent(it.tenor, it.value.toBigDecimal().toPlainString()) },
            asOfDate = curve.asOfDate.toString(),
            source = curve.source.name,
        )
    }
}

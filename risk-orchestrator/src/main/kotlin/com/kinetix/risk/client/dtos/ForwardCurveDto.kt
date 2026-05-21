package com.kinetix.risk.client.dtos

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.CurvePoint
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.RateSource
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class CurvePointDto(
    val tenor: String,
    val value: String,
) {
    fun toDomain(): CurvePoint = CurvePoint(
        tenor = tenor,
        value = value.toDouble(),
    )
}

@Serializable
data class ForwardCurveDto(
    val instrumentId: String,
    val assetClass: String,
    val points: List<CurvePointDto>,
    val asOfDate: String,
    val source: String,
) {
    fun toDomain(): ForwardCurve = ForwardCurve(
        instrumentId = InstrumentId(instrumentId),
        assetClass = AssetClass.valueOf(assetClass),
        points = points.map { it.toDomain() },
        asOfDate = Instant.parse(asOfDate),
        source = RateSource.valueOf(source),
    )
}

package com.kinetix.rates.cache

import com.kinetix.common.model.*
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

class RedisRatesCache(
    private val connection: StatefulRedisConnection<String, String>,
) : RatesCache {

    private val async = connection.async()

    override suspend fun putYieldCurve(curve: YieldCurve) {
        val key = "yield-curve:${curve.curveId}"
        val value = Json.encodeToString(CachedYieldCurve.from(curve))
        async.set(key, value).await()
    }

    override suspend fun getYieldCurve(curveId: String): YieldCurve? {
        val key = "yield-curve:$curveId"
        val value = async.get(key).await() ?: return null
        return Json.decodeFromString<CachedYieldCurve>(value).toDomain()
    }

    override suspend fun putRiskFreeRate(rate: RiskFreeRate) {
        val key = "risk-free-rate:${rate.currency.currencyCode}:${rate.tenor}"
        val value = Json.encodeToString(CachedRiskFreeRate.from(rate))
        async.set(key, value).await()
    }

    override suspend fun getRiskFreeRate(currency: Currency, tenor: String): RiskFreeRate? {
        val key = "risk-free-rate:${currency.currencyCode}:$tenor"
        val value = async.get(key).await() ?: return null
        return Json.decodeFromString<CachedRiskFreeRate>(value).toDomain()
    }

    override suspend fun putForwardCurve(curve: ForwardCurve) {
        val key = "forward-curve:${curve.instrumentId.value}"
        val value = Json.encodeToString(CachedForwardCurve.from(curve))
        async.set(key, value).await()
    }

    override suspend fun getForwardCurve(instrumentId: InstrumentId): ForwardCurve? {
        val key = "forward-curve:${instrumentId.value}"
        val value = async.get(key).await() ?: return null
        return Json.decodeFromString<CachedForwardCurve>(value).toDomain()
    }

    @Serializable
    internal data class CachedTenor(val label: String, val days: Int, val rate: String) {
        companion object {
            fun from(tenor: Tenor) = CachedTenor(tenor.label, tenor.days, tenor.rate.toPlainString())
        }
        fun toDomain() = Tenor(label, days, BigDecimal(rate))
    }

    @Serializable
    internal data class CachedYieldCurve(
        val curveId: String,
        val currency: String,
        val tenors: List<CachedTenor>,
        val asOfDate: String,
        val source: String,
    ) {
        companion object {
            fun from(curve: YieldCurve) = CachedYieldCurve(
                curveId = curve.curveId,
                currency = curve.currency.currencyCode,
                tenors = curve.tenors.map { CachedTenor.from(it) },
                asOfDate = curve.asOf.toString(),
                source = curve.source.name,
            )
        }
        fun toDomain() = YieldCurve(
            currency = Currency.getInstance(currency),
            asOf = Instant.parse(asOfDate),
            tenors = tenors.map { it.toDomain() },
            curveId = curveId,
            source = RateSource.valueOf(source),
        )
    }

    @Serializable
    internal data class CachedRiskFreeRate(
        val currency: String,
        val tenor: String,
        val rate: String,
        val asOfDate: String,
        val source: String,
    ) {
        companion object {
            fun from(rate: RiskFreeRate) = CachedRiskFreeRate(
                currency = rate.currency.currencyCode,
                tenor = rate.tenor,
                rate = rate.rate.toBigDecimal().toPlainString(),
                asOfDate = rate.asOfDate.toString(),
                source = rate.source.name,
            )
        }
        fun toDomain() = RiskFreeRate(
            currency = Currency.getInstance(currency),
            tenor = tenor,
            rate = BigDecimal(rate).toDouble(),
            asOfDate = Instant.parse(asOfDate),
            source = RateSource.valueOf(source),
        )
    }

    @Serializable
    internal data class CachedCurvePoint(val tenor: String, val value: String) {
        companion object {
            fun from(point: CurvePoint) = CachedCurvePoint(point.tenor, point.value.toBigDecimal().toPlainString())
        }
        fun toDomain() = CurvePoint(tenor, BigDecimal(value).toDouble())
    }

    @Serializable
    internal data class CachedForwardCurve(
        val instrumentId: String,
        val assetClass: String,
        val points: List<CachedCurvePoint>,
        val asOfDate: String,
        val source: String,
    ) {
        companion object {
            fun from(curve: ForwardCurve) = CachedForwardCurve(
                instrumentId = curve.instrumentId.value,
                assetClass = curve.assetClass.name,
                points = curve.points.map { CachedCurvePoint.from(it) },
                asOfDate = curve.asOfDate.toString(),
                source = curve.source.name,
            )
        }
        fun toDomain() = ForwardCurve(
            instrumentId = InstrumentId(instrumentId),
            assetClass = AssetClass.valueOf(assetClass),
            points = points.map { it.toDomain() },
            asOfDate = Instant.parse(asOfDate),
            source = RateSource.valueOf(source),
        )
    }
}

package com.kinetix.risk.client

import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.proto.risk.DataDependenciesRequest
import com.kinetix.proto.risk.DataDependenciesResponse
import com.kinetix.proto.risk.FactorDecompositionRequest
import com.kinetix.proto.risk.FactorReturnSeries
import com.kinetix.proto.risk.FactorType
import com.kinetix.proto.risk.MarketDataDependenciesServiceGrpcKt.MarketDataDependenciesServiceCoroutineStub
import com.kinetix.proto.risk.PositionLoadingInput
import com.kinetix.proto.risk.RiskCalculationServiceGrpcKt.RiskCalculationServiceCoroutineStub
import com.kinetix.proto.risk.VaRRequest
import com.kinetix.proto.risk.ValuationRequest
import com.kinetix.risk.client.dtos.InstrumentDto
import com.kinetix.risk.mapper.toProto
import com.kinetix.risk.mapper.toDomain
import com.kinetix.risk.mapper.toDomainValuation
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.FactorContribution
import com.kinetix.risk.model.FactorDecompositionSnapshot
import com.kinetix.risk.model.MarketDataValue
import com.kinetix.risk.model.TimeSeriesMarketData
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.model.VaRResult
import com.kinetix.risk.model.ValuationOutput
import com.kinetix.risk.model.ValuationResult
import java.time.Instant
import java.util.concurrent.TimeUnit
import com.kinetix.proto.common.BookId as ProtoBookId
import com.kinetix.proto.risk.ValuationOutput as ProtoValuationOutput

/**
 * Maps each factor type to the instrument id whose HISTORICAL_PRICES time series
 * is used as its return proxy. Instruments not present in the fetched market data
 * result in an empty return series; the Python engine then falls back to analytical
 * loading for affected positions.
 */
private val FACTOR_PROXY_INSTRUMENTS = mapOf(
    FactorType.FACTOR_EQUITY_BETA to "IDX-SPX",
    FactorType.FACTOR_RATES_DURATION to "US10Y",
    FactorType.FACTOR_CREDIT_SPREAD to "CDX-IG",
    FactorType.FACTOR_FX_DELTA to "EURUSD",
    FactorType.FACTOR_VOL_EXPOSURE to "VIX",
)

private val PROTO_FACTOR_TO_DOMAIN_NAME = mapOf(
    FactorType.FACTOR_EQUITY_BETA to "EQUITY_BETA",
    FactorType.FACTOR_RATES_DURATION to "RATES_DURATION",
    FactorType.FACTOR_CREDIT_SPREAD to "CREDIT_SPREAD",
    FactorType.FACTOR_FX_DELTA to "FX_DELTA",
    FactorType.FACTOR_VOL_EXPOSURE to "VOL_EXPOSURE",
)

private val DOMAIN_VALUATION_OUTPUT_TO_PROTO = mapOf(
    ValuationOutput.VAR to ProtoValuationOutput.VAR,
    ValuationOutput.EXPECTED_SHORTFALL to ProtoValuationOutput.EXPECTED_SHORTFALL,
    ValuationOutput.GREEKS to ProtoValuationOutput.GREEKS,
    ValuationOutput.PV to ProtoValuationOutput.PV,
)

class GrpcRiskEngineClient(
    private val stub: RiskCalculationServiceCoroutineStub,
    private val dependenciesStub: MarketDataDependenciesServiceCoroutineStub? = null,
    private val deadlineMs: Long = 60_000,
) : RiskEngineClient {

    override suspend fun calculateVaR(
        request: VaRCalculationRequest,
        positions: List<Position>,
        marketData: List<MarketDataValue>,
        instrumentMap: Map<String, InstrumentDto>,
    ): VaRResult {
        val protoRequest = VaRRequest.newBuilder()
            .setBookId(ProtoBookId.newBuilder().setValue(request.bookId.value))
            .setCalculationType(request.calculationType.toProto())
            .setConfidenceLevel(request.confidenceLevel.toProto())
            .setTimeHorizonDays(request.timeHorizonDays)
            .setNumSimulations(request.numSimulations)
            .addAllPositions(positions.map { it.toProto(instrumentMap[it.instrumentId.value]) })
            .addAllMarketData(marketData.map { it.toProto() })
            .build()

        val response = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .calculateVaR(protoRequest)
        return response.toDomain()
    }

    override suspend fun valuate(
        request: VaRCalculationRequest,
        positions: List<Position>,
        marketData: List<MarketDataValue>,
        instrumentMap: Map<String, InstrumentDto>,
    ): ValuationResult {
        val protoRequest = ValuationRequest.newBuilder()
            .setBookId(ProtoBookId.newBuilder().setValue(request.bookId.value))
            .setCalculationType(request.calculationType.toProto())
            .setConfidenceLevel(request.confidenceLevel.toProto())
            .setTimeHorizonDays(request.timeHorizonDays)
            .setNumSimulations(request.numSimulations)
            .addAllPositions(positions.map { it.toProto(instrumentMap[it.instrumentId.value]) })
            .addAllMarketData(marketData.map { it.toProto() })
            .addAllRequestedOutputs(request.requestedOutputs.map { DOMAIN_VALUATION_OUTPUT_TO_PROTO.getValue(it) })
            .setMonteCarloSeed(request.monteCarloSeed)
            .build()

        val response = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .valuate(protoRequest)
        return response.toDomainValuation()
    }

    override suspend fun discoverDependencies(
        positions: List<Position>,
        calculationType: String,
        confidenceLevel: String,
        instrumentMap: Map<String, InstrumentDto>,
    ): DataDependenciesResponse {
        val calcType = CalculationType.valueOf(calculationType)
        val confLevel = ConfidenceLevel.valueOf(confidenceLevel)

        val protoRequest = DataDependenciesRequest.newBuilder()
            .addAllPositions(positions.map { it.toProto(instrumentMap[it.instrumentId.value]) })
            .setCalculationType(calcType.toProto())
            .setConfidenceLevel(confLevel.toProto())
            .build()

        return requireNotNull(dependenciesStub) {
            "MarketDataDependenciesService stub not configured"
        }.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .discoverDependencies(protoRequest)
    }

    override suspend fun decomposeFactorRisk(
        bookId: BookId,
        positions: List<Position>,
        marketData: Map<String, TimeSeriesMarketData>,
        totalVar: Double,
    ): FactorDecompositionSnapshot {
        val positionInputs = positions.map { position ->
            val series = marketData[position.instrumentId.value]
            val returns = series?.toDailyReturns() ?: emptyList()
            PositionLoadingInput.newBuilder()
                .setInstrumentId(position.instrumentId.value)
                .setAssetClass(position.assetClass.name)
                .setMarketValue(position.marketValue.amount.toDouble())
                .addAllInstrumentReturns(returns)
                .build()
        }

        val factorReturnSeries = FACTOR_PROXY_INSTRUMENTS.map { (factorType, proxyInstrumentId) ->
            val series = marketData[proxyInstrumentId]
            val returns = series?.toDailyReturns() ?: emptyList()
            FactorReturnSeries.newBuilder()
                .setFactor(factorType)
                .addAllReturns(returns)
                .build()
        }

        val request = FactorDecompositionRequest.newBuilder()
            .setBookId(bookId.value)
            .addAllPositions(positionInputs)
            .addAllFactorReturns(factorReturnSeries)
            .setTotalVar(totalVar)
            .setDecompositionDate(Instant.now().toString())
            .build()

        val response = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .decomposeFactorRisk(request)

        val factors = response.factorContributionsList.mapNotNull { fc ->
            val domainName = PROTO_FACTOR_TO_DOMAIN_NAME[fc.factor] ?: return@mapNotNull null
            val loadingResult = response.loadingsList.find { it.factor == fc.factor }
            FactorContribution(
                factorType = domainName,
                factorExposure = fc.factorExposure,
                varContribution = fc.factorVar,
                pnlAttribution = fc.pnlAttribution,
                pctOfTotal = fc.pctOfTotalVar,
                loading = loadingResult?.loading ?: 0.0,
                loadingMethod = loadingResult?.method?.name?.removePrefix("LOADING_") ?: "ANALYTICAL",
            )
        }

        return FactorDecompositionSnapshot(
            bookId = bookId.value,
            calculatedAt = Instant.now(),
            totalVar = response.totalVar,
            systematicVar = response.systematicVar,
            idiosyncraticVar = response.idiosyncraticVar,
            rSquared = response.rSquared,
            concentrationWarning = factors.any { it.pctOfTotal > 0.60 },
            factors = factors,
        )
    }

    /**
     * Converts a price time series to daily log returns.
     * Returns are calculated as ln(P_t / P_{t-1}) for each consecutive pair.
     */
    private fun TimeSeriesMarketData.toDailyReturns(): List<Double> {
        val sorted = points.sortedBy { it.timestamp }
        if (sorted.size < 2) return emptyList()
        return sorted.zipWithNext { a, b ->
            if (a.value > 0 && b.value > 0) Math.log(b.value / a.value) else 0.0
        }
    }
}

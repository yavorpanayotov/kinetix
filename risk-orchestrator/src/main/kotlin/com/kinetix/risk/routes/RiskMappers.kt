package com.kinetix.risk.routes

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.risk.model.GreeksChange
import com.kinetix.risk.model.HypotheticalTrade
import com.kinetix.risk.model.PnlAttribution
import com.kinetix.risk.model.PositionPnlAttribution
import com.kinetix.risk.model.PositionRisk
import com.kinetix.risk.model.RebalancingTrade
import com.kinetix.risk.model.RebalancingWhatIfResult
import com.kinetix.risk.model.SodBaseline
import com.kinetix.risk.model.SodBaselineStatus
import com.kinetix.risk.model.TradeVarContribution
import com.kinetix.risk.model.ValuationResult
import com.kinetix.risk.model.WhatIfResult
import com.kinetix.risk.routes.dtos.ComponentBreakdownDto
import com.kinetix.risk.routes.dtos.GreekValuesDto
import com.kinetix.risk.routes.dtos.GreeksChangeDto
import com.kinetix.risk.routes.dtos.GreeksResponse
import com.kinetix.risk.routes.dtos.HypotheticalTradeDto
import com.kinetix.risk.routes.dtos.PnlAttributionResponse
import com.kinetix.risk.routes.dtos.PositionGreekDto
import com.kinetix.risk.routes.dtos.PositionPnlAttributionDto
import com.kinetix.risk.routes.dtos.PositionRiskDto
import com.kinetix.risk.routes.dtos.RebalancingTradeDto
import com.kinetix.risk.routes.dtos.RebalancingWhatIfResponse
import com.kinetix.risk.routes.dtos.SodBaselineStatusResponse
import com.kinetix.risk.routes.dtos.SodSnapshotResponse
import com.kinetix.risk.routes.dtos.TradeVarContributionDto
import com.kinetix.risk.routes.dtos.VaRResultResponse
import com.kinetix.risk.routes.dtos.WhatIfResponse
import com.kinetix.proto.risk.FrtbRiskClass
import com.kinetix.proto.risk.MarketDataType
import java.math.BigDecimal
import java.util.Currency

internal fun ValuationResult.toResponse() = VaRResultResponse(
    bookId = bookId.value,
    calculationType = calculationType.name,
    confidenceLevel = confidenceLevel.name,
    varValue = "%.2f".format(varValue ?: 0.0),
    expectedShortfall = "%.2f".format(expectedShortfall ?: 0.0),
    componentBreakdown = componentBreakdown.map {
        ComponentBreakdownDto(
            assetClass = it.assetClass.name,
            varContribution = "%.2f".format(it.varContribution),
            percentageOfTotal = "%.2f".format(it.percentageOfTotal),
        )
    },
    calculatedAt = calculatedAt.toString(),
    greeks = greeks?.let { g ->
        GreeksResponse(
            bookId = bookId.value,
            assetClassGreeks = g.assetClassGreeks.map { gv ->
                GreekValuesDto(
                    assetClass = gv.assetClass.name,
                    delta = "%.6f".format(gv.delta),
                    gamma = "%.6f".format(gv.gamma),
                    vega = "%.6f".format(gv.vega),
                )
            },
            theta = "%.6f".format(g.theta),
            rho = "%.6f".format(g.rho),
            calculatedAt = calculatedAt.toString(),
        )
    },
    computedOutputs = computedOutputs.map { it.name },
    pvValue = pvValue?.let { "%.2f".format(it) },
    positionRisk = positionRisk.takeIf { it.isNotEmpty() }?.map { it.toDto() },
    valuationDate = valuationDate?.toString(),
    marketDataComplete = marketDataComplete,
    positionGreeks = positionGreeks.takeIf { it.isNotEmpty() }?.map { pg ->
        PositionGreekDto(
            instrumentId = pg.instrumentId,
            delta = "%.6f".format(pg.delta),
            gamma = "%.6f".format(pg.gamma),
            vega = "%.6f".format(pg.vega),
            theta = "%.6f".format(pg.theta),
            rho = "%.6f".format(pg.rho),
        )
    },
)

internal fun PositionRisk.toDto() = PositionRiskDto(
    instrumentId = instrumentId.value,
    assetClass = assetClass.name,
    marketValue = marketValue.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
    delta = delta?.let { "%.6f".format(it) },
    gamma = gamma?.let { "%.6f".format(it) },
    vega = vega?.let { "%.6f".format(it) },
    varContribution = varContribution.toPlainString(),
    esContribution = esContribution.toPlainString(),
    percentageOfTotal = percentageOfTotal.toPlainString(),
    instrumentType = instrumentType,
    displayName = displayName,
    theta = theta?.let { "%.6f".format(it) },
    rho = rho?.let { "%.6f".format(it) },
    dv01 = dv01?.let { "%.6f".format(it) },
)

internal fun HypotheticalTradeDto.toDomain() = HypotheticalTrade(
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.valueOf(assetClass),
    side = Side.valueOf(side),
    quantity = BigDecimal(quantity),
    price = Money(BigDecimal(priceAmount), Currency.getInstance(priceCurrency)),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.fromString(instrumentType),
)

internal fun WhatIfResult.toResponse() = WhatIfResponse(
    baseVaR = "%.2f".format(baseVaR),
    baseExpectedShortfall = "%.2f".format(baseExpectedShortfall),
    baseGreeks = baseGreeks?.let { g ->
        GreeksResponse(
            bookId = "",
            assetClassGreeks = g.assetClassGreeks.map { gv ->
                GreekValuesDto(
                    assetClass = gv.assetClass.name,
                    delta = "%.6f".format(gv.delta),
                    gamma = "%.6f".format(gv.gamma),
                    vega = "%.6f".format(gv.vega),
                )
            },
            theta = "%.6f".format(g.theta),
            rho = "%.6f".format(g.rho),
            calculatedAt = calculatedAt.toString(),
        )
    },
    basePositionRisk = basePositionRisk.map { it.toDto() },
    hypotheticalVaR = "%.2f".format(hypotheticalVaR),
    hypotheticalExpectedShortfall = "%.2f".format(hypotheticalExpectedShortfall),
    hypotheticalGreeks = hypotheticalGreeks?.let { g ->
        GreeksResponse(
            bookId = "",
            assetClassGreeks = g.assetClassGreeks.map { gv ->
                GreekValuesDto(
                    assetClass = gv.assetClass.name,
                    delta = "%.6f".format(gv.delta),
                    gamma = "%.6f".format(gv.gamma),
                    vega = "%.6f".format(gv.vega),
                )
            },
            theta = "%.6f".format(g.theta),
            rho = "%.6f".format(g.rho),
            calculatedAt = calculatedAt.toString(),
        )
    },
    hypotheticalPositionRisk = hypotheticalPositionRisk.map { it.toDto() },
    varChange = "%.2f".format(varChange),
    esChange = "%.2f".format(esChange),
    calculatedAt = calculatedAt.toString(),
)

internal fun PnlAttribution.toResponse() = PnlAttributionResponse(
    bookId = bookId.value,
    date = date.toString(),
    totalPnl = totalPnl.toPlainString(),
    deltaPnl = deltaPnl.toPlainString(),
    gammaPnl = gammaPnl.toPlainString(),
    vegaPnl = vegaPnl.toPlainString(),
    thetaPnl = thetaPnl.toPlainString(),
    rhoPnl = rhoPnl.toPlainString(),
    vannaPnl = vannaPnl.toPlainString(),
    volgaPnl = volgaPnl.toPlainString(),
    charmPnl = charmPnl.toPlainString(),
    crossGammaPnl = crossGammaPnl.toPlainString(),
    unexplainedPnl = unexplainedPnl.toPlainString(),
    positionAttributions = positionAttributions.map { it.toDto() },
    dataQualityFlag = dataQualityFlag.name,
    calculatedAt = calculatedAt.toString(),
)

internal fun PositionPnlAttribution.toDto() = PositionPnlAttributionDto(
    instrumentId = instrumentId.value,
    assetClass = assetClass.name,
    totalPnl = totalPnl.toPlainString(),
    deltaPnl = deltaPnl.toPlainString(),
    gammaPnl = gammaPnl.toPlainString(),
    vegaPnl = vegaPnl.toPlainString(),
    thetaPnl = thetaPnl.toPlainString(),
    rhoPnl = rhoPnl.toPlainString(),
    vannaPnl = vannaPnl.toPlainString(),
    volgaPnl = volgaPnl.toPlainString(),
    charmPnl = charmPnl.toPlainString(),
    crossGammaPnl = crossGammaPnl.toPlainString(),
    unexplainedPnl = unexplainedPnl.toPlainString(),
)

internal val FRTB_RISK_CLASS_NAMES = mapOf(
    FrtbRiskClass.GIRR to "GIRR",
    FrtbRiskClass.CSR_NON_SEC to "CSR_NON_SEC",
    FrtbRiskClass.CSR_SEC_CTP to "CSR_SEC_CTP",
    FrtbRiskClass.CSR_SEC_NON_CTP to "CSR_SEC_NON_CTP",
    FrtbRiskClass.FRTB_EQUITY to "EQUITY",
    FrtbRiskClass.FRTB_COMMODITY to "COMMODITY",
    FrtbRiskClass.FRTB_FX to "FX",
)

internal val ASSET_CLASS_TO_PROTO = mapOf(
    AssetClass.EQUITY to com.kinetix.proto.common.AssetClass.EQUITY,
    AssetClass.FIXED_INCOME to com.kinetix.proto.common.AssetClass.FIXED_INCOME,
    AssetClass.FX to com.kinetix.proto.common.AssetClass.FX,
    AssetClass.COMMODITY to com.kinetix.proto.common.AssetClass.COMMODITY,
    AssetClass.DERIVATIVE to com.kinetix.proto.common.AssetClass.DERIVATIVE,
)

internal val PROTO_ASSET_CLASS_TO_DOMAIN = ASSET_CLASS_TO_PROTO.entries.associate { (k, v) -> v to k }

internal fun SodBaselineStatus.toResponse() = SodBaselineStatusResponse(
    exists = exists,
    baselineDate = baselineDate,
    snapshotType = snapshotType?.name,
    createdAt = createdAt?.toString(),
    sourceJobId = sourceJobId,
    calculationType = calculationType,
)

internal fun SodBaseline.toSnapshotResponse(snapshotCount: Int) = SodSnapshotResponse(
    bookId = bookId.value,
    baselineDate = baselineDate.toString(),
    snapshotType = snapshotType.name,
    createdAt = createdAt.toString(),
    snapshotCount = snapshotCount,
)

internal val MARKET_DATA_TYPE_NAMES = mapOf(
    MarketDataType.SPOT_PRICE to "SPOT_PRICE",
    MarketDataType.HISTORICAL_PRICES to "HISTORICAL_PRICES",
    MarketDataType.VOLATILITY_SURFACE to "VOLATILITY_SURFACE",
    MarketDataType.YIELD_CURVE to "YIELD_CURVE",
    MarketDataType.RISK_FREE_RATE to "RISK_FREE_RATE",
    MarketDataType.DIVIDEND_YIELD to "DIVIDEND_YIELD",
    MarketDataType.CREDIT_SPREAD to "CREDIT_SPREAD",
    MarketDataType.FORWARD_CURVE to "FORWARD_CURVE",
    MarketDataType.CORRELATION_MATRIX to "CORRELATION_MATRIX",
)

internal fun com.kinetix.proto.risk.StressTestResponse.toStressTestResponse() =
    com.kinetix.risk.routes.dtos.StressTestResponse(
        scenarioName = scenarioName,
        baseVar = "%.2f".format(baseVar),
        stressedVar = "%.2f".format(stressedVar),
        pnlImpact = "%.2f".format(pnlImpact),
        assetClassImpacts = assetClassImpactsList.map {
            com.kinetix.risk.routes.dtos.AssetClassImpactDto(
                assetClass = (PROTO_ASSET_CLASS_TO_DOMAIN[it.assetClass] ?: AssetClass.EQUITY).name,
                baseExposure = "%.2f".format(it.baseExposure),
                stressedExposure = "%.2f".format(it.stressedExposure),
                pnlImpact = "%.2f".format(it.pnlImpact),
            )
        },
        calculatedAt = java.time.Instant.ofEpochSecond(calculatedAt.seconds, calculatedAt.nanos.toLong()).toString(),
        positionImpacts = positionImpactsList.map {
            com.kinetix.risk.routes.dtos.PositionStressImpactDto(
                instrumentId = it.instrumentId,
                assetClass = (PROTO_ASSET_CLASS_TO_DOMAIN[it.assetClass] ?: AssetClass.EQUITY).name,
                baseMarketValue = "%.2f".format(it.baseMarketValue),
                stressedMarketValue = "%.2f".format(it.stressedMarketValue),
                pnlImpact = "%.2f".format(it.pnlImpact),
                percentageOfTotal = "%.2f".format(it.percentageOfTotal),
            )
        },
    )

internal fun com.kinetix.proto.risk.HistoricalReplayResponse.toHistoricalReplayResponse() =
    com.kinetix.risk.routes.dtos.HistoricalReplayResponse(
        scenarioName = scenarioName,
        totalPnlImpact = "%.2f".format(totalPnlImpact),
        positionImpacts = positionImpactsList.map {
            com.kinetix.risk.routes.dtos.PositionReplayImpactDto(
                instrumentId = it.instrumentId,
                assetClass = (PROTO_ASSET_CLASS_TO_DOMAIN[it.assetClass] ?: AssetClass.EQUITY).name,
                marketValue = "%.2f".format(it.marketValue),
                pnlImpact = "%.2f".format(it.pnlImpact),
                dailyPnl = it.dailyPnlList.map { d -> "%.2f".format(d) },
                proxyUsed = it.proxyUsed,
            )
        },
        windowStart = windowStart.takeIf { it.isNotBlank() },
        windowEnd = windowEnd.takeIf { it.isNotBlank() },
        calculatedAt = java.time.Instant.ofEpochSecond(calculatedAt.seconds, calculatedAt.nanos.toLong()).toString(),
    )

internal fun com.kinetix.proto.risk.ReverseStressResponse.toReverseStressResponse() =
    com.kinetix.risk.routes.dtos.ReverseStressResponse(
        shocks = shocksList.map {
            com.kinetix.risk.routes.dtos.InstrumentShockDto(
                instrumentId = it.instrumentId,
                shock = "%.6f".format(it.shock),
            )
        },
        achievedLoss = "%.2f".format(achievedLoss),
        targetLoss = "%.2f".format(targetLoss),
        converged = converged,
        calculatedAt = java.time.Instant.ofEpochSecond(calculatedAt.seconds, calculatedAt.nanos.toLong()).toString(),
    )

internal fun RebalancingTradeDto.toDomain() = RebalancingTrade(
    instrumentId = com.kinetix.common.model.InstrumentId(instrumentId),
    assetClass = com.kinetix.common.model.AssetClass.valueOf(assetClass),
    side = com.kinetix.common.model.Side.valueOf(side),
    quantity = java.math.BigDecimal(quantity),
    price = com.kinetix.common.model.Money(java.math.BigDecimal(priceAmount), java.util.Currency.getInstance(priceCurrency)),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.fromString(instrumentType),
    bidAskSpreadBps = bidAskSpreadBps,
)

internal fun GreeksChange.toDto() = GreeksChangeDto(
    deltaChange = "%.6f".format(deltaChange),
    gammaChange = "%.6f".format(gammaChange),
    vegaChange = "%.6f".format(vegaChange),
    thetaChange = "%.6f".format(thetaChange),
    rhoChange = "%.6f".format(rhoChange),
)

internal fun TradeVarContribution.toDto() = TradeVarContributionDto(
    instrumentId = instrumentId,
    side = side,
    quantity = quantity,
    marginalVarImpact = "%.2f".format(marginalVarImpact),
    executionCost = "%.2f".format(executionCost),
)

internal fun RebalancingWhatIfResult.toResponse() = RebalancingWhatIfResponse(
    baseVar = "%.2f".format(baseVar),
    rebalancedVar = "%.2f".format(rebalancedVar),
    varChange = "%.2f".format(varChange),
    varChangePct = "%.2f".format(varChangePct),
    baseExpectedShortfall = "%.2f".format(baseExpectedShortfall),
    rebalancedExpectedShortfall = "%.2f".format(rebalancedExpectedShortfall),
    esChange = "%.2f".format(esChange),
    baseGreeks = baseGreeks?.let { g ->
        GreeksResponse(
            bookId = "",
            assetClassGreeks = g.assetClassGreeks.map { gv ->
                GreekValuesDto(
                    assetClass = gv.assetClass.name,
                    delta = "%.6f".format(gv.delta),
                    gamma = "%.6f".format(gv.gamma),
                    vega = "%.6f".format(gv.vega),
                )
            },
            theta = "%.6f".format(g.theta),
            rho = "%.6f".format(g.rho),
            calculatedAt = calculatedAt.toString(),
        )
    },
    rebalancedGreeks = rebalancedGreeks?.let { g ->
        GreeksResponse(
            bookId = "",
            assetClassGreeks = g.assetClassGreeks.map { gv ->
                GreekValuesDto(
                    assetClass = gv.assetClass.name,
                    delta = "%.6f".format(gv.delta),
                    gamma = "%.6f".format(gv.gamma),
                    vega = "%.6f".format(gv.vega),
                )
            },
            theta = "%.6f".format(g.theta),
            rho = "%.6f".format(g.rho),
            calculatedAt = calculatedAt.toString(),
        )
    },
    greeksChange = greeksChange.toDto(),
    tradeContributions = tradeContributions.map { it.toDto() },
    estimatedExecutionCost = "%.2f".format(estimatedExecutionCost),
    calculatedAt = calculatedAt.toString(),
)

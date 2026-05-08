package com.kinetix.risk.service

import com.kinetix.common.model.BookId
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.model.*
import java.math.BigDecimal
import java.time.Instant

class RebalancingWhatIfService(
    private val positionProvider: PositionProvider,
    private val riskEngineClient: RiskEngineClient,
    private val whatIfAnalysisService: WhatIfAnalysisService,
) {
    suspend fun analyzeRebalancing(
        bookId: BookId,
        trades: List<RebalancingTrade>,
        calculationType: CalculationType,
        confidenceLevel: ConfidenceLevel,
    ): RebalancingWhatIfResult {
        val positions = positionProvider.getPositions(bookId)
        val request = VaRCalculationRequest(
            bookId = bookId,
            calculationType = calculationType,
            confidenceLevel = confidenceLevel,
        )

        val baseResult = riskEngineClient.valuate(request, positions)
        val baseVar = baseResult.varValue ?: 0.0
        val baseES = baseResult.expectedShortfall ?: 0.0

        if (trades.isEmpty()) {
            return RebalancingWhatIfResult(
                baseVar = baseVar,
                rebalancedVar = baseVar,
                varChange = 0.0,
                varChangePct = 0.0,
                baseExpectedShortfall = baseES,
                rebalancedExpectedShortfall = baseES,
                esChange = 0.0,
                baseGreeks = baseResult.greeks,
                rebalancedGreeks = baseResult.greeks,
                greeksChange = zeroGreeksChange(),
                tradeContributions = emptyList(),
                estimatedExecutionCost = 0.0,
                calculatedAt = Instant.now(),
            )
        }

        val tradeContributions = computeMarginalContributions(
            positions = positions,
            trades = trades,
            request = request,
            startingVar = baseVar,
        )

        val hypotheticalTrades = trades.map { it.toHypotheticalTrade() }
        val rebalancedPositions = whatIfAnalysisService.applyHypotheticalTrades(positions, hypotheticalTrades)
        val rebalancedResult = riskEngineClient.valuate(request, rebalancedPositions)

        val rebalancedVar = rebalancedResult.varValue ?: 0.0
        val rebalancedES = rebalancedResult.expectedShortfall ?: 0.0
        val varChange = rebalancedVar - baseVar
        val varChangePct = if (baseVar != 0.0) (varChange / baseVar) * 100.0 else 0.0

        val estimatedExecutionCost = trades.sumOf { computeExecutionCost(it) }

        return RebalancingWhatIfResult(
            baseVar = baseVar,
            rebalancedVar = rebalancedVar,
            varChange = varChange,
            varChangePct = varChangePct,
            baseExpectedShortfall = baseES,
            rebalancedExpectedShortfall = rebalancedES,
            esChange = rebalancedES - baseES,
            baseGreeks = baseResult.greeks,
            rebalancedGreeks = rebalancedResult.greeks,
            greeksChange = computeGreeksChange(baseResult.greeks, rebalancedResult.greeks),
            tradeContributions = tradeContributions,
            estimatedExecutionCost = estimatedExecutionCost,
            calculatedAt = Instant.now(),
        )
    }

    private suspend fun computeMarginalContributions(
        positions: List<com.kinetix.common.model.Position>,
        trades: List<RebalancingTrade>,
        request: VaRCalculationRequest,
        startingVar: Double,
    ): List<TradeVarContribution> {
        val contributions = mutableListOf<TradeVarContribution>()
        var currentPositions = positions
        var previousVar = startingVar

        for (trade in trades) {
            val hypotheticalTrade = trade.toHypotheticalTrade()
            currentPositions = whatIfAnalysisService.applyHypotheticalTrades(currentPositions, listOf(hypotheticalTrade))
            val result = riskEngineClient.valuate(request, currentPositions)
            val varAfterTrade = result.varValue ?: previousVar
            val marginalImpact = varAfterTrade - previousVar
            previousVar = varAfterTrade

            contributions.add(
                TradeVarContribution(
                    instrumentId = trade.instrumentId.value,
                    side = trade.side.name,
                    quantity = trade.quantity.toPlainString(),
                    marginalVarImpact = marginalImpact,
                    executionCost = computeExecutionCost(trade),
                )
            )
        }

        return contributions
    }

    private fun computeExecutionCost(trade: RebalancingTrade): Double {
        val notional = trade.quantity.toDouble() * trade.price.amount.toDouble()
        return notional * (trade.bidAskSpreadBps / 10_000.0)
    }

    private fun computeGreeksChange(base: GreeksResult?, rebalanced: GreeksResult?): GreeksChange {
        fun sumDelta(g: GreeksResult?) = g?.assetClassGreeks?.sumOf { it.delta } ?: 0.0
        fun sumGamma(g: GreeksResult?) = g?.assetClassGreeks?.sumOf { it.gamma } ?: 0.0
        fun sumVega(g: GreeksResult?) = g?.assetClassGreeks?.sumOf { it.vega } ?: 0.0

        return GreeksChange(
            deltaChange = sumDelta(rebalanced) - sumDelta(base),
            gammaChange = sumGamma(rebalanced) - sumGamma(base),
            vegaChange = sumVega(rebalanced) - sumVega(base),
            thetaChange = (rebalanced?.theta ?: 0.0) - (base?.theta ?: 0.0),
            rhoChange = (rebalanced?.rho ?: 0.0) - (base?.rho ?: 0.0),
        )
    }

    private fun zeroGreeksChange() = GreeksChange(
        deltaChange = 0.0,
        gammaChange = 0.0,
        vegaChange = 0.0,
        thetaChange = 0.0,
        rhoChange = 0.0,
    )
}

private fun RebalancingTrade.toHypotheticalTrade() = HypotheticalTrade(
    instrumentId = instrumentId,
    assetClass = assetClass,
    side = side,
    quantity = quantity,
    price = price,
    instrumentType = instrumentType,
)

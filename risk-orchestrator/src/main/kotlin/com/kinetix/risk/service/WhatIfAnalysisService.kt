package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.model.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

class WhatIfAnalysisService(
    private val positionProvider: PositionProvider,
    private val riskEngineClient: RiskEngineClient,
) {
    fun applyHypotheticalTrades(
        positions: List<Position>,
        trades: List<HypotheticalTrade>,
    ): List<Position> {
        val positionsByInstrument = positions.associateBy { it.instrumentId }.toMutableMap()
        val bookId = positions.firstOrNull()?.bookId ?: BookId("unknown")

        for (trade in trades) {
            val existing = positionsByInstrument[trade.instrumentId]
            if (existing != null) {
                val asTrade = Trade(
                    tradeId = TradeId("hypothetical"),
                    bookId = existing.bookId,
                    instrumentId = trade.instrumentId,
                    assetClass = trade.assetClass,
                    side = trade.side,
                    quantity = trade.quantity,
                    price = trade.price,
                    tradedAt = Instant.now(),
                    instrumentType = trade.instrumentType,
                )
                positionsByInstrument[trade.instrumentId] = existing.applyTrade(asTrade)
            } else {
                val signedQty = trade.quantity * trade.side.sign.toBigDecimal()
                val newPosition = Position(
                    bookId = bookId,
                    instrumentId = trade.instrumentId,
                    assetClass = trade.assetClass,
                    quantity = signedQty,
                    averageCost = trade.price,
                    marketPrice = trade.price,
                    instrumentType = trade.instrumentType,
                )
                positionsByInstrument[trade.instrumentId] = newPosition
            }
        }

        return positionsByInstrument.values.filter { it.quantity.compareTo(BigDecimal.ZERO) != 0 }
    }

    suspend fun analyzeWhatIf(
        bookId: BookId,
        hypotheticalTrades: List<HypotheticalTrade>,
        calculationType: CalculationType,
        confidenceLevel: ConfidenceLevel,
    ): WhatIfResult {
        val positions = positionProvider.getPositions(bookId)
        val hypotheticalPositions = applyHypotheticalTrades(positions, hypotheticalTrades)

        val request = VaRCalculationRequest(
            bookId = bookId,
            calculationType = calculationType,
            confidenceLevel = confidenceLevel,
        )

        val baseResult = riskEngineClient.valuate(request, positions)
        val hypotheticalResult = riskEngineClient.valuate(request, hypotheticalPositions)

        val baseVaR = baseResult.varValue ?: 0.0
        val baseES = baseResult.expectedShortfall ?: 0.0
        val hypoVaR = hypotheticalResult.varValue ?: 0.0
        val hypoES = hypotheticalResult.expectedShortfall ?: 0.0

        return WhatIfResult(
            baseVaR = baseVaR,
            baseExpectedShortfall = baseES,
            baseGreeks = baseResult.greeks,
            basePositionRisk = baseResult.positionRisk,
            hypotheticalVaR = hypoVaR,
            hypotheticalExpectedShortfall = hypoES,
            hypotheticalGreeks = hypotheticalResult.greeks,
            hypotheticalPositionRisk = hypotheticalResult.positionRisk,
            varChange = hypoVaR - baseVaR,
            esChange = hypoES - baseES,
            calculatedAt = Instant.now(),
        )
    }
}

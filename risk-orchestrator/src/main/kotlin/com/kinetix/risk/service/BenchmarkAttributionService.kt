package com.kinetix.risk.service

import com.kinetix.common.model.BookId
import com.kinetix.risk.client.AttributionEngineClient
import com.kinetix.risk.client.BenchmarkServiceClient
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.SectorInput
import com.kinetix.risk.model.BrinsonAttributionResult
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Orchestrates Brinson-Hood-Beebower performance attribution for a book against a benchmark.
 *
 * Portfolio weights are computed from position market values relative to the total book market value.
 * Benchmark weights are read from the benchmark's constituent list as of the requested date.
 * Portfolio returns are computed from unrealized P&L relative to cost basis.
 * Benchmark returns use portfolio returns as a proxy for shared constituents;
 * a proper implementation requires a benchmark returns data feed.
 */
class BenchmarkAttributionService(
    private val positionProvider: PositionProvider,
    private val benchmarkServiceClient: BenchmarkServiceClient,
    private val attributionEngineClient: AttributionEngineClient,
) {
    private val logger = LoggerFactory.getLogger(BenchmarkAttributionService::class.java)

    suspend fun calculateAttribution(
        bookId: BookId,
        benchmarkId: String,
        asOfDate: LocalDate,
    ): BrinsonAttributionResult {
        val positions = positionProvider.getPositions(bookId)
        require(positions.isNotEmpty()) {
            "No positions found for book ${bookId.value}; cannot compute attribution"
        }

        val benchmarkDetail = when (val resp = benchmarkServiceClient.getBenchmarkDetail(benchmarkId, asOfDate)) {
            is ClientResponse.Success -> resp.value
            else -> throw IllegalArgumentException(
                "Benchmark not found or unavailable: $benchmarkId"
            )
        }

        val benchmarkWeightByInstrument: Map<String, Double> = benchmarkDetail.constituents
            .associate { it.instrumentId to it.weight.toDouble() }

        val totalMarketValue: BigDecimal = positions.sumOf { it.marketValue.amount }
        require(totalMarketValue > BigDecimal.ZERO) {
            "Total market value is zero for book ${bookId.value}; cannot compute portfolio weights"
        }

        // Brinson requires the benchmark weight vector to be a distribution summing to
        // 1.0 over the sectors presented (one sector per held instrument). Published
        // index weights are partial — they cover ~500 names and the book holds only a
        // subset — so we normalise the mapped weights over the book's benchmark overlap.
        // This frames the attribution as portfolio vs. the benchmark restricted to the
        // names the book actually holds.
        val totalBenchmarkWeight = positions.sumOf { benchmarkWeightByInstrument[it.instrumentId.value] ?: 0.0 }
        require(totalBenchmarkWeight > 0.0) {
            "Book ${bookId.value} holds none of benchmark $benchmarkId's constituents; cannot compute attribution"
        }

        val sectors = positions.map { position ->
            val instrumentId = position.instrumentId.value
            val portfolioWeight = position.marketValue.amount
                .divide(totalMarketValue, 10, java.math.RoundingMode.HALF_UP)
                .toDouble()
            val benchmarkWeight = (benchmarkWeightByInstrument[instrumentId] ?: 0.0) / totalBenchmarkWeight

            // Portfolio return: unrealized P&L / cost basis (market value - unrealized P&L)
            val pnl = position.unrealizedPnl.amount
            val costBasis = position.marketValue.amount - pnl
            val portfolioReturn = if (costBasis.signum() > 0) {
                pnl.divide(costBasis, 10, java.math.RoundingMode.HALF_UP).toDouble()
            } else 0.0

            // Benchmark return: use portfolio return as proxy for constituents in our book;
            // off-benchmark holdings get zero benchmark return.
            // A proper implementation requires a benchmark returns data feed.
            val benchmarkReturn = if (benchmarkWeight > 0.0) portfolioReturn else 0.0

            SectorInput(
                sectorLabel = instrumentId,
                portfolioWeight = portfolioWeight,
                benchmarkWeight = benchmarkWeight,
                portfolioReturn = portfolioReturn,
                benchmarkReturn = benchmarkReturn,
            )
        }

        logger.debug(
            "Calculating Brinson attribution for book={} benchmark={} asOfDate={} sectors={}",
            bookId.value, benchmarkId, asOfDate, sectors.size,
        )

        return attributionEngineClient.calculateBrinsonAttribution(sectors)
    }
}

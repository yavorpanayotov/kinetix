package com.kinetix.risk.service

import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.kafka.CrossBookRiskResultPublisher
import com.kinetix.risk.model.BookVaRContribution
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.CrossBookVaRRequest
import com.kinetix.risk.model.CrossBookValuationResult
import com.kinetix.risk.model.FetchSuccess
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.model.ValuationOutput
import com.kinetix.risk.routes.dtos.StressedCrossBookVaRResultResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

class CrossBookVaRCalculationService(
    private val positionProvider: PositionProvider,
    private val riskEngineClient: RiskEngineClient,
    private val resultPublisher: CrossBookRiskResultPublisher,
    private val varCache: VaRCache,
    private val dependenciesDiscoverer: DependenciesDiscoverer? = null,
    private val marketDataFetcher: MarketDataFetcher? = null,
) {
    private val logger = LoggerFactory.getLogger(CrossBookVaRCalculationService::class.java)

    suspend fun calculate(request: CrossBookVaRRequest, correlationId: String? = null): CrossBookValuationResult? {
        // Phase 1: Fetch positions for all books in parallel
        val positionsByBook: Map<BookId, List<Position>> = coroutineScope {
            request.bookIds.map { bookId ->
                async {
                    bookId to positionProvider.getPositions(bookId)
                }
            }.awaitAll()
        }.filter { (_, positions) -> positions.isNotEmpty() }
            .toMap()

        // Phase 2: Merge all positions into flat list
        val allPositions = positionsByBook.values.flatten()
        if (allPositions.isEmpty()) {
            logger.info("No positions found for any book in group {}, skipping cross-book VaR", request.portfolioGroupId)
            return null
        }

        logger.info(
            "Calculating cross-book {} VaR for group {} with {} books, {} total positions",
            request.calculationType, request.portfolioGroupId, positionsByBook.size, allPositions.size,
        )

        // Phase 3: Discover dependencies on merged list
        val dependencies = try {
            dependenciesDiscoverer?.discover(
                allPositions,
                request.calculationType.name,
                request.confidenceLevel.name,
            ) ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Market data dependency discovery failed for cross-book VaR, proceeding with defaults", e)
            emptyList()
        }

        // Phase 4: Fetch market data
        val marketData = try {
            if (dependencies.isNotEmpty() && marketDataFetcher != null) {
                marketDataFetcher.fetch(dependencies)
                    .filterIsInstance<FetchSuccess>()
                    .map { it.value }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.warn("Market data fetch failed for cross-book VaR, proceeding with defaults", e)
            emptyList()
        }

        // Phase 5: Build VaRCalculationRequest with synthetic portfolio ID and call risk engine
        val effectiveSeed = if (request.calculationType == CalculationType.MONTE_CARLO && request.monteCarloSeed == 0L) {
            System.nanoTime()
        } else {
            request.monteCarloSeed
        }

        val syntheticPortfolioId = BookId("cross-book:${request.portfolioGroupId}")
        val varRequest = VaRCalculationRequest(
            bookId = syntheticPortfolioId,
            calculationType = request.calculationType,
            confidenceLevel = request.confidenceLevel,
            timeHorizonDays = request.timeHorizonDays,
            numSimulations = request.numSimulations,
            requestedOutputs = setOf(
                ValuationOutput.VAR,
                ValuationOutput.EXPECTED_SHORTFALL,
                // Greeks over the merged position set are the firm-level aggregate
                // sensitivities the cross-book dashboard renders. Without this the
                // aggregated Risk view has no greeks to show.
                ValuationOutput.GREEKS,
            ),
            monteCarloSeed = effectiveSeed,
        )

        val valuationResult = riskEngineClient.valuate(varRequest, allPositions, marketData)

        val crossBookVarValue = valuationResult.varValue ?: 0.0
        val crossBookEs = valuationResult.expectedShortfall ?: 0.0

        // Phase 6: Compute book contributions using asset-class market value weighting
        val bookContributions = computeBookContributions(
            positionsByBook,
            valuationResult.componentBreakdown,
            crossBookVarValue,
            request.bookIds,
        )

        val totalStandaloneVar = bookContributions.sumOf { it.standaloneVar }
        val diversificationBenefit = maxOf(0.0, totalStandaloneVar - crossBookVarValue)

        // Phase 7: Build and return CrossBookValuationResult
        val result = CrossBookValuationResult(
            portfolioGroupId = request.portfolioGroupId,
            bookIds = request.bookIds,
            calculationType = request.calculationType,
            confidenceLevel = request.confidenceLevel,
            varValue = crossBookVarValue,
            expectedShortfall = crossBookEs,
            componentBreakdown = valuationResult.componentBreakdown,
            bookContributions = bookContributions,
            totalStandaloneVar = totalStandaloneVar,
            diversificationBenefit = diversificationBenefit,
            calculatedAt = Instant.now(),
            modelVersion = valuationResult.modelVersion,
            monteCarloSeed = effectiveSeed,
            jobId = UUID.randomUUID(),
            greeks = valuationResult.greeks,
        )

        // Publish result
        try {
            resultPublisher.publish(result, correlationId)
        } catch (e: Exception) {
            logger.error("Failed to publish cross-book VaR result for group {}", request.portfolioGroupId, e)
        }

        logger.info(
            "Cross-book VaR calculation complete for group {}: VaR={}, diversificationBenefit={}",
            request.portfolioGroupId, crossBookVarValue, diversificationBenefit,
        )

        return result
    }

    suspend fun calculateStressed(
        request: CrossBookVaRRequest,
        stressCorrelation: Double = 0.9,
    ): StressedCrossBookVaRResultResponse? {
        // Compute base cross-book VaR with normal correlations
        val baseResult = calculate(request) ?: return null

        // For the stressed result, re-run with the same request.
        // The Python risk engine computes the stressed scenario natively via
        // calculate_stressed_cross_book_var, but the orchestrator currently calls
        // the standard VaR RPC.  As a pragmatic approximation, we compute a
        // stressed VaR by re-using the base result and scaling the diversification
        // benefit based on the stress correlation.
        //
        // The stress correlation controls how much diversification survives:
        //   - At rho=0 (uncorrelated), max diversification
        //   - At rho=1 (perfect correlation), zero diversification
        // We compute the stressed diversification benefit as:
        //   stressed_benefit = base_benefit * (1 - stressCorrelation)
        // This is a simplification; the Python engine does it properly via
        // matrix replacement. For the orchestrator endpoint, we apply the same
        // logic: stressed VaR = base VaR + eroded benefit.
        val baseBenefit = baseResult.diversificationBenefit
        val stressedBenefit = baseBenefit * (1.0 - stressCorrelation)
        val benefitErosion = baseBenefit - stressedBenefit
        val stressedVaR = baseResult.varValue + benefitErosion
        val benefitErosionPct = if (baseBenefit > 0.0) {
            (benefitErosion / baseBenefit) * 100.0
        } else {
            0.0
        }

        logger.info(
            "Stressed cross-book VaR for group {}: baseVaR={}, stressedVaR={}, " +
                "baseBenefit={}, stressedBenefit={}, erosion={}, erosionPct={}%",
            request.portfolioGroupId, baseResult.varValue, stressedVaR,
            baseBenefit, stressedBenefit, benefitErosion, benefitErosionPct,
        )

        return StressedCrossBookVaRResultResponse(
            baseVaR = "%.2f".format(baseResult.varValue),
            stressedVaR = "%.2f".format(stressedVaR),
            baseDiversificationBenefit = "%.2f".format(baseBenefit),
            stressedDiversificationBenefit = "%.2f".format(stressedBenefit),
            benefitErosion = "%.2f".format(benefitErosion),
            benefitErosionPct = "%.2f".format(benefitErosionPct),
            stressCorrelation = "%.2f".format(stressCorrelation),
        )
    }

    private fun computeBookContributions(
        positionsByBook: Map<BookId, List<Position>>,
        componentBreakdown: List<com.kinetix.risk.model.ComponentBreakdown>,
        crossBookVarValue: Double,
        allBookIds: List<BookId>,
    ): List<BookVaRContribution> {
        val breakdownByAssetClass = componentBreakdown.associateBy { it.assetClass }

        // For each asset class, compute total absolute market value across all books
        val allPositions = positionsByBook.values.flatten()
        val totalAbsMarketValueByAssetClass = allPositions.groupBy { it.assetClass }
            .mapValues { (_, poses) -> poses.fold(BigDecimal.ZERO) { acc, p -> acc + p.marketValue.amount.abs() } }

        return allBookIds.map { bookId ->
            val bookPositions = positionsByBook[bookId] ?: emptyList()

            // Compute book's VaR contribution by weighting each asset class
            var bookVarContribution = 0.0
            val bookAbsMarketValueByAssetClass = bookPositions.groupBy { it.assetClass }
                .mapValues { (_, poses) -> poses.fold(BigDecimal.ZERO) { acc, p -> acc + p.marketValue.amount.abs() } }

            for ((assetClass, totalAbsMv) in totalAbsMarketValueByAssetClass) {
                val breakdown = breakdownByAssetClass[assetClass] ?: continue
                val bookAbsMv = bookAbsMarketValueByAssetClass[assetClass] ?: BigDecimal.ZERO
                if (totalAbsMv.compareTo(BigDecimal.ZERO) != 0) {
                    val weight = bookAbsMv.divide(totalAbsMv, 10, RoundingMode.HALF_UP).toDouble()
                    bookVarContribution += breakdown.varContribution * weight
                }
            }

            // Lookup standalone VaR from cache
            val standaloneVar = varCache.get(bookId.value)?.varValue ?: run {
                if (bookPositions.isNotEmpty()) {
                    logger.warn("No cached standalone VaR for book {}, using 0.0", bookId.value)
                }
                0.0
            }

            val percentageOfTotal = if (crossBookVarValue != 0.0) {
                (bookVarContribution / crossBookVarValue) * 100.0
            } else {
                0.0
            }

            val diversificationBenefit = maxOf(0.0, standaloneVar - bookVarContribution)

            BookVaRContribution(
                bookId = bookId,
                varContribution = bookVarContribution,
                percentageOfTotal = percentageOfTotal,
                standaloneVar = standaloneVar,
                diversificationBenefit = diversificationBenefit,
            )
        }
    }
}

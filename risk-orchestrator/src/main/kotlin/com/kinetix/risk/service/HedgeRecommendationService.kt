package com.kinetix.risk.service

import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.InstrumentServiceClient
import com.kinetix.risk.client.LimitServiceClient
import com.kinetix.risk.client.PriceServiceClient
import com.kinetix.risk.client.ReferenceDataServiceClient
import com.kinetix.risk.client.VolatilityServiceClient
import com.kinetix.risk.client.dtos.LimitDefinitionDto
import com.kinetix.risk.model.CandidateInstrument
import com.kinetix.risk.model.GreekImpact
import com.kinetix.risk.model.HedgeConstraints
import com.kinetix.risk.model.HedgeRecommendation
import com.kinetix.risk.model.HedgeStatus
import com.kinetix.risk.model.HedgeSuggestion
import com.kinetix.risk.model.HedgeTarget
import com.kinetix.risk.model.ValuationResult
import com.kinetix.risk.persistence.HedgeRecommendationRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val LIQUID_TIERS = setOf("HIGH_LIQUID", "LIQUID")
private const val MAX_CANDIDATES = 50
private val STALENESS_WARNING_THRESHOLD = Duration.ofMinutes(30)

class HedgeRecommendationService(
    private val varCache: VaRCache,
    private val instrumentServiceClient: InstrumentServiceClient,
    private val priceServiceClient: PriceServiceClient,
    private val referenceDataClient: ReferenceDataServiceClient,
    private val limitServiceClient: LimitServiceClient? = null,
    private val volatilityServiceClient: VolatilityServiceClient? = null,
    private val calculator: AnalyticalHedgeCalculator,
    private val repository: HedgeRecommendationRepository,
    private val maxGreekStaleness: Duration = Duration.ofHours(2),
    private val recommendationTtl: Duration = Duration.ofMinutes(30),
) {
    private val logger = LoggerFactory.getLogger(HedgeRecommendationService::class.java)

    suspend fun suggestHedge(
        bookId: BookId,
        target: HedgeTarget,
        targetReductionPct: Double,
        constraints: HedgeConstraints,
    ): HedgeRecommendation {
        require(targetReductionPct > 0.0 && targetReductionPct <= 1.0) {
            "targetReductionPct must be between 0 (exclusive) and 1.0 (inclusive), got $targetReductionPct"
        }

        val cachedResult = varCache.get(bookId.value)
            ?: throw IllegalStateException("No VaR result available for book ${bookId.value}. Run a VaR calculation first.")

        val greekAge = Duration.between(cachedResult.calculatedAt, Instant.now())
        if (greekAge > maxGreekStaleness) {
            throw IllegalStateException(
                "Greeks for book ${bookId.value} are stale (age: ${greekAge.toMinutes()} minutes, " +
                    "max: ${maxGreekStaleness.toMinutes()} minutes). Rerun VaR calculation first."
            )
        }

        val staleWarning: String? = if (greekAge >= STALENESS_WARNING_THRESHOLD) {
            "Greeks are stale (age: ${greekAge.toMinutes()} minutes). " +
                "Suggestions may not reflect the latest market conditions. Rerun VaR for fresh data."
        } else {
            null
        }

        val currentGreeks = extractGreeks(cachedResult)
        val candidates = fetchCandidates(target, constraints)

        if (candidates.isEmpty()) {
            logger.warn("No liquid candidates found for book {} target {}", bookId.value, target)
            val now = Instant.now()
            val noCandidatesMessage = "No liquid instruments found matching your constraints. " +
                "Broaden the instrument universe or relax liquidity constraints and retry."
            val recommendation = HedgeRecommendation(
                id = UUID.randomUUID(),
                bookId = bookId.value,
                targetMetric = target,
                targetReductionPct = targetReductionPct,
                requestedAt = now,
                status = HedgeStatus.PENDING,
                message = if (staleWarning != null) "$noCandidatesMessage $staleWarning" else noCandidatesMessage,
                constraints = constraints,
                suggestions = emptyList(),
                preHedgeGreeks = currentGreeks,
                sourceJobId = cachedResult.jobId?.toString(),
                acceptedBy = null,
                acceptedAt = null,
                expiresAt = now.plus(recommendationTtl),
            )
            repository.save(recommendation)
            return recommendation
        }

        val rawSuggestions = calculator.suggest(
            currentGreeks = currentGreeks,
            target = target,
            targetReductionPct = targetReductionPct,
            candidates = candidates,
            constraints = constraints,
        )

        val suggestions = if (constraints.respectPositionLimits) {
            applyPositionLimitFilter(rawSuggestions)
        } else {
            rawSuggestions
        }

        val now = Instant.now()
        val recommendation = HedgeRecommendation(
            id = UUID.randomUUID(),
            bookId = bookId.value,
            targetMetric = target,
            targetReductionPct = targetReductionPct,
            requestedAt = now,
            status = HedgeStatus.PENDING,
            message = staleWarning,
            constraints = constraints,
            suggestions = suggestions,
            preHedgeGreeks = currentGreeks,
            sourceJobId = cachedResult.jobId?.toString(),
            acceptedBy = null,
            acceptedAt = null,
            expiresAt = now.plus(recommendationTtl),
        )

        repository.save(recommendation)

        logger.info(
            "Hedge recommendation created for book {} target {} with {} suggestions (job: {})",
            bookId.value, target, suggestions.size, cachedResult.jobId,
        )

        return recommendation
    }

    suspend fun acceptRecommendation(
        id: UUID,
        acceptedBy: String,
        suggestionIndices: List<Int>? = null,
    ): HedgeRecommendation {
        val recommendation = repository.findById(id)
            ?: throw NoSuchElementException("Hedge recommendation $id not found")

        if (recommendation.isExpired) {
            throw IllegalStateException("Recommendation has expired and cannot be accepted")
        }
        if (recommendation.status != HedgeStatus.PENDING) {
            throw IllegalStateException("Recommendation is not PENDING (current status: ${recommendation.status})")
        }

        val acceptedSuggestions = if (suggestionIndices != null) {
            val outOfBounds = suggestionIndices.filter { it < 0 || it >= recommendation.suggestions.size }
            if (outOfBounds.isNotEmpty()) {
                throw IllegalArgumentException(
                    "suggestion_indices out of bounds: $outOfBounds (recommendation has ${recommendation.suggestions.size} suggestions)"
                )
            }
            suggestionIndices.map { recommendation.suggestions[it] }
        } else {
            recommendation.suggestions
        }

        val now = Instant.now()
        repository.updateStatus(id, HedgeStatus.ACCEPTED, acceptedBy = acceptedBy, acceptedAt = now)

        logger.info("Hedge recommendation {} accepted by {} ({} suggestions)", id, acceptedBy, acceptedSuggestions.size)
        return recommendation.copy(
            status = HedgeStatus.ACCEPTED,
            acceptedBy = acceptedBy,
            acceptedAt = now,
            suggestions = acceptedSuggestions,
        )
    }

    suspend fun rejectRecommendation(id: UUID): HedgeRecommendation {
        val recommendation = repository.findById(id)
            ?: throw NoSuchElementException("Hedge recommendation $id not found")

        if (recommendation.status != HedgeStatus.PENDING) {
            throw IllegalStateException("Recommendation is not PENDING (current status: ${recommendation.status})")
        }

        repository.updateStatus(id, HedgeStatus.REJECTED)

        logger.info("Hedge recommendation {} rejected", id)
        return recommendation.copy(status = HedgeStatus.REJECTED)
    }

    suspend fun getLatestRecommendations(bookId: BookId, limit: Int = 10): List<HedgeRecommendation> =
        repository.findLatestByBookId(bookId.value, limit)

    suspend fun getRecommendation(id: UUID): HedgeRecommendation? =
        repository.findById(id)

    private fun extractGreeks(result: ValuationResult): GreekImpact {
        val greeks = result.greeks
        val totalDelta = greeks?.assetClassGreeks?.sumOf { it.delta } ?: 0.0
        val totalGamma = greeks?.assetClassGreeks?.sumOf { it.gamma } ?: 0.0
        val totalVega = greeks?.assetClassGreeks?.sumOf { it.vega } ?: 0.0
        val theta = greeks?.theta ?: 0.0
        val rho = greeks?.rho ?: 0.0
        return GreekImpact(
            deltaBefore = totalDelta,
            deltaAfter = totalDelta,
            gammaBefore = totalGamma,
            gammaAfter = totalGamma,
            vegaBefore = totalVega,
            vegaAfter = totalVega,
            thetaBefore = theta,
            thetaAfter = theta,
            rhoBefore = rho,
            rhoAfter = rho,
        )
    }

    private suspend fun fetchCandidates(
        target: HedgeTarget,
        constraints: HedgeConstraints,
    ): List<CandidateInstrument> {
        // Fetch liquidity data for all instruments with HIGH_LIQUID/LIQUID liquidity.
        // In Phase 1 we use the batch endpoint filtering by universe if specified.
        val liquidityByInstrument = try {
            referenceDataClient.getLiquidityDataBatch(
                if (constraints.instrumentUniverse != null)
                    listOf(constraints.instrumentUniverse)
                else
                    emptyList()
            )
        } catch (e: Exception) {
            logger.warn("Failed to fetch liquidity data for candidates: {}", e.message)
            emptyMap()
        }

        // Per hedge.allium:179-198 (eligible_instruments): filter for eligibility
        // and liquidity tier FIRST, rank by liquidity, THEN truncate to
        // MAX_CANDIDATES. Doing tier filtering before the cap ensures the
        // MAX_CANDIDATES slots go to the most-liquid eligible instruments —
        // illiquid candidates never consume a slot only to be dropped later.
        return liquidityByInstrument.values
            .filter { liq -> liq.hedgingEligible != false } // null treated as eligible (backward compatible)
            .filter { liq -> classifyTier(liq.adv, liq.bidAskSpreadBps) in LIQUID_TIERS }
            .sortedByDescending { liq -> liq.adv } // rank by liquidity (ADV) before truncation
            .take(MAX_CANDIDATES)
            .mapNotNull { liq ->
                buildCandidate(liq, target)
            }
    }

    private suspend fun buildCandidate(
        liq: com.kinetix.risk.client.dtos.InstrumentLiquidityDto,
        target: HedgeTarget,
    ): CandidateInstrument? {
        return try {
            val instrumentResp = instrumentServiceClient.getInstrument(InstrumentId(liq.instrumentId))
            val instrument = when (instrumentResp) {
                is ClientResponse.Success -> instrumentResp.value
                else -> return null
            }

            val tier = classifyTier(liq.adv, liq.bidAskSpreadBps)
            if (tier !in LIQUID_TIERS) return null

            val priceResp = priceServiceClient.getLatestPrice(InstrumentId(liq.instrumentId))
            val pricePoint = when (priceResp) {
                is ClientResponse.Success -> priceResp.value
                else -> {
                    logger.debug("No price found for candidate {}, excluding", liq.instrumentId)
                    return null
                }
            }
            val pricePerUnit = pricePoint.price.amount.toDouble()
            if (pricePerUnit <= 0.0) {
                logger.debug("Zero or negative price for candidate {}, excluding", liq.instrumentId)
                return null
            }

            // Price age in minutes — derived from the actual quote timestamp, not the
            // ADV-staleness flag. Drives the FRESH/STALE data_quality signal (>= 15 min = STALE).
            val priceAgeMinutes = Duration.between(pricePoint.timestamp, Instant.now())
                .toMinutes()
                .coerceAtLeast(0L)
                .toInt()

            // For VEGA hedges, require vol surface data to be available.
            // Fail-open: if the vol client is unavailable or throws, include the candidate.
            if (target == HedgeTarget.VEGA && volatilityServiceClient != null) {
                val volAvailable = try {
                    volatilityServiceClient.getLatestSurface(InstrumentId(liq.instrumentId)) is ClientResponse.Success
                } catch (e: Exception) {
                    logger.warn("Vol surface check failed for {}, including candidate: {}", liq.instrumentId, e.message)
                    true // fail-open
                }
                if (!volAvailable) {
                    logger.debug("No vol surface available for vega candidate {}, excluding", liq.instrumentId)
                    return null
                }
            }

            CandidateInstrument(
                instrumentId = liq.instrumentId,
                instrumentType = instrument.instrumentType,
                pricePerUnit = pricePerUnit,
                bidAskSpreadBps = liq.bidAskSpreadBps,
                deltaPerUnit = greekPerUnitForInstrumentType(instrument.instrumentType, target, "delta"),
                gammaPerUnit = greekPerUnitForInstrumentType(instrument.instrumentType, target, "gamma"),
                vegaPerUnit = greekPerUnitForInstrumentType(instrument.instrumentType, target, "vega"),
                thetaPerUnit = greekPerUnitForInstrumentType(instrument.instrumentType, target, "theta"),
                rhoPerUnit = greekPerUnitForInstrumentType(instrument.instrumentType, target, "rho"),
                liquidityTier = tier,
                priceAgeMinutes = priceAgeMinutes,
            )
        } catch (e: Exception) {
            logger.warn("Failed to build candidate for {}: {}", liq.instrumentId, e.message)
            null
        }
    }

    private fun classifyTier(advNotional: Double, bidAskSpreadBps: Double): String = when {
        advNotional >= 50_000_000 && bidAskSpreadBps <= 5.0 -> "HIGH_LIQUID"
        advNotional >= 10_000_000 && bidAskSpreadBps <= 20.0 -> "LIQUID"
        advNotional >= 1_000_000 -> "SEMI_LIQUID"
        else -> "ILLIQUID"
    }

    /**
     * When [respectPositionLimits] is true, fetches active NOTIONAL limits from position-service
     * and filters out any suggestion whose notional would exceed the tightest applicable limit.
     *
     * Behaviour on failure:
     * - If [limitServiceClient] is null or the call throws, every suggestion is preserved but
     *   annotated with a warning so the caller knows limit data was unavailable.
     * - If no NOTIONAL limits are defined, all suggestions pass through without warnings.
     */
    private suspend fun applyPositionLimitFilter(suggestions: List<HedgeSuggestion>): List<HedgeSuggestion> {
        val limits = fetchNotionalLimits() ?: return suggestions.map { it.withLimitUnavailableWarning() }
        if (limits.isEmpty()) return suggestions

        val tightestLimit = limits.mapNotNull { BigDecimal(it.limitValue).takeIf { v -> v > BigDecimal.ZERO } }.minOrNull()
            ?: return suggestions

        return suggestions.filter { suggestion ->
            val notional = BigDecimal.valueOf(suggestion.estimatedCost - suggestion.crossingCost)
            notional <= tightestLimit
        }
    }

    /** Returns active NOTIONAL limit definitions, or null if unavailable. */
    private suspend fun fetchNotionalLimits(): List<LimitDefinitionDto>? {
        if (limitServiceClient == null) return null
        return try {
            when (val response = limitServiceClient.getLimits()) {
                is ClientResponse.Success -> response.value.filter { it.active && it.limitType == "NOTIONAL" }
                else -> emptyList()
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch position limits for hedge filter: {}", e.message)
            null
        }
    }

    private fun HedgeSuggestion.withLimitUnavailableWarning() =
        copy(warnings = warnings + "Position limit data unavailable — limit check skipped")

    /** Approximate per-unit Greek sensitivity based on instrument type. */
    private fun greekPerUnitForInstrumentType(
        instrumentType: String,
        target: HedgeTarget,
        greek: String,
    ): Double = when (instrumentType.uppercase()) {
        "STOCK", "ETF" -> when (greek) {
            "delta" -> -1.0 // buying/selling stock has delta = ±1 per share
            else -> 0.0
        }
        "OPTION" -> when (greek) {
            "delta" -> -0.5 // approximate ATM delta
            "gamma" -> 0.02
            "vega" -> 0.1
            "theta" -> -0.01
            "rho" -> 0.05
            else -> 0.0
        }
        "FUTURE" -> when (greek) {
            "delta" -> -1.0
            else -> 0.0
        }
        else -> when (greek) {
            "delta" -> -1.0
            else -> 0.0
        }
    }
}

package com.kinetix.risk.service

import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.CorrelationServiceClient
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RatesServiceClient
import com.kinetix.risk.client.VolatilityServiceClient
import com.kinetix.risk.model.DailyRiskSnapshot
import com.kinetix.risk.model.PnlAttribution
import com.kinetix.risk.model.SodGreekSnapshot
import com.kinetix.risk.persistence.DailyRiskSnapshotRepository
import com.kinetix.risk.persistence.PnlAttributionRepository
import com.kinetix.risk.persistence.SodGreekSnapshotRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

class PnlComputationService(
    private val sodSnapshotService: SodSnapshotService,
    private val dailyRiskSnapshotRepository: DailyRiskSnapshotRepository,
    private val pnlAttributionService: PnlAttributionService,
    private val pnlAttributionRepository: PnlAttributionRepository,
    private val varCache: VaRCache,
    private val positionProvider: PositionProvider,
    private val volatilityServiceClient: VolatilityServiceClient? = null,
    private val ratesServiceClient: RatesServiceClient? = null,
    /** Repository for immutable pricing Greeks locked at SOD. Null when not wired (degrades to PRICE_ONLY). */
    private val sodGreekSnapshotRepository: SodGreekSnapshotRepository? = null,
    private val correlationServiceClient: CorrelationServiceClient? = null,
) {
    private val logger = LoggerFactory.getLogger(PnlComputationService::class.java)

    suspend fun compute(
        bookId: BookId,
        date: LocalDate = LocalDate.now(),
    ): PnlAttribution {
        val status = sodSnapshotService.getBaselineStatus(bookId, date)
        if (!status.exists) {
            throw NoSodBaselineException(bookId.value)
        }

        val sodSnapshots = dailyRiskSnapshotRepository.findByBookIdAndDate(bookId, date)
        val currentPositions = positionProvider.getPositions(bookId)

        // Prefetch pricing Greeks (second-order cross-Greeks live here)
        val greeksByInstrument = fetchPricingGreeks(bookId, date)

        // Prefetch current market data for all instruments in one pass.
        val instrumentIds = sodSnapshots.map { it.instrumentId }.distinct()
        val currencies = currentPositions.map { it.currency }.distinct()
        val currentVolMap = fetchCurrentVols(instrumentIds)
        val currentRateMap = fetchCurrentRates(currencies)

        val inputs = sodSnapshots.map { snapshot ->
            val currentPosition = currentPositions.find { it.instrumentId == snapshot.instrumentId }
            val currentPrice = currentPosition?.marketPrice?.amount ?: snapshot.marketPrice
            val priceChange = currentPrice.subtract(snapshot.marketPrice)
            val totalPnl = priceChange.multiply(snapshot.quantity)
            val currency = currentPosition?.currency ?: Currency.getInstance("USD")

            val volChange = computeVolChange(snapshot, currentVolMap[snapshot.instrumentId])
            val rateChange = computeRateChange(snapshot, currentRateMap[currency])

            // Prefer pricing Greeks from SodGreekSnapshot over VaR Greeks from DailyRiskSnapshot.
            // VaR Greeks are bump-and-reprice aggregations; pricing Greeks are the closed-form
            // BS partials required for a correct Taylor expansion P&L attribution.
            val pricingGreek = greeksByInstrument[snapshot.instrumentId]

            PositionPnlInput(
                instrumentId = snapshot.instrumentId,
                assetClass = snapshot.assetClass,
                totalPnl = totalPnl,
                delta = greekOrFallback(pricingGreek?.delta, snapshot.delta),
                // kx-gla6: gamma drives the second-order Taylor term 0.5 * gamma * priceChange^2,
                // where priceChange is an ABSOLUTE dollar move. The DailyRiskSnapshot.gamma is a
                // VaR-bump sensitivity (per fractional bump, ~ -1e9), not a per-unit pricing gamma —
                // substituting it produces billions-scale artefacts mirrored by unexplainedPnl.
                // Only a true closed-form pricing gamma is valid here; otherwise fall back to 0 so
                // unexplained absorbs convexity honestly rather than fabricating a gamma term.
                gamma = pricingGammaOrZero(pricingGreek?.gamma),
                vega = greekOrFallback(pricingGreek?.vega, snapshot.vega),
                theta = greekOrFallback(pricingGreek?.theta, snapshot.theta),
                rho = greekOrFallback(pricingGreek?.rho, snapshot.rho),
                vanna = pricingGreek?.vanna?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO,
                volga = pricingGreek?.volga?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO,
                charm = pricingGreek?.charm?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO,
                priceChange = priceChange,
                volChange = volChange,
                rateChange = rateChange,
            )
        }

        val correlations = fetchCorrelations(inputs.map { it.instrumentId.value }.distinct())
        val baseCurrency = currentPositions
            .groupingBy { it.currency.currencyCode }
            .eachCount()
            .maxByOrNull { it.value }?.key ?: "USD"

        val attribution = pnlAttributionService.attribute(bookId, inputs, date, correlations, baseCurrency)
        pnlAttributionRepository.save(attribution)

        logger.info(
            "P&L attribution computed for portfolio {} on {}: totalPnl={}, quality={}",
            bookId.value, date, attribution.totalPnl, attribution.dataQualityFlag,
        )

        return attribution
    }

    /**
     * Fetches per-instrument pricing Greeks from the [SodGreekSnapshotRepository].
     * Returns an empty map when the repository is not wired or returns no data —
     * attribution will fall back to VaR Greeks with PRICE_ONLY quality flag.
     */
    private suspend fun fetchPricingGreeks(bookId: BookId, date: LocalDate): Map<InstrumentId, SodGreekSnapshot> {
        val repo = sodGreekSnapshotRepository ?: return emptyMap()
        return repo.findByBookIdAndDate(bookId, date).associateBy { it.instrumentId }
    }

    /** Returns pricing Greek value when available; falls back to VaR Greek value; returns zero if neither exists. */
    private fun greekOrFallback(pricingGreek: Double?, varGreek: Double?): BigDecimal =
        BigDecimal.valueOf(pricingGreek ?: varGreek ?: 0.0)

    /**
     * Returns the closed-form pricing gamma when available, otherwise zero.
     *
     * Unlike [greekOrFallback], this never falls back to the VaR-bump [DailyRiskSnapshot.gamma]:
     * that value is a VaR sensitivity per fractional bump (~ -1e9), and the Taylor gamma term
     * multiplies it by an absolute dollar priceChange^2, yielding a billions-scale artefact (kx-gla6).
     * A per-unit pricing gamma is the only valid input here; when absent, gamma_pnl must be 0 so the
     * unexplained residual honestly absorbs the true convexity rather than fabricating a gamma term.
     */
    private fun pricingGammaOrZero(pricingGamma: Double?): BigDecimal =
        BigDecimal.valueOf(pricingGamma ?: 0.0)

    private suspend fun fetchCurrentVols(instrumentIds: List<InstrumentId>): Map<InstrumentId, Double> {
        val client = volatilityServiceClient ?: return emptyMap()
        return instrumentIds.mapNotNull { id ->
            when (val response = client.getLatestSurface(id)) {
                is ClientResponse.Success -> {
                    val atm = response.value.points.firstOrNull()?.impliedVol?.toDouble()
                    if (atm != null) id to atm else null
                }
                else -> {
                    logger.debug("No current vol surface for {} — volChange will be zero", id.value)
                    null
                }
            }
        }.toMap()
    }

    private suspend fun fetchCurrentRates(currencies: List<Currency>): Map<Currency, Double> {
        val client = ratesServiceClient ?: return emptyMap()
        return currencies.mapNotNull { currency ->
            when (val response = client.getLatestRiskFreeRate(currency, "1Y")) {
                is ClientResponse.Success -> currency to response.value.rate
                else -> {
                    logger.debug("No current risk-free rate for {} 1Y — rateChange will be zero", currency.currencyCode)
                    null
                }
            }
        }.toMap()
    }

    private fun computeVolChange(snapshot: DailyRiskSnapshot, currentVol: Double?): BigDecimal {
        val sodVol = snapshot.sodVol ?: return BigDecimal.ZERO
        val current = currentVol ?: return BigDecimal.ZERO
        return BigDecimal.valueOf(current - sodVol)
    }

    private fun computeRateChange(snapshot: DailyRiskSnapshot, currentRate: Double?): BigDecimal {
        val sodRate = snapshot.sodRate ?: return BigDecimal.ZERO
        val current = currentRate ?: return BigDecimal.ZERO
        return BigDecimal.valueOf(current - sodRate)
    }

    /**
     * Fetches the correlation matrix for the given instruments and converts it
     * to a pairwise lookup map. Returns empty map if the correlation service
     * is not available or the fetch fails (graceful degradation to zero cross-gamma).
     */
    private suspend fun fetchCorrelations(instrumentIds: List<String>): Map<Pair<String, String>, Double> {
        val client = correlationServiceClient ?: return emptyMap()
        if (instrumentIds.size < 2) return emptyMap()

        return try {
            when (val response = client.getCorrelationMatrix(instrumentIds)) {
                is ClientResponse.Success -> {
                    val matrix = response.value
                    val result = mutableMapOf<Pair<String, String>, Double>()
                    for (i in matrix.labels.indices) {
                        for (j in matrix.labels.indices) {
                            if (i != j) {
                                val corr = matrix.values[i * matrix.labels.size + j]
                                result[matrix.labels[i] to matrix.labels[j]] = corr
                            }
                        }
                    }
                    result
                }
                else -> emptyMap()
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch correlation matrix for cross-gamma, defaulting to zero: {}", e.message)
            emptyMap()
        }
    }
}

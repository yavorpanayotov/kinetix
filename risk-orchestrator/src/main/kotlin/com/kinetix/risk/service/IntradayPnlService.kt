package com.kinetix.risk.service

import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RatesServiceClient
import com.kinetix.risk.client.VolatilityServiceClient
import com.kinetix.risk.model.DailyRiskSnapshot
import com.kinetix.risk.model.InstrumentPnlBreakdown
import com.kinetix.risk.model.IntradayPnlSnapshot
import com.kinetix.risk.model.PnlTrigger
import com.kinetix.risk.model.SodGreekSnapshot
import com.kinetix.risk.persistence.DailyRiskSnapshotRepository
import com.kinetix.risk.persistence.IntradayPnlRepository
import com.kinetix.risk.persistence.SodBaselineRepository
import com.kinetix.risk.persistence.SodGreekSnapshotRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

class IntradayPnlService(
    private val sodBaselineRepository: SodBaselineRepository,
    private val dailyRiskSnapshotRepository: DailyRiskSnapshotRepository,
    private val intradayPnlRepository: IntradayPnlRepository,
    private val positionProvider: PositionProvider,
    private val pnlAttributionService: PnlAttributionService,
    private val publisher: IntradayPnlPublisher,
    private val debounceInterval: Duration = Duration.ofSeconds(1),
    private val volatilityServiceClient: VolatilityServiceClient? = null,
    private val ratesServiceClient: RatesServiceClient? = null,
    private val fxRateProvider: FxRateProvider? = null,
    /**
     * Repository for immutable pricing Greeks locked at SOD. When wired and populated,
     * attribution uses analytical BS partials (the correct quantity for Taylor expansion).
     * When null or empty, falls back to VaR sensitivities from [DailyRiskSnapshot] —
     * the same fallback pattern as [PnlComputationService]. Spec [intraday-pnl.allium:225-231]
     * mandates pricing Greeks; fallback exists only because the SOD population path is not yet
     * built (see audit item A-3 Phase 2).
     */
    private val sodGreekSnapshotRepository: SodGreekSnapshotRepository? = null,
) {
    private val logger = LoggerFactory.getLogger(IntradayPnlService::class.java)
    private val mc = MathContext(20, RoundingMode.HALF_UP)

    /**
     * Recomputes intraday P&L for [bookId] triggered by a position change.
     *
     * Returns null (without publishing) if:
     * - no SOD baseline exists for the book today
     * - the debounce interval has not elapsed since the last snapshot
     *
     * Otherwise computes total P&L from position state (the truth),
     * attributes it against frozen SOD Greeks, updates the high-water mark,
     * persists the snapshot, and publishes it to Kafka.
     */
    suspend fun recompute(
        bookId: BookId,
        trigger: PnlTrigger,
        correlationId: String?,
        date: LocalDate = LocalDate.now(),
    ): IntradayPnlSnapshot? {
        val baseline = sodBaselineRepository.findByBookIdAndDate(bookId, date)
        if (baseline == null) {
            logger.warn(
                "No SOD baseline for book {} on {} — intraday P&L is undefined",
                bookId.value, date,
            )
            return null
        }

        val lastSnapshot = intradayPnlRepository.findLatest(bookId)
        if (lastSnapshot != null) {
            val elapsed = Duration.between(lastSnapshot.snapshotAt, Instant.now())
            if (elapsed < debounceInterval) {
                return null
            }
        }

        val sodSnapshots = dailyRiskSnapshotRepository.findByBookIdAndDate(bookId, date)
        val pricingGreeks = fetchPricingGreeks(bookId, date)
        val positions = positionProvider.getPositions(bookId)

        // Total P&L is the truth: computed from position state, never from Greek attribution.
        val baseCurrency = deriveBaseCurrency(positions)

        // Pre-fetch all FX rates needed so convertToBase stays a pure function.
        val (fxRates, missingFxRates) = prefetchFxRates(positions, baseCurrency)

        val totalRealised = positions.fold(BigDecimal.ZERO) { acc, pos ->
            acc.add(convertToBase(pos.realizedPnl, baseCurrency, fxRates), mc)
        }
        val totalUnrealised = positions.fold(BigDecimal.ZERO) { acc, pos ->
            acc.add(convertToBase(pos.unrealizedPnl, baseCurrency, fxRates), mc)
        }
        val totalPnl = totalRealised.add(totalUnrealised, mc)

        // Fetch current market data for vol/rate change computation.
        val instrumentIds = sodSnapshots.map { it.instrumentId }.distinct()
        val currencies = positions.map { it.currency }.distinct()
        val currentVolMap = fetchCurrentVols(instrumentIds)
        val currentRateMap = fetchCurrentRates(currencies)

        // Greek attribution: analytical overlay against frozen SOD state.
        val pnlInputs = buildAttributionInputs(
            positions, sodSnapshots, pricingGreeks, baseCurrency, fxRates, currentVolMap, currentRateMap,
        )
        val attribution = pnlAttributionService.attribute(bookId, pnlInputs, date, currency = baseCurrency)

        // High-water mark is monotonically non-decreasing within the trading day.
        val previousHwm = lastSnapshot?.highWaterMark ?: totalPnl
        val newHwm = previousHwm.max(totalPnl)

        val instrumentPnl = attribution.positionAttributions.map { pos ->
            InstrumentPnlBreakdown(
                instrumentId = pos.instrumentId.value,
                assetClass = pos.assetClass.name,
                totalPnl = pos.totalPnl.toPlainString(),
                deltaPnl = pos.deltaPnl.toPlainString(),
                gammaPnl = pos.gammaPnl.toPlainString(),
                vegaPnl = pos.vegaPnl.toPlainString(),
                thetaPnl = pos.thetaPnl.toPlainString(),
                rhoPnl = pos.rhoPnl.toPlainString(),
                vannaPnl = pos.vannaPnl.toPlainString(),
                volgaPnl = pos.volgaPnl.toPlainString(),
                charmPnl = pos.charmPnl.toPlainString(),
                crossGammaPnl = pos.crossGammaPnl.toPlainString(),
                unexplainedPnl = pos.unexplainedPnl.toPlainString(),
            )
        }

        val unexplainedPnl = totalPnl - (attribution.deltaPnl + attribution.gammaPnl +
            attribution.vegaPnl + attribution.thetaPnl + attribution.rhoPnl +
            attribution.vannaPnl + attribution.volgaPnl + attribution.charmPnl + attribution.crossGammaPnl)

        val dataQualityWarning = if (totalPnl.signum() != 0) {
            val unexplainedRatio = unexplainedPnl.abs().toDouble() / totalPnl.abs().toDouble()
            if (unexplainedRatio > 0.20) {
                "High unexplained P&L: ${String.format("%.1f", unexplainedRatio * 100)}% of total " +
                    "cannot be attributed to known Greeks — investigate model gaps or missing risk factors"
            } else null
        } else null

        val snapshot = IntradayPnlSnapshot(
            bookId = bookId,
            snapshotAt = Instant.now(),
            baseCurrency = baseCurrency,
            trigger = trigger,
            totalPnl = totalPnl,
            realisedPnl = totalRealised,
            unrealisedPnl = totalUnrealised,
            deltaPnl = attribution.deltaPnl,
            gammaPnl = attribution.gammaPnl,
            vegaPnl = attribution.vegaPnl,
            thetaPnl = attribution.thetaPnl,
            rhoPnl = attribution.rhoPnl,
            vannaPnl = attribution.vannaPnl,
            volgaPnl = attribution.volgaPnl,
            charmPnl = attribution.charmPnl,
            crossGammaPnl = attribution.crossGammaPnl,
            unexplainedPnl = unexplainedPnl,
            highWaterMark = newHwm,
            instrumentPnl = instrumentPnl,
            correlationId = correlationId,
            missingFxRates = missingFxRates,
            dataQualityWarning = dataQualityWarning,
        )

        intradayPnlRepository.save(snapshot)
        publisher.publish(snapshot)

        return snapshot
    }

    private fun buildAttributionInputs(
        positions: List<com.kinetix.common.model.Position>,
        sodSnapshots: List<DailyRiskSnapshot>,
        pricingGreeks: Map<InstrumentId, SodGreekSnapshot>,
        baseCurrency: String,
        fxRates: Map<String, BigDecimal>,
        currentVolMap: Map<InstrumentId, Double>,
        currentRateMap: Map<Currency, Double>,
    ): List<PositionPnlInput> {
        val sodByInstrument = sodSnapshots.associateBy { it.instrumentId }
        return positions.mapNotNull { position ->
            val sod = sodByInstrument[position.instrumentId] ?: return@mapNotNull null
            val currentPrice = position.marketPrice.amount
            val priceChange = currentPrice.subtract(sod.marketPrice, mc)
            val positionPnl = convertToBase(position.unrealizedPnl, baseCurrency, fxRates)
                .add(convertToBase(position.realizedPnl, baseCurrency, fxRates), mc)

            val volChange = computeVolChange(sod, currentVolMap[position.instrumentId])
            val rateChange = computeRateChange(sod, currentRateMap[position.currency])

            // Prefer pricing Greeks (analytical BS partials) over VaR Greeks (bump-and-reprice
            // aggregations). VaR Greeks are mathematically the wrong quantity for a Taylor-
            // expansion P&L attribution and produce systematically inflated unexplained_pnl.
            // See PnlComputationService.greekOrFallback for the same pattern in EOD attribution.
            val pricingGreek = pricingGreeks[position.instrumentId]

            PositionPnlInput(
                instrumentId = position.instrumentId,
                assetClass = position.assetClass,
                totalPnl = positionPnl,
                delta = greekOrFallback(pricingGreek?.delta, sod.delta),
                gamma = greekOrFallback(pricingGreek?.gamma, sod.gamma),
                vega = greekOrFallback(pricingGreek?.vega, sod.vega),
                theta = greekOrFallback(pricingGreek?.theta, sod.theta),
                rho = greekOrFallback(pricingGreek?.rho, sod.rho),
                vanna = pricingGreek?.vanna?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO,
                volga = pricingGreek?.volga?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO,
                charm = pricingGreek?.charm?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO,
                priceChange = priceChange,
                volChange = volChange,
                rateChange = rateChange,
            )
        }
    }

    /**
     * Fetches per-instrument pricing Greeks from the [SodGreekSnapshotRepository].
     * Returns an empty map when the repository is not wired or the SOD job has not yet
     * populated rows for the day — attribution then falls back to VaR Greeks per-instrument.
     */
    private suspend fun fetchPricingGreeks(
        bookId: BookId,
        date: LocalDate,
    ): Map<InstrumentId, SodGreekSnapshot> {
        val repo = sodGreekSnapshotRepository ?: return emptyMap()
        return repo.findByBookIdAndDate(bookId, date).associateBy { it.instrumentId }
    }

    /** Returns pricing Greek value when available; falls back to VaR Greek value; zero if neither. */
    private fun greekOrFallback(pricingGreek: Double?, varGreek: Double?): BigDecimal =
        BigDecimal.valueOf(pricingGreek ?: varGreek ?: 0.0)

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
     * Pre-fetches all FX rates needed to convert position P&L to [baseCurrency].
     *
     * Currencies equal to [baseCurrency] are skipped (rate is 1:1 by definition).
     * Returns a pair of:
     * - fxRates: Map from currency code to the rate against [baseCurrency]
     * - missingCurrencies: List of currency codes for which no rate was available
     */
    private suspend fun prefetchFxRates(
        positions: List<com.kinetix.common.model.Position>,
        baseCurrency: String,
    ): Pair<Map<String, BigDecimal>, List<String>> {
        val provider = fxRateProvider ?: return emptyMap<String, BigDecimal>() to emptyList()

        val foreignCurrencies = positions
            .map { it.currency.currencyCode }
            .filter { it != baseCurrency }
            .distinct()

        if (foreignCurrencies.isEmpty()) return emptyMap<String, BigDecimal>() to emptyList()

        val fxRates = mutableMapOf<String, BigDecimal>()
        val missingCurrencies = mutableListOf<String>()

        for (currency in foreignCurrencies) {
            val rate = provider.getRate(currency, baseCurrency)
            if (rate != null) {
                fxRates[currency] = rate
            } else {
                logger.warn(
                    "No FX rate available for {}/{} — P&L for positions in {} will use 1:1 fallback",
                    currency, baseCurrency, currency,
                )
                missingCurrencies.add(currency)
            }
        }

        return fxRates to missingCurrencies
    }

    /**
     * Converts [money] to [baseCurrency] using the pre-fetched [fxRates] map.
     *
     * Same currency returns the amount directly. Missing rate uses 1:1 (the caller
     * is responsible for tracking missing currencies via [prefetchFxRates]).
     */
    private fun convertToBase(
        money: Money,
        baseCurrency: String,
        fxRates: Map<String, BigDecimal>,
    ): BigDecimal {
        val currencyCode = money.currency.currencyCode
        if (currencyCode == baseCurrency) return money.amount
        val rate = fxRates[currencyCode] ?: return money.amount
        return money.amount.multiply(rate, mc)
    }

    private fun deriveBaseCurrency(
        @Suppress("UNUSED_PARAMETER") positions: List<com.kinetix.common.model.Position>,
    ): String = "USD"
}

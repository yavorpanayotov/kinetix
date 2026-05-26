package com.kinetix.referencedata.seed

import com.kinetix.common.demo.SeedProfile
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BondSeniority
import com.kinetix.common.model.CreditSpread
import com.kinetix.common.model.Desk
import com.kinetix.common.model.DeskId
import com.kinetix.common.model.Division
import com.kinetix.common.model.DivisionId
import com.kinetix.common.model.DividendYield
import com.kinetix.common.model.ExerciseStyle
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.OptionType
import com.kinetix.common.model.ReferenceDataSource
import com.kinetix.common.model.SwapDirection
import com.kinetix.common.model.Trader
import com.kinetix.common.model.TraderId
import com.kinetix.common.model.instrument.*
import com.kinetix.referencedata.model.Counterparty
import com.kinetix.referencedata.model.Instrument
import com.kinetix.referencedata.model.InstrumentLiquidity
import com.kinetix.referencedata.model.NettingAgreement
import com.kinetix.referencedata.service.InstrumentLiquidityService
import com.kinetix.referencedata.persistence.CounterpartyRepository
import com.kinetix.referencedata.persistence.CreditSpreadRepository
import com.kinetix.referencedata.persistence.DeskRepository
import com.kinetix.referencedata.persistence.DivisionRepository
import com.kinetix.referencedata.persistence.DividendYieldRepository
import com.kinetix.referencedata.persistence.InstrumentLiquidityRepository
import com.kinetix.referencedata.persistence.InstrumentRepository
import com.kinetix.referencedata.persistence.NettingAgreementRepository
import com.kinetix.referencedata.persistence.TraderRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant

class DevDataSeeder(
    private val dividendYieldRepository: DividendYieldRepository,
    private val creditSpreadRepository: CreditSpreadRepository,
    private val instrumentRepository: InstrumentRepository? = null,
    private val divisionRepository: DivisionRepository? = null,
    private val deskRepository: DeskRepository? = null,
    private val liquidityRepository: InstrumentLiquidityRepository? = null,
    private val counterpartyRepository: CounterpartyRepository? = null,
    private val nettingAgreementRepository: NettingAgreementRepository? = null,
    private val traderRepository: TraderRepository? = null,
) {
    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

    suspend fun seed(profile: SeedProfile) {
        require(profile.implemented) { "Scenario '${profile.id}' is not implemented" }
        log.info("Seeding reference-data with profile={}", profile.id)
        // Phase 2 Gaps 2.4–2.6 will branch on profile (e.g. expanded option chain
        // for options-book, additional desks/limits for stress). All implemented
        // profiles share the existing reference universe today.
        seed()
    }

    suspend fun seed() {
        val existing = dividendYieldRepository.findLatest(InstrumentId("AAPL"))
        if (existing != null) {
            log.info("Reference data already present, skipping seed")
            return
        }

        log.info("Seeding reference data")

        seedDividendYields()
        seedCreditSpreads()
        seedInstruments()
        seedDivisions()
        seedDesks()
        seedTraders()
        seedLiquidityData()
        seedCounterparties()
        seedNettingAgreements()

        log.info("Reference data seeding complete")
    }

    private suspend fun seedDividendYields() {
        for ((instrumentId, yieldPercent) in DIVIDEND_YIELDS) {
            val dividendYield = DividendYield(
                instrumentId = InstrumentId(instrumentId),
                yield = yieldPercent,
                exDate = null,
                asOfDate = AS_OF,
                source = ReferenceDataSource.BLOOMBERG,
            )
            dividendYieldRepository.save(dividendYield)
        }
        log.info("Seeded {} dividend yields", DIVIDEND_YIELDS.size)
    }

    private suspend fun seedCreditSpreads() {
        for ((instrumentId, config) in CREDIT_SPREADS) {
            val creditSpread = CreditSpread(
                instrumentId = InstrumentId(instrumentId),
                spread = config.spread,
                rating = config.rating,
                asOfDate = AS_OF,
                source = ReferenceDataSource.RATING_AGENCY,
            )
            creditSpreadRepository.save(creditSpread)
        }
        log.info("Seeded {} credit spreads", CREDIT_SPREADS.size)
    }

    private suspend fun seedInstruments() {
        val repo = instrumentRepository ?: return
        for ((id, config) in INSTRUMENTS) {
            val instrument = Instrument(
                instrumentId = InstrumentId(id),
                instrumentType = config.type,
                displayName = config.displayName,
                currency = config.currency,
                createdAt = AS_OF,
                updatedAt = AS_OF,
            )
            repo.save(instrument)
        }
        log.info("Seeded {} instruments", INSTRUMENTS.size)
    }

    private suspend fun seedDivisions() {
        val repo = divisionRepository ?: return
        for ((id, config) in DIVISIONS) {
            repo.save(Division(id = DivisionId(id), name = config.name, description = config.description))
        }
        log.info("Seeded {} divisions", DIVISIONS.size)
    }

    private suspend fun seedDesks() {
        val repo = deskRepository ?: return
        for ((id, config) in DESKS) {
            repo.save(Desk(id = DeskId(id), name = config.name, divisionId = DivisionId(config.divisionId), deskHead = config.deskHead))
        }
        log.info("Seeded {} desks", DESKS.size)
    }

    private suspend fun seedTraders() {
        val repo = traderRepository ?: return
        var count = 0
        for ((deskId, traders) in TRADERS) {
            for (config in traders) {
                repo.save(
                    Trader(
                        id = TraderId(config.id),
                        name = config.name,
                        deskId = DeskId(deskId),
                        email = config.email,
                        notionalLimitUsd = config.notionalLimitUsd,
                    )
                )
                count++
            }
        }
        log.info("Seeded {} traders across {} desks", count, TRADERS.size)
    }

    private suspend fun seedLiquidityData() {
        val repo = liquidityRepository ?: return
        for ((instrumentId, config) in LIQUIDITY_DATA) {
            repo.upsert(
                InstrumentLiquidity(
                    instrumentId = instrumentId,
                    adv = config.adv,
                    bidAskSpreadBps = config.bidAskSpreadBps,
                    assetClass = config.assetClass,
                    liquidityTier = InstrumentLiquidityService.classifyTier(config.adv, config.bidAskSpreadBps),
                    advUpdatedAt = AS_OF,
                    createdAt = AS_OF,
                    updatedAt = AS_OF,
                    advShares = config.advShares,
                    marketDepthScore = config.marketDepthScore,
                    source = config.source,
                    hedgingEligible = config.hedgingEligible,
                )
            )
        }
        log.info("Seeded {} instrument liquidity records", LIQUIDITY_DATA.size)
    }

    private suspend fun seedCounterparties() {
        val repo = counterpartyRepository ?: return
        for (cp in COUNTERPARTIES) {
            repo.upsert(cp)
        }
        log.info("Seeded {} counterparties", COUNTERPARTIES.size)
    }

    private suspend fun seedNettingAgreements() {
        val repo = nettingAgreementRepository ?: return
        for (na in NETTING_AGREEMENTS) {
            repo.upsert(na)
        }
        log.info("Seeded {} netting agreements", NETTING_AGREEMENTS.size)
    }

    private data class InstrumentConfig(
        val type: InstrumentType,
        val displayName: String,
        val currency: String,
    )

    private data class CreditSpreadConfig(
        val spread: Double,
        val rating: String,
    )

    private data class DivisionConfig(
        val name: String,
        val description: String? = null,
    )

    private data class DeskConfig(
        val name: String,
        val divisionId: String,
        val deskHead: String? = null,
    )

    private data class TraderConfig(
        val id: String,
        val name: String,
        val email: String,
        val notionalLimitUsd: BigDecimal,
    )

    private data class LiquidityConfig(
        val adv: Double,
        val bidAskSpreadBps: Double,
        val assetClass: AssetClass,
        val advShares: Double? = null,
        val marketDepthScore: Double? = null,
        val source: String = "bloomberg",
        val hedgingEligible: Boolean? = true,
    )

    companion object {
        val AS_OF: Instant = Instant.parse("2026-02-22T10:00:00Z")

        val INSTRUMENT_IDS: Set<String> get() = INSTRUMENTS.keys
        val DESK_IDS: Set<String> get() = DESKS.keys

        val DIVIDEND_YIELDS: Map<String, Double> = mapOf(
            "AAPL" to 0.0055,
            "MSFT" to 0.0075,
            "GOOGL" to 0.0,
            "AMZN" to 0.0,
            "JPM" to 0.024,
            "NVDA" to 0.0003,
            "META" to 0.0035,
            "TSLA" to 0.0,
            "BABA" to 0.0,
            "AMD" to 0.0,
            "INTC" to 0.01,
            "CRM" to 0.0,
            "ORCL" to 0.013,
            "ADBE" to 0.0,
            "BAC" to 0.025,
            "GS" to 0.022,
            "MS" to 0.03,
            "DIS" to 0.008,
            "KO" to 0.03,
            "WMT" to 0.014,
            "JNJ" to 0.03,
            "PFE" to 0.06,
            "UNH" to 0.015,
            "XOM" to 0.035,
            "CVX" to 0.04,
        )

        private val INSTRUMENTS: Map<String, InstrumentConfig> = mapOf(
            // Benchmark index — used as SPX factor proxy for equity beta decomposition
            "IDX-SPX" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Index", countryCode = "US"),
                "S&P 500 Index", "USD",
            ),
            // ── Cash equities ──
            "AAPL" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NASDAQ", sector = "Technology", countryCode = "US"),
                "Apple Inc.", "USD",
            ),
            "GOOGL" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NASDAQ", sector = "Technology", countryCode = "US"),
                "Alphabet Inc.", "USD",
            ),
            "MSFT" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NASDAQ", sector = "Technology", countryCode = "US"),
                "Microsoft Corp.", "USD",
            ),
            "AMZN" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NASDAQ", sector = "Technology", countryCode = "US"),
                "Amazon.com Inc.", "USD",
            ),
            "TSLA" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NASDAQ", sector = "Technology", countryCode = "US"),
                "Tesla Inc.", "USD",
            ),
            "NVDA" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NASDAQ", sector = "Technology", countryCode = "US"),
                "NVIDIA Corp.", "USD",
            ),
            "META" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NASDAQ", sector = "Technology", countryCode = "US"),
                "Meta Platforms Inc.", "USD",
            ),
            "BABA" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Technology", countryCode = "CN"),
                "Alibaba Group", "USD",
            ),
            "JPM" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Financials", countryCode = "US"),
                "JPMorgan Chase & Co.", "USD",
            ),
            // ── Equity options ──
            "AAPL-C-200-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "AAPL", optionType = OptionType.CALL, strike = 200.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0, dividendYield = 0.0055),
                "AAPL Call 200 Jun2026", "USD",
            ),
            "SPX-CALL-5000" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-SPX", optionType = OptionType.CALL, strike = 5000.0, expiryDate = "2026-09-18", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "SPX Call 5000 Sep2026", "USD",
            ),
            "SPX-PUT-4500" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-SPX", optionType = OptionType.PUT, strike = 4500.0, expiryDate = "2026-09-18", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "SPX Put 4500 Sep2026", "USD",
            ),
            // VIX volatility index — cash-settled against the VIX fixing. Until the
            // enum has a dedicated INDEX / VOL_INDEX type (separate ADR), it is
            // classified as CashEquity to mirror IDX-SPX so the underlyingId
            // reference from VIX-PUT-15 resolves cleanly.
            "IDX-VIX" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "CBOE", sector = "Index", countryCode = "US"),
                "CBOE Volatility Index", "USD",
            ),
            "VIX-PUT-15" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-VIX", optionType = OptionType.PUT, strike = 15.0, expiryDate = "2026-09-18", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "VIX Put 15 Sep2026", "USD",
            ),
            "SPX-PUT-4800" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-SPX", optionType = OptionType.PUT, strike = 4800.0, expiryDate = "2026-09-18", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "SPX Sep26 4800 Put", "USD",
            ),
            "SPX-CALL-5200" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-SPX", optionType = OptionType.CALL, strike = 5200.0, expiryDate = "2026-09-18", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "SPX Sep26 5200 Call", "USD",
            ),
            "NVDA-C-950-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "NVDA", optionType = OptionType.CALL, strike = 950.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.AMERICAN, contractMultiplier = 100.0),
                "NVDA Jun26 950 Call", "USD",
            ),
            "NVDA-P-800-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "NVDA", optionType = OptionType.PUT, strike = 800.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.AMERICAN, contractMultiplier = 100.0),
                "NVDA Jun26 800 Put", "USD",
            ),
            "AAPL-P-180-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "AAPL", optionType = OptionType.PUT, strike = 180.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.AMERICAN, contractMultiplier = 100.0),
                "AAPL Jun26 180 Put", "USD",
            ),
            // ── Equity futures ──
            "SPX-SEP26" to InstrumentConfig(
                EquityFuture(underlyingId = "IDX-SPX", expiryDate = "2026-09-18", contractSize = 50.0, currency = "USD"),
                "S&P 500 Sep2026 Future", "USD",
            ),
            // ── Government bonds ──
            "US2Y" to InstrumentConfig(
                GovernmentBond(currency = "USD", couponRate = 0.04, couponFrequency = 2, maturityDate = "2028-03-15", faceValue = 1000.0, dayCountConvention = "ACT/ACT"),
                "US 2Y Treasury", "USD",
            ),
            "US10Y" to InstrumentConfig(
                GovernmentBond(currency = "USD", couponRate = 0.025, couponFrequency = 2, maturityDate = "2036-05-15", faceValue = 1000.0, dayCountConvention = "ACT/ACT"),
                "US 10Y Treasury", "USD",
            ),
            "US30Y" to InstrumentConfig(
                GovernmentBond(currency = "USD", couponRate = 0.03, couponFrequency = 2, maturityDate = "2056-05-15", faceValue = 1000.0, dayCountConvention = "ACT/ACT"),
                "US 30Y Treasury", "USD",
            ),
            "DE10Y" to InstrumentConfig(
                GovernmentBond(currency = "EUR", couponRate = 0.02, couponFrequency = 1, maturityDate = "2036-05-15", faceValue = 1000.0, dayCountConvention = "ACT/ACT"),
                "German 10Y Bund", "EUR",
            ),
            // ── Corporate bonds ──
            "JPM-BOND-2031" to InstrumentConfig(
                CorporateBond(currency = "USD", couponRate = 0.045, couponFrequency = 2, maturityDate = "2031-03-15", faceValue = 1000.0, issuer = "JPMorgan Chase", creditRating = "A+", seniority = BondSeniority.SENIOR_UNSECURED),
                "JPM 4.5% 2031", "USD",
            ),
            // ── Interest rate swaps ──
            "USD-SOFR-5Y" to InstrumentConfig(
                InterestRateSwap(notional = 10_000_000.0, currency = "USD", fixedRate = 0.035, floatIndex = "SOFR", maturityDate = "2031-03-16", effectiveDate = "2026-03-16", payReceive = SwapDirection.PAY_FIXED),
                "USD SOFR 5Y IRS", "USD",
            ),
            // ── FX spot ──
            "EURUSD" to InstrumentConfig(
                FxSpot(baseCurrency = "EUR", quoteCurrency = "USD"),
                "EUR/USD Spot", "USD",
            ),
            "GBPUSD" to InstrumentConfig(
                FxSpot(baseCurrency = "GBP", quoteCurrency = "USD"),
                "GBP/USD Spot", "USD",
            ),
            "USDJPY" to InstrumentConfig(
                FxSpot(baseCurrency = "USD", quoteCurrency = "JPY"),
                "USD/JPY Spot", "JPY",
            ),
            // ── FX forwards ──
            "GBPUSD-3M" to InstrumentConfig(
                FxForward(baseCurrency = "GBP", quoteCurrency = "USD", deliveryDate = "2026-06-16", forwardRate = 1.28),
                "GBP/USD 3M Forward", "USD",
            ),
            // ── FX options ──
            "EURUSD-P-1.08-SEP26" to InstrumentConfig(
                FxOption(baseCurrency = "EUR", quoteCurrency = "USD", optionType = OptionType.PUT, strike = 1.08, expiryDate = "2026-09-15"),
                "EUR/USD Put 1.08 Sep2026", "USD",
            ),
            // ── Commodity futures ──
            "GC" to InstrumentConfig(
                CommodityFuture(commodity = "Gold", expiryDate = "2026-12-28", contractSize = 100.0, currency = "USD"),
                "Gold Futures", "USD",
            ),
            "CL" to InstrumentConfig(
                CommodityFuture(commodity = "WTI", expiryDate = "2026-08-20", contractSize = 1000.0, currency = "USD"),
                "Crude Oil Futures", "USD",
            ),
            "SI" to InstrumentConfig(
                CommodityFuture(commodity = "Silver", expiryDate = "2026-12-28", contractSize = 5000.0, currency = "USD"),
                "Silver Futures", "USD",
            ),
            "WTI-AUG26" to InstrumentConfig(
                CommodityFuture(commodity = "WTI", expiryDate = "2026-08-20", contractSize = 1000.0, currency = "USD"),
                "WTI Crude Aug2026", "USD",
            ),
            // ── Commodity options ──
            "GC-C-2200-DEC26" to InstrumentConfig(
                CommodityOption(underlyingId = "GC", optionType = OptionType.CALL, strike = 2200.0, expiryDate = "2026-12-28", contractMultiplier = 100.0),
                "Gold Call 2200 Dec2026", "USD",
            ),
            // ── Additional cash equities ──
            "AMD" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NASDAQ", sector = "Technology", countryCode = "US"),
                "Advanced Micro Devices", "USD",
            ),
            "INTC" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NASDAQ", sector = "Technology", countryCode = "US"),
                "Intel Corp.", "USD",
            ),
            "CRM" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Technology", countryCode = "US"),
                "Salesforce Inc.", "USD",
            ),
            "ORCL" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Technology", countryCode = "US"),
                "Oracle Corp.", "USD",
            ),
            "ADBE" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NASDAQ", sector = "Technology", countryCode = "US"),
                "Adobe Inc.", "USD",
            ),
            "BAC" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Financials", countryCode = "US"),
                "Bank of America Corp.", "USD",
            ),
            "GS" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Financials", countryCode = "US"),
                "Goldman Sachs Group", "USD",
            ),
            "MS" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Financials", countryCode = "US"),
                "Morgan Stanley", "USD",
            ),
            "DIS" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Consumer", countryCode = "US"),
                "Walt Disney Co.", "USD",
            ),
            "KO" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Consumer", countryCode = "US"),
                "Coca-Cola Co.", "USD",
            ),
            "WMT" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Consumer", countryCode = "US"),
                "Walmart Inc.", "USD",
            ),
            "JNJ" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Healthcare", countryCode = "US"),
                "Johnson & Johnson", "USD",
            ),
            "PFE" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Healthcare", countryCode = "US"),
                "Pfizer Inc.", "USD",
            ),
            "UNH" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Healthcare", countryCode = "US"),
                "UnitedHealth Group", "USD",
            ),
            "XOM" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Energy", countryCode = "US"),
                "ExxonMobil Corp.", "USD",
            ),
            "CVX" to InstrumentConfig(
                CashEquity(currency = "USD", exchange = "NYSE", sector = "Energy", countryCode = "US"),
                "Chevron Corp.", "USD",
            ),
            // ── Additional equity options ──
            "MSFT-C-450-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "MSFT", optionType = OptionType.CALL, strike = 450.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0, dividendYield = 0.0075),
                "MSFT Call 450 Jun2026", "USD",
            ),
            "MSFT-P-400-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "MSFT", optionType = OptionType.PUT, strike = 400.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0, dividendYield = 0.0075),
                "MSFT Put 400 Jun2026", "USD",
            ),
            "TSLA-C-280-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "TSLA", optionType = OptionType.CALL, strike = 280.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.AMERICAN, contractMultiplier = 100.0),
                "TSLA Call 280 Jun2026", "USD",
            ),
            "TSLA-P-220-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "TSLA", optionType = OptionType.PUT, strike = 220.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.AMERICAN, contractMultiplier = 100.0),
                "TSLA Put 220 Jun2026", "USD",
            ),
            "GOOGL-C-190-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "GOOGL", optionType = OptionType.CALL, strike = 190.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "GOOGL Call 190 Jun2026", "USD",
            ),
            "GOOGL-P-160-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "GOOGL", optionType = OptionType.PUT, strike = 160.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "GOOGL Put 160 Jun2026", "USD",
            ),
            "AMZN-C-220-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "AMZN", optionType = OptionType.CALL, strike = 220.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "AMZN Call 220 Jun2026", "USD",
            ),
            "AMZN-P-190-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "AMZN", optionType = OptionType.PUT, strike = 190.0, expiryDate = "2026-06-20", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "AMZN Put 190 Jun2026", "USD",
            ),
            // ── Additional government bonds ──
            "US5Y" to InstrumentConfig(
                GovernmentBond(currency = "USD", couponRate = 0.0375, couponFrequency = 2, maturityDate = "2031-05-15", faceValue = 1000.0, dayCountConvention = "ACT/ACT"),
                "US 5Y Treasury", "USD",
            ),
            "UK10Y" to InstrumentConfig(
                GovernmentBond(currency = "GBP", couponRate = 0.0325, couponFrequency = 2, maturityDate = "2036-05-15", faceValue = 1000.0, dayCountConvention = "ACT/ACT"),
                "UK 10Y Gilt", "GBP",
            ),
            "JP10Y" to InstrumentConfig(
                GovernmentBond(currency = "JPY", couponRate = 0.0075, couponFrequency = 2, maturityDate = "2036-05-15", faceValue = 1000.0, dayCountConvention = "ACT/ACT"),
                "Japan 10Y JGB", "JPY",
            ),
            "DE2Y" to InstrumentConfig(
                GovernmentBond(currency = "EUR", couponRate = 0.025, couponFrequency = 1, maturityDate = "2028-03-15", faceValue = 1000.0, dayCountConvention = "ACT/ACT"),
                "German 2Y Schatz", "EUR",
            ),
            // ── Additional corporate bonds ──
            "AAPL-BOND-2030" to InstrumentConfig(
                CorporateBond(currency = "USD", couponRate = 0.0385, couponFrequency = 2, maturityDate = "2030-09-15", faceValue = 1000.0, issuer = "Apple Inc.", creditRating = "AA+", seniority = BondSeniority.SENIOR_UNSECURED),
                "AAPL 3.85% 2030", "USD",
            ),
            "GS-BOND-2029" to InstrumentConfig(
                CorporateBond(currency = "USD", couponRate = 0.0525, couponFrequency = 2, maturityDate = "2029-06-15", faceValue = 1000.0, issuer = "Goldman Sachs", creditRating = "A+", seniority = BondSeniority.SENIOR_UNSECURED),
                "GS 5.25% 2029", "USD",
            ),
            "MSFT-BOND-2032" to InstrumentConfig(
                CorporateBond(currency = "USD", couponRate = 0.035, couponFrequency = 2, maturityDate = "2032-03-15", faceValue = 1000.0, issuer = "Microsoft Corp.", creditRating = "AAA", seniority = BondSeniority.SENIOR_UNSECURED),
                "MSFT 3.5% 2032", "USD",
            ),
            // ── Additional FX spot ──
            "AUDUSD" to InstrumentConfig(
                FxSpot(baseCurrency = "AUD", quoteCurrency = "USD"),
                "AUD/USD Spot", "USD",
            ),
            "USDCAD" to InstrumentConfig(
                FxSpot(baseCurrency = "USD", quoteCurrency = "CAD"),
                "USD/CAD Spot", "CAD",
            ),
            "USDCHF" to InstrumentConfig(
                FxSpot(baseCurrency = "USD", quoteCurrency = "CHF"),
                "USD/CHF Spot", "CHF",
            ),
            "EURGBP" to InstrumentConfig(
                FxSpot(baseCurrency = "EUR", quoteCurrency = "GBP"),
                "EUR/GBP Spot", "GBP",
            ),
            "NZDUSD" to InstrumentConfig(
                FxSpot(baseCurrency = "NZD", quoteCurrency = "USD"),
                "NZD/USD Spot", "USD",
            ),
            // ── Additional FX forwards ──
            "EURUSD-6M" to InstrumentConfig(
                FxForward(baseCurrency = "EUR", quoteCurrency = "USD", deliveryDate = "2026-08-22", forwardRate = 1.088),
                "EUR/USD 6M Forward", "USD",
            ),
            "USDJPY-3M" to InstrumentConfig(
                FxForward(baseCurrency = "USD", quoteCurrency = "JPY", deliveryDate = "2026-06-16", forwardRate = 149.5),
                "USD/JPY 3M Forward", "JPY",
            ),
            // ── Additional FX options ──
            "USDJPY-C-155-SEP26" to InstrumentConfig(
                FxOption(baseCurrency = "USD", quoteCurrency = "JPY", optionType = OptionType.CALL, strike = 155.0, expiryDate = "2026-09-15"),
                "USD/JPY Call 155 Sep2026", "USD",
            ),
            // ── Additional interest rate swaps ──
            "USD-SOFR-10Y" to InstrumentConfig(
                InterestRateSwap(notional = 20_000_000.0, currency = "USD", fixedRate = 0.038, floatIndex = "SOFR", maturityDate = "2036-03-16", effectiveDate = "2026-03-16", payReceive = SwapDirection.PAY_FIXED),
                "USD SOFR 10Y IRS", "USD",
            ),
            "EUR-ESTR-5Y" to InstrumentConfig(
                InterestRateSwap(notional = 10_000_000.0, currency = "EUR", fixedRate = 0.025, floatIndex = "ESTR", maturityDate = "2031-03-16", effectiveDate = "2026-03-16", payReceive = SwapDirection.PAY_FIXED),
                "EUR ESTR 5Y IRS", "EUR",
            ),
            // ── Additional equity futures ──
            "NDX-SEP26" to InstrumentConfig(
                EquityFuture(underlyingId = "NDX", expiryDate = "2026-09-18", contractSize = 20.0, currency = "USD"),
                "Nasdaq 100 Sep2026 Future", "USD",
            ),
            "RTY-SEP26" to InstrumentConfig(
                EquityFuture(underlyingId = "RTY", expiryDate = "2026-09-18", contractSize = 50.0, currency = "USD"),
                "Russell 2000 Sep2026 Future", "USD",
            ),
            // ── Additional commodity futures ──
            "NG" to InstrumentConfig(
                CommodityFuture(commodity = "Natural Gas", expiryDate = "2026-09-28", contractSize = 10000.0, currency = "USD"),
                "Natural Gas Futures", "USD",
            ),
            "HG" to InstrumentConfig(
                CommodityFuture(commodity = "Copper", expiryDate = "2026-09-28", contractSize = 25000.0, currency = "USD"),
                "Copper Futures", "USD",
            ),
            "PL" to InstrumentConfig(
                CommodityFuture(commodity = "Platinum", expiryDate = "2026-10-28", contractSize = 50.0, currency = "USD"),
                "Platinum Futures", "USD",
            ),
            "ZC" to InstrumentConfig(
                CommodityFuture(commodity = "Corn", expiryDate = "2026-12-14", contractSize = 5000.0, currency = "USD"),
                "Corn Futures", "USD",
            ),
            // ── Additional commodity options ──
            "CL-P-70-DEC26" to InstrumentConfig(
                CommodityOption(underlyingId = "CL", optionType = OptionType.PUT, strike = 70.0, expiryDate = "2026-12-28", contractMultiplier = 100.0),
                "Crude Oil Put 70 Dec2026", "USD",
            ),
            // ── kx-90w: derivatives-book demo placeholders ──
            // DemoBookProfiles' derivatives-book uses these ids so a vol PM can
            // see structured option detail (strike, expiry, underlying) in the
            // first 60 seconds. Implied vol lives on the volatility-service
            // surface, not on the instrument record.
            "SPX-OPT-5000C" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-SPX", optionType = OptionType.CALL, strike = 5000.0, expiryDate = "2026-06-19", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "SPX Call 5000 Jun2026", "USD",
            ),
            "ES-FUT-MAR" to InstrumentConfig(
                EquityFuture(underlyingId = "IDX-SPX", expiryDate = "2026-03-20", contractSize = 50.0, currency = "USD"),
                "E-mini S&P 500 Mar2026 Future", "USD",
            ),
            "VIX-OPT-20C" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-VIX", optionType = OptionType.CALL, strike = 20.0, expiryDate = "2026-06-18", exerciseStyle = ExerciseStyle.EUROPEAN, contractMultiplier = 100.0),
                "VIX Call 20 Jun2026", "USD",
            ),
        )

        private val CREDIT_SPREADS: Map<String, CreditSpreadConfig> = mapOf(
            "US2Y" to CreditSpreadConfig(spread = 0.0005, rating = "AAA"),
            "US10Y" to CreditSpreadConfig(spread = 0.0010, rating = "AAA"),
            "US30Y" to CreditSpreadConfig(spread = 0.0015, rating = "AAA"),
            "DE10Y" to CreditSpreadConfig(spread = 0.0008, rating = "AAA"),
            "JPM" to CreditSpreadConfig(spread = 0.0050, rating = "A+"),
            "BABA" to CreditSpreadConfig(spread = 0.0180, rating = "A"),
            "US5Y" to CreditSpreadConfig(spread = 0.0008, rating = "AAA"),
            "UK10Y" to CreditSpreadConfig(spread = 0.0010, rating = "AA"),
            "JP10Y" to CreditSpreadConfig(spread = 0.0005, rating = "A+"),
            "DE2Y" to CreditSpreadConfig(spread = 0.0005, rating = "AAA"),
            "AAPL-BOND-2030" to CreditSpreadConfig(spread = 0.0030, rating = "AA+"),
            "GS-BOND-2029" to CreditSpreadConfig(spread = 0.0060, rating = "A+"),
            "MSFT-BOND-2032" to CreditSpreadConfig(spread = 0.0025, rating = "AAA"),
        )

        private val DIVISIONS: Map<String, DivisionConfig> = mapOf(
            "equities" to DivisionConfig(name = "Equities"),
            "fixed-income-rates" to DivisionConfig(name = "Fixed Income & Rates"),
            "multi-asset" to DivisionConfig(name = "Multi-Asset"),
        )

        // ADV and bid-ask spread data for all 11 instrument types.
        // ADV is approximate daily traded notional in USD.
        // Tier guidance: <10% ADV = HIGH_LIQUID (1d), 10-25% = LIQUID (3d),
        //                25-50% = SEMI_LIQUID (5d), >50% or no ADV = ILLIQUID (10d).
        private val LIQUIDITY_DATA: Map<String, LiquidityConfig> = mapOf(
            // Large-cap equities — highly liquid
            "AAPL"                  to LiquidityConfig(adv = 80_000_000.0,  bidAskSpreadBps = 1.0,   assetClass = AssetClass.EQUITY, advShares = 450_000.0, marketDepthScore = 9.5, source = "bloomberg"),
            // Equity option on AAPL — liquid via underlying, wider spread
            "AAPL-C-200-20260620"   to LiquidityConfig(adv = 5_000_000.0,   bidAskSpreadBps = 20.0,  assetClass = AssetClass.EQUITY, advShares = null, marketDepthScore = 6.0, source = "bloomberg"),
            // Index future — highly liquid
            "SPX-SEP26"             to LiquidityConfig(adv = 120_000_000.0, bidAskSpreadBps = 0.5,   assetClass = AssetClass.EQUITY, advShares = 2_400_000.0, marketDepthScore = 9.8, source = "exchange"),
            // On-the-run US Treasury — highly liquid
            "US10Y"                 to LiquidityConfig(adv = 500_000_000.0, bidAskSpreadBps = 0.25,  assetClass = AssetClass.FIXED_INCOME, advShares = null, marketDepthScore = 9.0, source = "exchange"),
            // Investment-grade corporate bond — liquid but wider spread
            "JPM-BOND-2031"         to LiquidityConfig(adv = 15_000_000.0,  bidAskSpreadBps = 10.0,  assetClass = AssetClass.FIXED_INCOME, advShares = null, marketDepthScore = 5.5, source = "bloomberg"),
            // Vanilla IRS — semi-liquid OTC instrument
            "USD-SOFR-5Y"           to LiquidityConfig(adv = 8_000_000.0,   bidAskSpreadBps = 5.0,   assetClass = AssetClass.FIXED_INCOME, advShares = null, marketDepthScore = 4.0, source = "bloomberg"),
            // Spot FX — most liquid market
            "EURUSD"                to LiquidityConfig(adv = 1_000_000_000.0, bidAskSpreadBps = 0.1, assetClass = AssetClass.FX, advShares = null, marketDepthScore = 10.0, source = "reuters"),
            // FX forward — liquid but less than spot
            "GBPUSD-3M"             to LiquidityConfig(adv = 200_000_000.0, bidAskSpreadBps = 1.0,   assetClass = AssetClass.FX, advShares = null, marketDepthScore = 8.0, source = "reuters"),
            // FX option — semi-liquid OTC
            "EURUSD-P-1.08-SEP26"   to LiquidityConfig(adv = 20_000_000.0,  bidAskSpreadBps = 15.0,  assetClass = AssetClass.FX, advShares = null, marketDepthScore = 5.0, source = "bloomberg"),
            // WTI crude futures — highly liquid exchange-traded
            "WTI-AUG26"             to LiquidityConfig(adv = 350_000_000.0, bidAskSpreadBps = 2.0,   assetClass = AssetClass.COMMODITY, advShares = 350_000.0, marketDepthScore = 8.5, source = "exchange"),
            // Gold option — semi-liquid
            "GC-C-2200-DEC26"       to LiquidityConfig(adv = 10_000_000.0,  bidAskSpreadBps = 25.0,  assetClass = AssetClass.COMMODITY, advShares = null, marketDepthScore = 4.5, source = "exchange"),
            // Additional equities
            "AMD"                   to LiquidityConfig(adv = 60_000_000.0,  bidAskSpreadBps = 1.5,   assetClass = AssetClass.EQUITY),
            "BAC"                   to LiquidityConfig(adv = 50_000_000.0,  bidAskSpreadBps = 1.0,   assetClass = AssetClass.EQUITY),
            "GS"                    to LiquidityConfig(adv = 30_000_000.0,  bidAskSpreadBps = 2.0,   assetClass = AssetClass.EQUITY),
            "XOM"                   to LiquidityConfig(adv = 40_000_000.0,  bidAskSpreadBps = 1.5,   assetClass = AssetClass.EQUITY),
            "KO"                    to LiquidityConfig(adv = 25_000_000.0,  bidAskSpreadBps = 1.0,   assetClass = AssetClass.EQUITY),
            // Additional FX spot
            "AUDUSD"                to LiquidityConfig(adv = 500_000_000.0, bidAskSpreadBps = 0.2,   assetClass = AssetClass.FX),
            "USDCAD"                to LiquidityConfig(adv = 400_000_000.0, bidAskSpreadBps = 0.3,   assetClass = AssetClass.FX),
            // Additional fixed income
            "US5Y"                  to LiquidityConfig(adv = 400_000_000.0, bidAskSpreadBps = 0.3,   assetClass = AssetClass.FIXED_INCOME),
            "AAPL-BOND-2030"        to LiquidityConfig(adv = 12_000_000.0,  bidAskSpreadBps = 8.0,   assetClass = AssetClass.FIXED_INCOME),
            // Additional commodity futures
            "NG"                    to LiquidityConfig(adv = 200_000_000.0, bidAskSpreadBps = 3.0,   assetClass = AssetClass.COMMODITY),
            "HG"                    to LiquidityConfig(adv = 100_000_000.0, bidAskSpreadBps = 4.0,   assetClass = AssetClass.COMMODITY),
            // Additional equity futures
            "NDX-SEP26"             to LiquidityConfig(adv = 80_000_000.0,  bidAskSpreadBps = 0.8,   assetClass = AssetClass.EQUITY),
            // Additional equity options
            "MSFT-C-450-20260620"   to LiquidityConfig(adv = 4_000_000.0,   bidAskSpreadBps = 22.0,  assetClass = AssetClass.EQUITY),
            "TSLA-C-280-20260620"   to LiquidityConfig(adv = 6_000_000.0,   bidAskSpreadBps = 25.0,  assetClass = AssetClass.EQUITY),
        )

        private val DESKS: Map<String, DeskConfig> = mapOf(
            "equity-growth" to DeskConfig(name = "Equity Growth", divisionId = "equities"),
            "tech-momentum" to DeskConfig(name = "Tech Momentum", divisionId = "equities"),
            "emerging-markets" to DeskConfig(name = "Emerging Markets", divisionId = "equities"),
            "rates-trading" to DeskConfig(name = "Rates Trading", divisionId = "fixed-income-rates"),
            "multi-asset-strategies" to DeskConfig(name = "Multi-Asset Strategies", divisionId = "multi-asset"),
            "macro-hedge" to DeskConfig(name = "Macro Hedge", divisionId = "multi-asset"),
            "balanced-income" to DeskConfig(name = "Balanced Income", divisionId = "multi-asset"),
            "derivatives-trading" to DeskConfig(name = "Derivatives Trading", divisionId = "multi-asset"),
        )

        // Phase 2 Gap 6 — 3–8 traders per desk, named ref-data entities.
        // Senior traders carry larger per-trader notional limits; junior
        // traders get smaller books. Limits sit below the desk-level limit
        // so a single trader cannot exhaust the desk budget on their own.
        private val TRADERS: Map<String, List<TraderConfig>> = mapOf(
            "equity-growth" to listOf(
                TraderConfig("tr-eg-001", "Sarah Chen", "sarah.chen@kinetix.test", BigDecimal("150000000")),
                TraderConfig("tr-eg-002", "Marcus Webb", "marcus.webb@kinetix.test", BigDecimal("120000000")),
                TraderConfig("tr-eg-003", "Priya Krishnan", "priya.krishnan@kinetix.test", BigDecimal("80000000")),
                TraderConfig("tr-eg-004", "Daniel Okafor", "daniel.okafor@kinetix.test", BigDecimal("40000000")),
            ),
            "tech-momentum" to listOf(
                TraderConfig("tr-tm-001", "Alice Cohen", "alice.cohen@kinetix.test", BigDecimal("200000000")),
                TraderConfig("tr-tm-002", "Ryan Tanaka", "ryan.tanaka@kinetix.test", BigDecimal("150000000")),
                TraderConfig("tr-tm-003", "Maya Patel", "maya.patel@kinetix.test", BigDecimal("100000000")),
                TraderConfig("tr-tm-004", "Jordan Reyes", "jordan.reyes@kinetix.test", BigDecimal("50000000")),
            ),
            "emerging-markets" to listOf(
                TraderConfig("tr-em-001", "Carlos Mendes", "carlos.mendes@kinetix.test", BigDecimal("100000000")),
                TraderConfig("tr-em-002", "Nadia Hassan", "nadia.hassan@kinetix.test", BigDecimal("80000000")),
                TraderConfig("tr-em-003", "Wei Zhang", "wei.zhang@kinetix.test", BigDecimal("50000000")),
            ),
            "rates-trading" to listOf(
                TraderConfig("tr-rt-001", "James Whitfield", "james.whitfield@kinetix.test", BigDecimal("500000000")),
                TraderConfig("tr-rt-002", "Elena Rossi", "elena.rossi@kinetix.test", BigDecimal("400000000")),
                TraderConfig("tr-rt-003", "Hideo Yamamoto", "hideo.yamamoto@kinetix.test", BigDecimal("300000000")),
                TraderConfig("tr-rt-004", "Ana Soares", "ana.soares@kinetix.test", BigDecimal("150000000")),
                TraderConfig("tr-rt-005", "Tom O'Brien", "tom.obrien@kinetix.test", BigDecimal("100000000")),
            ),
            "multi-asset-strategies" to listOf(
                TraderConfig("tr-ma-001", "Lukas Bauer", "lukas.bauer@kinetix.test", BigDecimal("250000000")),
                TraderConfig("tr-ma-002", "Rachel Goldberg", "rachel.goldberg@kinetix.test", BigDecimal("180000000")),
                TraderConfig("tr-ma-003", "Yusuf El-Sayed", "yusuf.elsayed@kinetix.test", BigDecimal("120000000")),
                TraderConfig("tr-ma-004", "Isabel Fernandez", "isabel.fernandez@kinetix.test", BigDecimal("80000000")),
            ),
            "macro-hedge" to listOf(
                TraderConfig("tr-mh-001", "Henrik Lindqvist", "henrik.lindqvist@kinetix.test", BigDecimal("400000000")),
                TraderConfig("tr-mh-002", "Olivia Kerr", "olivia.kerr@kinetix.test", BigDecimal("300000000")),
                TraderConfig("tr-mh-003", "Akira Sato", "akira.sato@kinetix.test", BigDecimal("200000000")),
            ),
            "balanced-income" to listOf(
                TraderConfig("tr-bi-001", "Margaret Holloway", "margaret.holloway@kinetix.test", BigDecimal("180000000")),
                TraderConfig("tr-bi-002", "Vikram Iyer", "vikram.iyer@kinetix.test", BigDecimal("120000000")),
                TraderConfig("tr-bi-003", "Sophie Laurent", "sophie.laurent@kinetix.test", BigDecimal("80000000")),
            ),
            "derivatives-trading" to listOf(
                TraderConfig("tr-dt-001", "Vincent Park", "vincent.park@kinetix.test", BigDecimal("350000000")),
                TraderConfig("tr-dt-002", "Eva Novak", "eva.novak@kinetix.test", BigDecimal("280000000")),
                TraderConfig("tr-dt-003", "Connor Ainsworth", "connor.ainsworth@kinetix.test", BigDecimal("200000000")),
                TraderConfig("tr-dt-004", "Lina Aoki", "lina.aoki@kinetix.test", BigDecimal("130000000")),
                TraderConfig("tr-dt-005", "Felix Brandt", "felix.brandt@kinetix.test", BigDecimal("90000000")),
            ),
        )

        /** Trader IDs by desk — read by position-service tape generator to tag every ticket. */
        val TRADER_IDS_BY_DESK: Map<String, List<String>> get() = TRADERS.mapValues { (_, list) -> list.map { it.id } }

        // ── Phase 1 Gap 5 — 30-counterparty universe ─────────────────────────────
        //
        // Composition: 6 G-SIBs + 10 mid-tier banks + 4 CCPs + 4 buy-side + 6 corporates.
        // Buy-side and corporate names are scoped to OTC instruments only — the trade
        // tape generator (Gap 3) enforces this when picking counterparties for trades.
        // Exposure follows a log-normal/power-law shape: top 3 G-SIBs hold ~50-60% of
        // gross, mid 10 hold ~30-35%, tail 17 share the rest.
        // Higher-rated G-SIBs carry larger gross notionals at low spread × large notional;
        // BB-rated corporates carry small notionals at high CVA charges.
        //
        // Tier definitions are owned by common/demo/CounterpartyTiers so the position-
        // service trade tape generator and reference-data seeder agree on membership.
        val G_SIB_COUNTERPARTY_IDS: List<String> = com.kinetix.common.demo.CounterpartyTiers.G_SIB_IDS
        val MID_TIER_BANK_IDS: List<String> = com.kinetix.common.demo.CounterpartyTiers.MID_TIER_BANK_IDS
        val CCP_IDS: List<String> = com.kinetix.common.demo.CounterpartyTiers.CCP_IDS
        val BUY_SIDE_IDS: List<String> = com.kinetix.common.demo.CounterpartyTiers.BUY_SIDE_IDS
        val CORPORATE_IDS: List<String> = com.kinetix.common.demo.CounterpartyTiers.CORPORATE_IDS
        val OTC_ONLY_COUNTERPARTY_IDS: Set<String> = com.kinetix.common.demo.CounterpartyTiers.OTC_ONLY_IDS
        val LISTED_ONLY_COUNTERPARTY_IDS: Set<String> = com.kinetix.common.demo.CounterpartyTiers.LISTED_ONLY_IDS

        // Counterparties covering the major banking counterparties referenced in seed trades.
        // These IDs match what will be set on position-service seed trades.
        val COUNTERPARTIES: List<Counterparty> = listOf(
            Counterparty(
                counterpartyId = "CP-GS",
                legalName = "Goldman Sachs Bank USA",
                shortName = "Goldman Sachs",
                lei = "784F5XWPLTWKTBV3E584",
                ratingSp = "A+",
                ratingMoodys = "A1",
                ratingFitch = "A+",
                sector = "FINANCIALS",
                country = "US",
                isFinancial = true,
                pd1y = BigDecimal("0.00050"),
                lgd = BigDecimal("0.400000"),
                cdsSpreadBps = BigDecimal("65.00"),
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            Counterparty(
                counterpartyId = "CP-JPM",
                legalName = "JPMorgan Chase Bank, N.A.",
                shortName = "JPMorgan",
                lei = "7H6GLXDRUGQFU57RNE97",
                ratingSp = "A+",
                ratingMoodys = "Aa2",
                ratingFitch = "AA-",
                sector = "FINANCIALS",
                country = "US",
                isFinancial = true,
                pd1y = BigDecimal("0.00040"),
                lgd = BigDecimal("0.400000"),
                cdsSpreadBps = BigDecimal("55.00"),
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            Counterparty(
                counterpartyId = "CP-BARC",
                legalName = "Barclays Bank PLC",
                shortName = "Barclays",
                lei = "G5GSEF7VJP5I7OUK5573",
                ratingSp = "A",
                ratingMoodys = "A1",
                ratingFitch = "A+",
                sector = "FINANCIALS",
                country = "GB",
                isFinancial = true,
                pd1y = BigDecimal("0.00080"),
                lgd = BigDecimal("0.400000"),
                cdsSpreadBps = BigDecimal("80.00"),
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            Counterparty(
                counterpartyId = "CP-DB",
                legalName = "Deutsche Bank AG",
                shortName = "Deutsche Bank",
                lei = "7LTWFZYICNSX8D621K86",
                ratingSp = "BBB+",
                ratingMoodys = "A2",
                ratingFitch = "BBB+",
                sector = "FINANCIALS",
                country = "DE",
                isFinancial = true,
                pd1y = BigDecimal("0.00150"),
                lgd = BigDecimal("0.400000"),
                cdsSpreadBps = BigDecimal("110.00"),
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            Counterparty(
                counterpartyId = "CP-UBS",
                legalName = "UBS AG",
                shortName = "UBS",
                lei = "BFM8T61CT2L1QCEMIK50",
                ratingSp = "A+",
                ratingMoodys = "Aa3",
                ratingFitch = "A+",
                sector = "FINANCIALS",
                country = "CH",
                isFinancial = true,
                pd1y = BigDecimal("0.00050"),
                lgd = BigDecimal("0.400000"),
                cdsSpreadBps = BigDecimal("60.00"),
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            Counterparty(
                counterpartyId = "CP-CITI",
                legalName = "Citibank N.A.",
                shortName = "Citibank",
                lei = "E57ODZWZ7FF32TWEFA76",
                ratingSp = "A+",
                ratingMoodys = "Aa3",
                ratingFitch = "A+",
                sector = "FINANCIALS",
                country = "US",
                isFinancial = true,
                pd1y = BigDecimal("0.00050"),
                lgd = BigDecimal("0.400000"),
                cdsSpreadBps = BigDecimal("62.00"),
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            // ── Mid-tier banks (general-purpose) ─────────────────────────────
            Counterparty("CP-WFC", "Wells Fargo Bank, N.A.", "Wells Fargo", "KB1H1DSPRFMYMCUFXT09",
                "A+", "Aa2", "AA-", "FINANCIALS", "US", true,
                BigDecimal("0.00060"), BigDecimal("0.400000"), BigDecimal("70.00"), AS_OF, AS_OF),
            Counterparty("CP-BNP", "BNP Paribas SA", "BNP Paribas", "R0MUWSFPU8MPRO8K5P83",
                "A+", "Aa3", "A+", "FINANCIALS", "FR", true,
                BigDecimal("0.00070"), BigDecimal("0.400000"), BigDecimal("75.00"), AS_OF, AS_OF),
            Counterparty("CP-SOCG", "Société Générale SA", "SocGen", "O2RNE8IBXP4R0TD8PU41",
                "A", "A1", "A", "FINANCIALS", "FR", true,
                BigDecimal("0.00100"), BigDecimal("0.400000"), BigDecimal("90.00"), AS_OF, AS_OF),
            Counterparty("CP-MIZ", "Mizuho Bank, Ltd.", "Mizuho", "RB0PEZSDGCO3JS6CEU02",
                "A", "A1", "A-", "FINANCIALS", "JP", true,
                BigDecimal("0.00080"), BigDecimal("0.400000"), BigDecimal("85.00"), AS_OF, AS_OF),
            Counterparty("CP-NMR", "Nomura International plc", "Nomura", "549300CYO8R9Y2NTPI03",
                "A-", "A3", "A-", "FINANCIALS", "JP", true,
                BigDecimal("0.00120"), BigDecimal("0.400000"), BigDecimal("100.00"), AS_OF, AS_OF),
            Counterparty("CP-RBC", "Royal Bank of Canada", "RBC", "ES7IP3U3RHIGC71XBU11",
                "AA-", "Aa1", "AA", "FINANCIALS", "CA", true,
                BigDecimal("0.00040"), BigDecimal("0.400000"), BigDecimal("55.00"), AS_OF, AS_OF),
            Counterparty("CP-ING", "ING Bank N.V.", "ING", "3TK20IVIUJ8J3ZU0QE75",
                "A+", "Aa3", "A+", "FINANCIALS", "NL", true,
                BigDecimal("0.00070"), BigDecimal("0.400000"), BigDecimal("72.00"), AS_OF, AS_OF),
            Counterparty("CP-SAN", "Banco Santander SA", "Santander", "5493006QMFDDMYWIAM13",
                "A", "A2", "A", "FINANCIALS", "ES", true,
                BigDecimal("0.00100"), BigDecimal("0.400000"), BigDecimal("95.00"), AS_OF, AS_OF),
            Counterparty("CP-HAND", "Svenska Handelsbanken AB", "Handelsbanken", "NHBDILHZTYCNBV5UYZ31",
                "AA-", "Aa1", "AA", "FINANCIALS", "SE", true,
                BigDecimal("0.00050"), BigDecimal("0.400000"), BigDecimal("60.00"), AS_OF, AS_OF),
            Counterparty("CP-BBVA", "Banco Bilbao Vizcaya Argentaria SA", "BBVA", "K8MS7FD7N5Z2WQ51AZ71",
                "A", "A2", "A", "FINANCIALS", "ES", true,
                BigDecimal("0.00100"), BigDecimal("0.400000"), BigDecimal("92.00"), AS_OF, AS_OF),
            // ── CCPs (listed flow only) ─────────────────────────────────────
            Counterparty("CP-LCH", "LCH Limited", "LCH", "F226TOH6YD6XJB17KS62",
                "AA-", "Aa3", "AA-", "CCP", "GB", true,
                BigDecimal("0.00010"), BigDecimal("0.200000"), BigDecimal("15.00"), AS_OF, AS_OF),
            Counterparty("CP-CME", "Chicago Mercantile Exchange Inc.", "CME Clearing", "SNZ2OJLFK8MWHT7DLB81",
                "AA", "Aa3", "AA-", "CCP", "US", true,
                BigDecimal("0.00010"), BigDecimal("0.200000"), BigDecimal("12.00"), AS_OF, AS_OF),
            Counterparty("CP-EUREX", "Eurex Clearing AG", "Eurex Clearing", "529900LN3S50JPU47S06",
                "AA", "Aa3", "AA", "CCP", "DE", true,
                BigDecimal("0.00010"), BigDecimal("0.200000"), BigDecimal("13.00"), AS_OF, AS_OF),
            Counterparty("CP-ICE", "ICE Clear Europe Limited", "ICE Clear", "5493000F4ZO33MV32P92",
                "AA-", "Aa3", "AA-", "CCP", "GB", true,
                BigDecimal("0.00010"), BigDecimal("0.200000"), BigDecimal("14.00"), AS_OF, AS_OF),
            // ── Buy-side (OTC swaps, FX forwards only) ──────────────────────
            Counterparty("CP-BLK", "BlackRock Inc.", "BlackRock", "549300MS535KC2WH4487",
                "AA-", "Aa3", "AA-", "INVESTMENT_MANAGER", "US", true,
                BigDecimal("0.00030"), BigDecimal("0.450000"), BigDecimal("45.00"), AS_OF, AS_OF),
            Counterparty("CP-BRDG", "Bridgewater Associates LP", "Bridgewater", "549300L4RW5IY32H1H68",
                "A", "A2", "A", "HEDGE_FUND", "US", true,
                BigDecimal("0.00150"), BigDecimal("0.500000"), BigDecimal("130.00"), AS_OF, AS_OF),
            Counterparty("CP-CITDL", "Citadel LLC", "Citadel", "549300VBSWS2K05NDB18",
                "A-", "A3", "A-", "HEDGE_FUND", "US", true,
                BigDecimal("0.00200"), BigDecimal("0.500000"), BigDecimal("145.00"), AS_OF, AS_OF),
            Counterparty("CP-MIL", "Millennium Capital Partners LLP", "Millennium", "549300RGGNH5VQXVOY42",
                "A-", "A3", "A-", "HEDGE_FUND", "US", true,
                BigDecimal("0.00200"), BigDecimal("0.500000"), BigDecimal("150.00"), AS_OF, AS_OF),
            // ── Corporates (FX hedging only) ─────────────────────────────────
            Counterparty("CP-AAPL", "Apple Inc.", "Apple", "HWUPKR0MPOU8FGXBT394",
                "AA+", "Aaa", "AA+", "TECH_HARDWARE", "US", false,
                BigDecimal("0.00020"), BigDecimal("0.450000"), BigDecimal("35.00"), AS_OF, AS_OF),
            Counterparty("CP-SHEL", "Shell plc", "Shell", "21380068P1DRHMJ8KU70",
                "AA-", "Aa2", "AA-", "ENERGY", "GB", false,
                BigDecimal("0.00050"), BigDecimal("0.400000"), BigDecimal("60.00"), AS_OF, AS_OF),
            Counterparty("CP-TM", "Toyota Motor Corporation", "Toyota", "G1RWGKVS5MS1Y4ZW1U68",
                "A+", "A1", "A+", "AUTOMOTIVE", "JP", false,
                BigDecimal("0.00080"), BigDecimal("0.400000"), BigDecimal("75.00"), AS_OF, AS_OF),
            Counterparty("CP-NESN", "Nestlé S.A.", "Nestlé", "529900XOBXJZSZTFP812",
                "AA-", "Aa3", "AA-", "CONSUMER_STAPLES", "CH", false,
                BigDecimal("0.00040"), BigDecimal("0.400000"), BigDecimal("50.00"), AS_OF, AS_OF),
            Counterparty("CP-MSFT", "Microsoft Corporation", "Microsoft", "INR2EJN1ERAN0W5ZP974",
                "AAA", "Aaa", "AAA", "TECH_SOFTWARE", "US", false,
                BigDecimal("0.00010"), BigDecimal("0.450000"), BigDecimal("25.00"), AS_OF, AS_OF),
            Counterparty("CP-BA", "The Boeing Company", "Boeing", "KIQRTJM6ZCQEYHHWFV33",
                "BBB-", "Baa3", "BBB-", "AEROSPACE_DEFENSE", "US", false,
                BigDecimal("0.00400"), BigDecimal("0.400000"), BigDecimal("220.00"), AS_OF, AS_OF),
        )

        // Netting agreements: one per counterparty, ISDA 2002 close-out netting
        val NETTING_AGREEMENTS: List<NettingAgreement> = listOf(
            NettingAgreement(
                nettingSetId = "NS-GS-001",
                counterpartyId = "CP-GS",
                agreementType = "ISDA_2002",
                closeOutNetting = true,
                csaThreshold = BigDecimal("5000000.000000"),
                currency = "USD",
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            NettingAgreement(
                nettingSetId = "NS-JPM-001",
                counterpartyId = "CP-JPM",
                agreementType = "ISDA_2002",
                closeOutNetting = true,
                csaThreshold = BigDecimal("5000000.000000"),
                currency = "USD",
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            NettingAgreement(
                nettingSetId = "NS-BARC-001",
                counterpartyId = "CP-BARC",
                agreementType = "ISDA_2002",
                closeOutNetting = true,
                csaThreshold = BigDecimal("3000000.000000"),
                currency = "USD",
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            // data_quality_intent: intentional_anomaly_demo
            // CP-DB carries an expired ISDA agreement (Gap 8 anomaly contract).
            // Surface: CounterpartyRiskDashboard pill; agreementStatus = EXPIRED.
            // Booking new trades to this CP must be rejected with 422 + COUNTERPARTY_AGREEMENT_EXPIRED.
            NettingAgreement(
                nettingSetId = "NS-DB-001",
                counterpartyId = "CP-DB",
                agreementType = "ISDA_2002",
                closeOutNetting = true,
                csaThreshold = BigDecimal("2000000.000000"),
                currency = "EUR",
                createdAt = AS_OF,
                updatedAt = AS_OF,
                expiryDate = AS_OF.minusSeconds(30L * 86_400L),
            ),
            NettingAgreement(
                nettingSetId = "NS-UBS-001",
                counterpartyId = "CP-UBS",
                agreementType = "ISDA_2002",
                closeOutNetting = true,
                csaThreshold = BigDecimal("4000000.000000"),
                currency = "USD",
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            NettingAgreement(
                nettingSetId = "NS-CITI-001",
                counterpartyId = "CP-CITI",
                agreementType = "ISDA_2002",
                closeOutNetting = true,
                csaThreshold = BigDecimal("5000000.000000"),
                currency = "USD",
                createdAt = AS_OF,
                updatedAt = AS_OF,
            ),
            // ── Mid-tier banks (ISDA 2002, smaller CSA thresholds) ───────────
            isda("NS-WFC-001",  "CP-WFC",  BigDecimal("3000000.000000"), "USD"),
            isda("NS-BNP-001",  "CP-BNP",  BigDecimal("2500000.000000"), "EUR"),
            isda("NS-SOCG-001", "CP-SOCG", BigDecimal("2000000.000000"), "EUR"),
            isda("NS-MIZ-001",  "CP-MIZ",  BigDecimal("2000000.000000"), "USD"),
            isda("NS-NMR-001",  "CP-NMR",  BigDecimal("1500000.000000"), "USD"),
            isda("NS-RBC-001",  "CP-RBC",  BigDecimal("3000000.000000"), "USD"),
            isda("NS-ING-001",  "CP-ING",  BigDecimal("2500000.000000"), "EUR"),
            isda("NS-SAN-001",  "CP-SAN",  BigDecimal("2000000.000000"), "EUR"),
            isda("NS-HAND-001", "CP-HAND", BigDecimal("3000000.000000"), "USD"),
            isda("NS-BBVA-001", "CP-BBVA", BigDecimal("2000000.000000"), "EUR"),
            // ── CCPs (clearing agreement, no CSA threshold — IM/VM posted daily) ──
            ccp("NS-LCH-001",   "CP-LCH",   "GBP"),
            ccp("NS-CME-001",   "CP-CME",   "USD"),
            ccp("NS-EUREX-001", "CP-EUREX", "EUR"),
            ccp("NS-ICE-001",   "CP-ICE",   "GBP"),
            // ── Buy-side (ISDA 2002 with CSA, daily margining) ───────────────
            isda("NS-BLK-001",   "CP-BLK",   BigDecimal("1000000.000000"), "USD"),
            isda("NS-BRDG-001",  "CP-BRDG",  BigDecimal("500000.000000"),  "USD"),
            isda("NS-CITDL-001", "CP-CITDL", BigDecimal("500000.000000"),  "USD"),
            isda("NS-MIL-001",   "CP-MIL",   BigDecimal("500000.000000"),  "USD"),
            // ── Corporates (ISDA 2002 — FX hedging only, smaller thresholds) ─
            isda("NS-AAPL-001", "CP-AAPL", BigDecimal("1000000.000000"), "USD"),
            isda("NS-SHEL-001", "CP-SHEL", BigDecimal("750000.000000"),  "USD"),
            isda("NS-TM-001",   "CP-TM",   BigDecimal("500000.000000"),  "USD"),
            isda("NS-NESN-001", "CP-NESN", BigDecimal("750000.000000"),  "EUR"),
            isda("NS-MSFT-001", "CP-MSFT", BigDecimal("1500000.000000"), "USD"),
            isda("NS-BA-001",   "CP-BA",   BigDecimal("250000.000000"),  "USD"),
        )

        private fun isda(nettingSetId: String, counterpartyId: String, csaThreshold: BigDecimal, currency: String) =
            NettingAgreement(
                nettingSetId = nettingSetId,
                counterpartyId = counterpartyId,
                agreementType = "ISDA_2002",
                closeOutNetting = true,
                csaThreshold = csaThreshold,
                currency = currency,
                createdAt = AS_OF,
                updatedAt = AS_OF,
            )

        private fun ccp(nettingSetId: String, counterpartyId: String, currency: String) =
            NettingAgreement(
                nettingSetId = nettingSetId,
                counterpartyId = counterpartyId,
                agreementType = "CCP_CLEARING",
                closeOutNetting = true,
                csaThreshold = BigDecimal.ZERO,
                currency = currency,
                createdAt = AS_OF,
                updatedAt = AS_OF,
            )
    }
}

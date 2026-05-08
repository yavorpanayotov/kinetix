package com.kinetix.referencedata.seed

import com.kinetix.common.model.CreditSpread
import com.kinetix.common.model.Desk
import com.kinetix.common.model.DeskId
import com.kinetix.common.model.Division
import com.kinetix.common.model.DivisionId
import com.kinetix.common.model.DividendYield
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.ReferenceDataSource
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
) {
    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

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

    private data class LiquidityConfig(
        val adv: Double,
        val bidAskSpreadBps: Double,
        val assetClass: String,
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
                EquityOption(underlyingId = "AAPL", optionType = "CALL", strike = 200.0, expiryDate = "2026-06-20", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0, dividendYield = 0.0055),
                "AAPL Call 200 Jun2026", "USD",
            ),
            "SPX-CALL-5000" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-SPX", optionType = "CALL", strike = 5000.0, expiryDate = "2026-09-18", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0),
                "SPX Call 5000 Sep2026", "USD",
            ),
            "SPX-PUT-4500" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-SPX", optionType = "PUT", strike = 4500.0, expiryDate = "2026-09-18", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0),
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
                EquityOption(underlyingId = "IDX-VIX", optionType = "PUT", strike = 15.0, expiryDate = "2026-09-18", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0),
                "VIX Put 15 Sep2026", "USD",
            ),
            "SPX-PUT-4800" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-SPX", optionType = "PUT", strike = 4800.0, expiryDate = "2026-09-18", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0),
                "SPX Sep26 4800 Put", "USD",
            ),
            "SPX-CALL-5200" to InstrumentConfig(
                EquityOption(underlyingId = "IDX-SPX", optionType = "CALL", strike = 5200.0, expiryDate = "2026-09-18", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0),
                "SPX Sep26 5200 Call", "USD",
            ),
            "NVDA-C-950-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "NVDA", optionType = "CALL", strike = 950.0, expiryDate = "2026-06-20", exerciseStyle = "AMERICAN", contractMultiplier = 100.0),
                "NVDA Jun26 950 Call", "USD",
            ),
            "NVDA-P-800-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "NVDA", optionType = "PUT", strike = 800.0, expiryDate = "2026-06-20", exerciseStyle = "AMERICAN", contractMultiplier = 100.0),
                "NVDA Jun26 800 Put", "USD",
            ),
            "AAPL-P-180-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "AAPL", optionType = "PUT", strike = 180.0, expiryDate = "2026-06-20", exerciseStyle = "AMERICAN", contractMultiplier = 100.0),
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
                CorporateBond(currency = "USD", couponRate = 0.045, couponFrequency = 2, maturityDate = "2031-03-15", faceValue = 1000.0, issuer = "JPMorgan Chase", creditRating = "A+", seniority = "SENIOR_UNSECURED"),
                "JPM 4.5% 2031", "USD",
            ),
            // ── Interest rate swaps ──
            "USD-SOFR-5Y" to InstrumentConfig(
                InterestRateSwap(notional = 10_000_000.0, currency = "USD", fixedRate = 0.035, floatIndex = "SOFR", maturityDate = "2031-03-16", effectiveDate = "2026-03-16", payReceive = "PAY_FIXED"),
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
                FxOption(baseCurrency = "EUR", quoteCurrency = "USD", optionType = "PUT", strike = 1.08, expiryDate = "2026-09-15"),
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
                CommodityOption(underlyingId = "GC", optionType = "CALL", strike = 2200.0, expiryDate = "2026-12-28", contractMultiplier = 100.0),
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
                EquityOption(underlyingId = "MSFT", optionType = "CALL", strike = 450.0, expiryDate = "2026-06-20", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0, dividendYield = 0.0075),
                "MSFT Call 450 Jun2026", "USD",
            ),
            "MSFT-P-400-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "MSFT", optionType = "PUT", strike = 400.0, expiryDate = "2026-06-20", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0, dividendYield = 0.0075),
                "MSFT Put 400 Jun2026", "USD",
            ),
            "TSLA-C-280-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "TSLA", optionType = "CALL", strike = 280.0, expiryDate = "2026-06-20", exerciseStyle = "AMERICAN", contractMultiplier = 100.0),
                "TSLA Call 280 Jun2026", "USD",
            ),
            "TSLA-P-220-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "TSLA", optionType = "PUT", strike = 220.0, expiryDate = "2026-06-20", exerciseStyle = "AMERICAN", contractMultiplier = 100.0),
                "TSLA Put 220 Jun2026", "USD",
            ),
            "GOOGL-C-190-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "GOOGL", optionType = "CALL", strike = 190.0, expiryDate = "2026-06-20", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0),
                "GOOGL Call 190 Jun2026", "USD",
            ),
            "GOOGL-P-160-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "GOOGL", optionType = "PUT", strike = 160.0, expiryDate = "2026-06-20", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0),
                "GOOGL Put 160 Jun2026", "USD",
            ),
            "AMZN-C-220-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "AMZN", optionType = "CALL", strike = 220.0, expiryDate = "2026-06-20", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0),
                "AMZN Call 220 Jun2026", "USD",
            ),
            "AMZN-P-190-20260620" to InstrumentConfig(
                EquityOption(underlyingId = "AMZN", optionType = "PUT", strike = 190.0, expiryDate = "2026-06-20", exerciseStyle = "EUROPEAN", contractMultiplier = 100.0),
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
                CorporateBond(currency = "USD", couponRate = 0.0385, couponFrequency = 2, maturityDate = "2030-09-15", faceValue = 1000.0, issuer = "Apple Inc.", creditRating = "AA+", seniority = "SENIOR_UNSECURED"),
                "AAPL 3.85% 2030", "USD",
            ),
            "GS-BOND-2029" to InstrumentConfig(
                CorporateBond(currency = "USD", couponRate = 0.0525, couponFrequency = 2, maturityDate = "2029-06-15", faceValue = 1000.0, issuer = "Goldman Sachs", creditRating = "A+", seniority = "SENIOR_UNSECURED"),
                "GS 5.25% 2029", "USD",
            ),
            "MSFT-BOND-2032" to InstrumentConfig(
                CorporateBond(currency = "USD", couponRate = 0.035, couponFrequency = 2, maturityDate = "2032-03-15", faceValue = 1000.0, issuer = "Microsoft Corp.", creditRating = "AAA", seniority = "SENIOR_UNSECURED"),
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
                FxOption(baseCurrency = "USD", quoteCurrency = "JPY", optionType = "CALL", strike = 155.0, expiryDate = "2026-09-15"),
                "USD/JPY Call 155 Sep2026", "USD",
            ),
            // ── Additional interest rate swaps ──
            "USD-SOFR-10Y" to InstrumentConfig(
                InterestRateSwap(notional = 20_000_000.0, currency = "USD", fixedRate = 0.038, floatIndex = "SOFR", maturityDate = "2036-03-16", effectiveDate = "2026-03-16", payReceive = "PAY_FIXED"),
                "USD SOFR 10Y IRS", "USD",
            ),
            "EUR-ESTR-5Y" to InstrumentConfig(
                InterestRateSwap(notional = 10_000_000.0, currency = "EUR", fixedRate = 0.025, floatIndex = "ESTR", maturityDate = "2031-03-16", effectiveDate = "2026-03-16", payReceive = "PAY_FIXED"),
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
                CommodityOption(underlyingId = "CL", optionType = "PUT", strike = 70.0, expiryDate = "2026-12-28", contractMultiplier = 100.0),
                "Crude Oil Put 70 Dec2026", "USD",
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
            "AAPL"                  to LiquidityConfig(adv = 80_000_000.0,  bidAskSpreadBps = 1.0,   assetClass = "EQUITY", advShares = 450_000.0, marketDepthScore = 9.5, source = "bloomberg"),
            // Equity option on AAPL — liquid via underlying, wider spread
            "AAPL-C-200-20260620"   to LiquidityConfig(adv = 5_000_000.0,   bidAskSpreadBps = 20.0,  assetClass = "EQUITY", advShares = null, marketDepthScore = 6.0, source = "bloomberg"),
            // Index future — highly liquid
            "SPX-SEP26"             to LiquidityConfig(adv = 120_000_000.0, bidAskSpreadBps = 0.5,   assetClass = "EQUITY", advShares = 2_400_000.0, marketDepthScore = 9.8, source = "exchange"),
            // On-the-run US Treasury — highly liquid
            "US10Y"                 to LiquidityConfig(adv = 500_000_000.0, bidAskSpreadBps = 0.25,  assetClass = "FIXED_INCOME", advShares = null, marketDepthScore = 9.0, source = "exchange"),
            // Investment-grade corporate bond — liquid but wider spread
            "JPM-BOND-2031"         to LiquidityConfig(adv = 15_000_000.0,  bidAskSpreadBps = 10.0,  assetClass = "FIXED_INCOME", advShares = null, marketDepthScore = 5.5, source = "bloomberg"),
            // Vanilla IRS — semi-liquid OTC instrument
            "USD-SOFR-5Y"           to LiquidityConfig(adv = 8_000_000.0,   bidAskSpreadBps = 5.0,   assetClass = "FIXED_INCOME", advShares = null, marketDepthScore = 4.0, source = "bloomberg"),
            // Spot FX — most liquid market
            "EURUSD"                to LiquidityConfig(adv = 1_000_000_000.0, bidAskSpreadBps = 0.1, assetClass = "FX", advShares = null, marketDepthScore = 10.0, source = "reuters"),
            // FX forward — liquid but less than spot
            "GBPUSD-3M"             to LiquidityConfig(adv = 200_000_000.0, bidAskSpreadBps = 1.0,   assetClass = "FX", advShares = null, marketDepthScore = 8.0, source = "reuters"),
            // FX option — semi-liquid OTC
            "EURUSD-P-1.08-SEP26"   to LiquidityConfig(adv = 20_000_000.0,  bidAskSpreadBps = 15.0,  assetClass = "FX", advShares = null, marketDepthScore = 5.0, source = "bloomberg"),
            // WTI crude futures — highly liquid exchange-traded
            "WTI-AUG26"             to LiquidityConfig(adv = 350_000_000.0, bidAskSpreadBps = 2.0,   assetClass = "COMMODITY", advShares = 350_000.0, marketDepthScore = 8.5, source = "exchange"),
            // Gold option — semi-liquid
            "GC-C-2200-DEC26"       to LiquidityConfig(adv = 10_000_000.0,  bidAskSpreadBps = 25.0,  assetClass = "COMMODITY", advShares = null, marketDepthScore = 4.5, source = "exchange"),
            // Additional equities
            "AMD"                   to LiquidityConfig(adv = 60_000_000.0,  bidAskSpreadBps = 1.5,   assetClass = "EQUITY"),
            "BAC"                   to LiquidityConfig(adv = 50_000_000.0,  bidAskSpreadBps = 1.0,   assetClass = "EQUITY"),
            "GS"                    to LiquidityConfig(adv = 30_000_000.0,  bidAskSpreadBps = 2.0,   assetClass = "EQUITY"),
            "XOM"                   to LiquidityConfig(adv = 40_000_000.0,  bidAskSpreadBps = 1.5,   assetClass = "EQUITY"),
            "KO"                    to LiquidityConfig(adv = 25_000_000.0,  bidAskSpreadBps = 1.0,   assetClass = "EQUITY"),
            // Additional FX spot
            "AUDUSD"                to LiquidityConfig(adv = 500_000_000.0, bidAskSpreadBps = 0.2,   assetClass = "FX"),
            "USDCAD"                to LiquidityConfig(adv = 400_000_000.0, bidAskSpreadBps = 0.3,   assetClass = "FX"),
            // Additional fixed income
            "US5Y"                  to LiquidityConfig(adv = 400_000_000.0, bidAskSpreadBps = 0.3,   assetClass = "FIXED_INCOME"),
            "AAPL-BOND-2030"        to LiquidityConfig(adv = 12_000_000.0,  bidAskSpreadBps = 8.0,   assetClass = "FIXED_INCOME"),
            // Additional commodity futures
            "NG"                    to LiquidityConfig(adv = 200_000_000.0, bidAskSpreadBps = 3.0,   assetClass = "COMMODITY"),
            "HG"                    to LiquidityConfig(adv = 100_000_000.0, bidAskSpreadBps = 4.0,   assetClass = "COMMODITY"),
            // Additional equity futures
            "NDX-SEP26"             to LiquidityConfig(adv = 80_000_000.0,  bidAskSpreadBps = 0.8,   assetClass = "EQUITY"),
            // Additional equity options
            "MSFT-C-450-20260620"   to LiquidityConfig(adv = 4_000_000.0,   bidAskSpreadBps = 22.0,  assetClass = "EQUITY"),
            "TSLA-C-280-20260620"   to LiquidityConfig(adv = 6_000_000.0,   bidAskSpreadBps = 25.0,  assetClass = "EQUITY"),
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
            NettingAgreement(
                nettingSetId = "NS-DB-001",
                counterpartyId = "CP-DB",
                agreementType = "ISDA_2002",
                closeOutNetting = true,
                csaThreshold = BigDecimal("2000000.000000"),
                currency = "EUR",
                createdAt = AS_OF,
                updatedAt = AS_OF,
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
        )
    }
}

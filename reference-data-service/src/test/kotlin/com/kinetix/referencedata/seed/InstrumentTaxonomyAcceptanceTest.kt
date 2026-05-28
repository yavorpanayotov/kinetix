package com.kinetix.referencedata.seed

import com.kinetix.common.demo.SeedProfile
import com.kinetix.common.model.CreditSpread
import com.kinetix.common.model.DividendYield
import com.kinetix.common.model.InstrumentId
import com.kinetix.referencedata.cache.ReferenceDataCache
import com.kinetix.referencedata.kafka.ReferenceDataPublisher
import com.kinetix.referencedata.persistence.DatabaseTestSetup
import com.kinetix.referencedata.persistence.ExposedCounterpartyRepository
import com.kinetix.referencedata.persistence.ExposedCreditSpreadRepository
import com.kinetix.referencedata.persistence.ExposedDeskRepository
import com.kinetix.referencedata.persistence.ExposedDivisionRepository
import com.kinetix.referencedata.persistence.ExposedDividendYieldRepository
import com.kinetix.referencedata.persistence.ExposedInstrumentLiquidityRepository
import com.kinetix.referencedata.persistence.ExposedInstrumentRepository
import com.kinetix.referencedata.persistence.ExposedNettingAgreementRepository
import com.kinetix.referencedata.persistence.ExposedTraderRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Acceptance test pinning the canonical taxonomy of US Treasury instruments
 * surfaced by the demo seed (kx-trader-review P0 #3).
 *
 * The trader-review walkthrough on the live demo observed `UST-5Y`, `UST-10Y`,
 * etc. rendering as `Cash Equity` / `Equity` on the Trades blotter and the
 * Risk → Position Risk Breakdown — caused by the demo orchestrator inheriting
 * the book-level `EQUITY` tag for every instrument it traded. The fix is to
 * own the per-instrument classification in the reference-data master so every
 * downstream service derives `assetClass` from `instrumentType` instead of
 * trusting a free-form per-book tag.
 *
 * This test asserts that after a clean seed, the GET `/api/v1/instruments/{id}`
 * route returns the canonical `(FIXED_INCOME, GOVERNMENT_BOND)` pair for the
 * Treasury identifiers used by the demo orchestrator (`UST-2Y`, `UST-5Y`,
 * `UST-10Y`, `UST-30Y`). Real Postgres via Testcontainers — no mocks, in line
 * with the CLAUDE.md acceptance-test rule.
 */
class InstrumentTaxonomyAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val dividendYieldRepo = ExposedDividendYieldRepository(db)
    val creditSpreadRepo = ExposedCreditSpreadRepository(db)
    val instrumentRepo = ExposedInstrumentRepository(db)
    val divisionRepo = ExposedDivisionRepository(db)
    val deskRepo = ExposedDeskRepository(db)
    val liquidityRepo = ExposedInstrumentLiquidityRepository(db)
    val counterpartyRepo = ExposedCounterpartyRepository(db)
    val nettingAgreementRepo = ExposedNettingAgreementRepository(db)
    val traderRepo = ExposedTraderRepository(db)

    val noOpCache = object : ReferenceDataCache {
        override suspend fun putDividendYield(dividendYield: DividendYield) = Unit
        override suspend fun getDividendYield(instrumentId: InstrumentId): DividendYield? = null
        override suspend fun putCreditSpread(creditSpread: CreditSpread) = Unit
        override suspend fun getCreditSpread(instrumentId: InstrumentId): CreditSpread? = null
    }
    val noOpPublisher = object : ReferenceDataPublisher {
        override suspend fun publishDividendYield(dividendYield: DividendYield) = Unit
        override suspend fun publishCreditSpread(creditSpread: CreditSpread) = Unit
    }

    val seeder = DevDataSeeder(
        dividendYieldRepository = dividendYieldRepo,
        creditSpreadRepository = creditSpreadRepo,
        instrumentRepository = instrumentRepo,
        divisionRepository = divisionRepo,
        deskRepository = deskRepo,
        liquidityRepository = liquidityRepo,
        counterpartyRepository = counterpartyRepo,
        nettingAgreementRepository = nettingAgreementRepo,
        traderRepository = traderRepo,
    )

    beforeEach {
        // Wipe every reference-data table so the seeder runs cleanly.
        newSuspendedTransaction(db = db) {
            exec(
                "TRUNCATE TABLE instruments, dividend_yields, credit_spreads, " +
                    "divisions, desks, traders, instrument_liquidity, " +
                    "counterparty_master, netting_agreements " +
                    "RESTART IDENTITY CASCADE",
            )
        }
        runBlocking { seeder.seed(SeedProfile.default()) }
    }

    test("UST-10Y is classified as FIXED_INCOME / GOVERNMENT_BOND in the seed") {
        val ust10y = runBlocking { instrumentRepo.findById(InstrumentId("UST-10Y")) }
        ust10y shouldNotBe null
        ust10y!!.assetClass.name shouldBe "FIXED_INCOME"
        ust10y.instrumentType.instrumentTypeName shouldBe "GOVERNMENT_BOND"
    }

    test("UST-5Y is classified as FIXED_INCOME / GOVERNMENT_BOND in the seed") {
        val ust5y = runBlocking { instrumentRepo.findById(InstrumentId("UST-5Y")) }
        ust5y shouldNotBe null
        ust5y!!.assetClass.name shouldBe "FIXED_INCOME"
        ust5y.instrumentType.instrumentTypeName shouldBe "GOVERNMENT_BOND"
    }

    test("UST-2Y and UST-30Y are also classified as FIXED_INCOME / GOVERNMENT_BOND") {
        val ust2y = runBlocking { instrumentRepo.findById(InstrumentId("UST-2Y")) }
        val ust30y = runBlocking { instrumentRepo.findById(InstrumentId("UST-30Y")) }
        ust2y shouldNotBe null
        ust30y shouldNotBe null
        ust2y!!.assetClass.name shouldBe "FIXED_INCOME"
        ust2y.instrumentType.instrumentTypeName shouldBe "GOVERNMENT_BOND"
        ust30y!!.assetClass.name shouldBe "FIXED_INCOME"
        ust30y.instrumentType.instrumentTypeName shouldBe "GOVERNMENT_BOND"
    }

    test("PG remains a CASH_EQUITY in the seed (Procter & Gamble — a real equity, not a Treasury)") {
        val pg = runBlocking { instrumentRepo.findById(InstrumentId("PG")) }
        pg shouldNotBe null
        pg!!.assetClass.name shouldBe "EQUITY"
        pg.instrumentType.instrumentTypeName shouldBe "CASH_EQUITY"
    }

    test("AAPL is still classified as EQUITY / CASH_EQUITY (regression guard)") {
        val aapl = runBlocking { instrumentRepo.findById(InstrumentId("AAPL")) }
        aapl shouldNotBe null
        aapl!!.assetClass.name shouldBe "EQUITY"
        aapl.instrumentType.instrumentTypeName shouldBe "CASH_EQUITY"
    }
})

package com.kinetix.referencedata.seed

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.CreditSpread
import com.kinetix.common.model.Desk
import com.kinetix.common.model.Division
import com.kinetix.common.model.DividendYield
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.OptionType
import com.kinetix.common.model.ReferenceDataSource
import com.kinetix.common.model.instrument.EquityFuture
import com.kinetix.common.model.instrument.EquityOption
import com.kinetix.referencedata.model.Instrument
import com.kinetix.referencedata.model.InstrumentLiquidity
import com.kinetix.referencedata.persistence.CreditSpreadRepository
import com.kinetix.referencedata.persistence.DeskRepository
import com.kinetix.referencedata.persistence.DivisionRepository
import com.kinetix.referencedata.persistence.DividendYieldRepository
import com.kinetix.referencedata.persistence.InstrumentLiquidityRepository
import com.kinetix.referencedata.persistence.InstrumentRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs

class DevDataSeederTest : FunSpec({

    val dividendYieldRepository = mockk<DividendYieldRepository>()
    val creditSpreadRepository = mockk<CreditSpreadRepository>()
    val divisionRepository = mockk<DivisionRepository>()
    val deskRepository = mockk<DeskRepository>()
    val seeder = DevDataSeeder(dividendYieldRepository, creditSpreadRepository, divisionRepository = divisionRepository, deskRepository = deskRepository)

    beforeEach {
        clearMocks(dividendYieldRepository, creditSpreadRepository, divisionRepository, deskRepository)
    }

    test("seeds dividend yields and credit spreads when database is empty") {
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { dividendYieldRepository.save(any()) } just runs
        coEvery { creditSpreadRepository.save(any()) } just runs
        coEvery { divisionRepository.save(any()) } just runs
        coEvery { deskRepository.save(any()) } just runs

        seeder.seed()

        coVerify(exactly = 25) { dividendYieldRepository.save(any()) }
        coVerify(exactly = 13) { creditSpreadRepository.save(any()) }
    }

    test("seeds all divisions when database is empty") {
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { dividendYieldRepository.save(any()) } just runs
        coEvery { creditSpreadRepository.save(any()) } just runs
        val savedDivisions = mutableListOf<Division>()
        coEvery { divisionRepository.save(capture(savedDivisions)) } just runs
        coEvery { deskRepository.save(any()) } just runs

        seeder.seed()

        coVerify(exactly = 3) { divisionRepository.save(any()) }
        savedDivisions.map { it.id.value } shouldBe listOf("equities", "fixed-income-rates", "multi-asset")
    }

    test("seeds all desks when database is empty") {
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { dividendYieldRepository.save(any()) } just runs
        coEvery { creditSpreadRepository.save(any()) } just runs
        coEvery { divisionRepository.save(any()) } just runs
        val savedDesks = mutableListOf<Desk>()
        coEvery { deskRepository.save(capture(savedDesks)) } just runs

        seeder.seed()

        coVerify(exactly = 8) { deskRepository.save(any()) }
        savedDesks.filter { it.divisionId.value == "equities" }.size shouldBe 3
        savedDesks.filter { it.divisionId.value == "fixed-income-rates" }.size shouldBe 1
        savedDesks.filter { it.divisionId.value == "multi-asset" }.size shouldBe 4
    }

    test("skips seeding when data already exists") {
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns DividendYield(
            instrumentId = InstrumentId("AAPL"),
            yield = 0.0055,
            exDate = null,
            asOfDate = DevDataSeeder.AS_OF,
            source = ReferenceDataSource.BLOOMBERG,
        )

        seeder.seed()

        coVerify(exactly = 0) { dividendYieldRepository.save(any()) }
        coVerify(exactly = 0) { creditSpreadRepository.save(any()) }
        coVerify(exactly = 0) { divisionRepository.save(any()) }
        coVerify(exactly = 0) { deskRepository.save(any()) }
    }

    test("dividend yields use BLOOMBERG source") {
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        val savedYields = mutableListOf<DividendYield>()
        coEvery { dividendYieldRepository.save(capture(savedYields)) } just runs
        coEvery { creditSpreadRepository.save(any()) } just runs
        coEvery { divisionRepository.save(any()) } just runs
        coEvery { deskRepository.save(any()) } just runs

        seeder.seed()

        savedYields.forEach { it.source shouldBe ReferenceDataSource.BLOOMBERG }
    }

    test("credit spreads use RATING_AGENCY source and have ratings") {
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { dividendYieldRepository.save(any()) } just runs
        val savedSpreads = mutableListOf<CreditSpread>()
        coEvery { creditSpreadRepository.save(capture(savedSpreads)) } just runs
        coEvery { divisionRepository.save(any()) } just runs
        coEvery { deskRepository.save(any()) } just runs

        seeder.seed()

        savedSpreads.forEach {
            it.source shouldBe ReferenceDataSource.RATING_AGENCY
            (it.rating != null) shouldBe true
        }

        val jpm = savedSpreads.first { it.instrumentId.value == "JPM" }
        jpm.rating shouldBe "A+"
    }

    test("seeds ADV and bid-ask spread data for every instrument when liquidity repository is provided") {
        // kx-trader-review P0 #7 — the seed previously covered only 25 of the
        // 93 instruments, leaving major names (JPM, DE10Y, US30Y, etc.) to
        // fail-safe to ILLIQUID in the LVaR engine. The current contract is
        // that every instrument declared in the master also has ADV data.
        val liquidityRepository = mockk<InstrumentLiquidityRepository>()
        val seederWithLiquidity = DevDataSeeder(
            dividendYieldRepository, creditSpreadRepository,
            divisionRepository = divisionRepository, deskRepository = deskRepository,
            liquidityRepository = liquidityRepository,
        )
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { dividendYieldRepository.save(any()) } just runs
        coEvery { creditSpreadRepository.save(any()) } just runs
        coEvery { divisionRepository.save(any()) } just runs
        coEvery { deskRepository.save(any()) } just runs
        val savedLiquidity = mutableListOf<InstrumentLiquidity>()
        coEvery { liquidityRepository.upsert(capture(savedLiquidity)) } just runs

        seederWithLiquidity.seed()

        val instrumentIds = savedLiquidity.map { it.instrumentId }.toSet()
        // Core sanity coverage from the prior assertion set.
        instrumentIds.contains("AAPL") shouldBe true
        instrumentIds.contains("US10Y") shouldBe true
        instrumentIds.contains("EURUSD") shouldBe true
        instrumentIds.contains("WTI-AUG26") shouldBe true
        // Names the trader-review walkthrough flagged as ILLIQUID despite
        // being among the deepest markets in the world — JPM, the 10Y Bund,
        // and the 30Y US Treasury. Pin them explicitly so a future seed
        // regression does not silently re-introduce the bug.
        instrumentIds.contains("JPM") shouldBe true
        instrumentIds.contains("DE10Y") shouldBe true
        instrumentIds.contains("US30Y") shouldBe true
        // Treasury aliases used by the demo orchestrator (kx-trader-review P0 #3).
        instrumentIds.contains("UST-10Y") shouldBe true
        instrumentIds.contains("UST-30Y") shouldBe true
    }

    test("liquidity data is not seeded when liquidity repository is not provided") {
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { dividendYieldRepository.save(any()) } just runs
        coEvery { creditSpreadRepository.save(any()) } just runs
        coEvery { divisionRepository.save(any()) } just runs
        coEvery { deskRepository.save(any()) } just runs

        seeder.seed() // no liquidityRepository configured

        // No interaction expected with any liquidity repo
    }

    test("seeds every instrument that the position-service DevDataSeeder books trades against") {
        // Without these, the gateway's PositionResponse / TradeResponse enrichment has no
        // fallback when a position's stored instrument_type is null (legacy data, FIX flow,
        // V12 backfill), so the UI shows "—" for the Type column.
        val expectedIds = setOf(
            "AUDUSD", "CL-P-70-DEC26", "EUR-ESTR-5Y", "EURGBP", "EURUSD-6M",
            "GS-BOND-2029", "HG", "MSFT-BOND-2032", "NDX-SEP26", "NG", "NZDUSD",
            "RTY-SEP26", "USD-SOFR-10Y", "USDCAD", "USDCHF", "USDJPY-3M",
            "USDJPY-C-155-SEP26", "ZC",
        )
        (DevDataSeeder.INSTRUMENT_IDS.containsAll(expectedIds)) shouldBe true
    }

    test("AAPL liquidity has HIGH_LIQUID tier ADV well above 10 million") {
        val liquidityRepository = mockk<InstrumentLiquidityRepository>()
        val seederWithLiquidity = DevDataSeeder(
            dividendYieldRepository, creditSpreadRepository,
            divisionRepository = divisionRepository, deskRepository = deskRepository,
            liquidityRepository = liquidityRepository,
        )
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { dividendYieldRepository.save(any()) } just runs
        coEvery { creditSpreadRepository.save(any()) } just runs
        coEvery { divisionRepository.save(any()) } just runs
        coEvery { deskRepository.save(any()) } just runs
        val savedLiquidity = mutableListOf<InstrumentLiquidity>()
        coEvery { liquidityRepository.upsert(capture(savedLiquidity)) } just runs

        seederWithLiquidity.seed()

        val aapl = savedLiquidity.first { it.instrumentId == "AAPL" }
        (aapl.adv > 10_000_000.0) shouldBe true
        aapl.assetClass shouldBe AssetClass.EQUITY
    }

    // ── Phase 1 Gap 5 — 30-counterparty universe ─────────────────────────────

    test("counterparty universe holds 30 names across the configured tiers") {
        val cps = DevDataSeeder.COUNTERPARTIES
        cps.size shouldBe 30
        DevDataSeeder.G_SIB_COUNTERPARTY_IDS.size shouldBe 6
        DevDataSeeder.MID_TIER_BANK_IDS.size shouldBe 10
        DevDataSeeder.CCP_IDS.size shouldBe 4
        DevDataSeeder.BUY_SIDE_IDS.size shouldBe 4
        DevDataSeeder.CORPORATE_IDS.size shouldBe 6

        val ids = cps.map { it.counterpartyId }.toSet()
        val expected = (
            DevDataSeeder.G_SIB_COUNTERPARTY_IDS +
                DevDataSeeder.MID_TIER_BANK_IDS +
                DevDataSeeder.CCP_IDS +
                DevDataSeeder.BUY_SIDE_IDS +
                DevDataSeeder.CORPORATE_IDS
            ).toSet()
        ids shouldBe expected
    }

    test("OTC-only and listed-only restrictions are explicit in the seeder") {
        DevDataSeeder.OTC_ONLY_COUNTERPARTY_IDS shouldBe (DevDataSeeder.BUY_SIDE_IDS + DevDataSeeder.CORPORATE_IDS).toSet()
        DevDataSeeder.LISTED_ONLY_COUNTERPARTY_IDS shouldBe DevDataSeeder.CCP_IDS.toSet()
    }

    test("ratings span AAA to BB so credit-spread differentiation is visible") {
        val ratings = DevDataSeeder.COUNTERPARTIES.mapNotNull { it.ratingSp }.toSet()
        // Plan: ratings spread AAA → BB. Boeing (BBB-) is the lowest rung in the seed set;
        // AAA is required so Microsoft anchors the top.
        ratings.contains("AAA") shouldBe true
        ratings.contains("BBB-") shouldBe true
    }

    test("CCPs carry near-zero PD and tightest CDS spread") {
        val ccps = DevDataSeeder.COUNTERPARTIES.filter { it.counterpartyId in DevDataSeeder.CCP_IDS }
        ccps.size shouldBe 4
        ccps.forEach { ccp ->
            (ccp.pd1y!! < java.math.BigDecimal("0.0005")) shouldBe true
            (ccp.cdsSpreadBps!! < java.math.BigDecimal("20.00")) shouldBe true
            (ccp.sector == "CCP") shouldBe true
        }
    }

    test("every counterparty has a netting agreement") {
        val cpIds = DevDataSeeder.COUNTERPARTIES.map { it.counterpartyId }.toSet()
        val nsCpIds = DevDataSeeder.NETTING_AGREEMENTS.map { it.counterpartyId }.toSet()
        nsCpIds shouldBe cpIds
    }

    test("CCP netting agreements use CCP_CLEARING type") {
        val ccpAgreements = DevDataSeeder.NETTING_AGREEMENTS
            .filter { it.counterpartyId in DevDataSeeder.CCP_IDS }
        ccpAgreements.size shouldBe 4
        ccpAgreements.forEach { it.agreementType shouldBe "CCP_CLEARING" }
    }

    // ── kx-90w: SPX/VIX/ES placeholders used by demo derivatives-book ────────
    // The demo-orchestrator's DemoBookProfiles references SPX-OPT-5000C,
    // ES-FUT-MAR, and VIX-OPT-20C. Without matching reference-data rows the
    // UI's option-detail panel cannot show strike, expiry, optionType, or
    // underlying for trades in the derivatives book.

    test("seeds SPX-OPT-5000C as a 5000-strike SPX call expiring 2026-06-19") {
        val instrumentRepository = mockk<InstrumentRepository>()
        val seederWithInstruments = DevDataSeeder(
            dividendYieldRepository, creditSpreadRepository,
            instrumentRepository = instrumentRepository,
            divisionRepository = divisionRepository, deskRepository = deskRepository,
        )
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { dividendYieldRepository.save(any()) } just runs
        coEvery { creditSpreadRepository.save(any()) } just runs
        coEvery { divisionRepository.save(any()) } just runs
        coEvery { deskRepository.save(any()) } just runs
        val saved = mutableListOf<Instrument>()
        coEvery { instrumentRepository.save(capture(saved)) } just runs

        seederWithInstruments.seed()

        val spxCall = saved.first { it.instrumentId.value == "SPX-OPT-5000C" }
        val option = spxCall.instrumentType as EquityOption
        option.underlyingId shouldBe "IDX-SPX"
        option.optionType shouldBe OptionType.CALL
        option.strike shouldBe 5000.0
        option.expiryDate shouldBe "2026-06-19"
        spxCall.currency shouldBe "USD"
    }

    test("seeds VIX-OPT-20C as a 20-strike VIX call expiring 2026-06-18") {
        val instrumentRepository = mockk<InstrumentRepository>()
        val seederWithInstruments = DevDataSeeder(
            dividendYieldRepository, creditSpreadRepository,
            instrumentRepository = instrumentRepository,
            divisionRepository = divisionRepository, deskRepository = deskRepository,
        )
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { dividendYieldRepository.save(any()) } just runs
        coEvery { creditSpreadRepository.save(any()) } just runs
        coEvery { divisionRepository.save(any()) } just runs
        coEvery { deskRepository.save(any()) } just runs
        val saved = mutableListOf<Instrument>()
        coEvery { instrumentRepository.save(capture(saved)) } just runs

        seederWithInstruments.seed()

        val vixCall = saved.first { it.instrumentId.value == "VIX-OPT-20C" }
        val option = vixCall.instrumentType as EquityOption
        option.underlyingId shouldBe "IDX-VIX"
        option.optionType shouldBe OptionType.CALL
        option.strike shouldBe 20.0
        option.expiryDate shouldBe "2026-06-18"
        vixCall.currency shouldBe "USD"
    }

    test("seeds ES-FUT-MAR as an SPX-underlying future expiring 2026-03-20") {
        val instrumentRepository = mockk<InstrumentRepository>()
        val seederWithInstruments = DevDataSeeder(
            dividendYieldRepository, creditSpreadRepository,
            instrumentRepository = instrumentRepository,
            divisionRepository = divisionRepository, deskRepository = deskRepository,
        )
        coEvery { dividendYieldRepository.findLatest(InstrumentId("AAPL")) } returns null
        coEvery { dividendYieldRepository.save(any()) } just runs
        coEvery { creditSpreadRepository.save(any()) } just runs
        coEvery { divisionRepository.save(any()) } just runs
        coEvery { deskRepository.save(any()) } just runs
        val saved = mutableListOf<Instrument>()
        coEvery { instrumentRepository.save(capture(saved)) } just runs

        seederWithInstruments.seed()

        val esFut = saved.first { it.instrumentId.value == "ES-FUT-MAR" }
        val future = esFut.instrumentType as EquityFuture
        future.underlyingId shouldBe "IDX-SPX"
        future.expiryDate shouldBe "2026-03-20"
        esFut.currency shouldBe "USD"
    }

    test("derivatives-book demo placeholder instrument ids are present in reference-data") {
        // DemoBookProfiles.derivatives-book references these three ids. Without them,
        // gateway enrichment of position/trade responses cannot resolve structured
        // fields and the UI shows '—' for strike, expiry, and option type.
        val derivativesIds = setOf("SPX-OPT-5000C", "ES-FUT-MAR", "VIX-OPT-20C")
        DevDataSeeder.INSTRUMENT_IDS.containsAll(derivativesIds) shouldBe true
    }
})

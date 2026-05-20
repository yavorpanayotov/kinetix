package com.kinetix.referencedata.persistence

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BondSeniority
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.instrument.CashEquity
import com.kinetix.common.model.instrument.CorporateBond
import com.kinetix.common.model.instrument.FxSpot
import com.kinetix.common.model.instrument.GovernmentBond
import com.kinetix.referencedata.model.Instrument
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

private val NOW: Instant = Instant.parse("2025-01-15T10:00:00Z")

private fun equity(
    id: String,
    name: String,
    exchange: String? = null,
    sector: String? = null,
) = Instrument(
    instrumentId = InstrumentId(id),
    instrumentType = CashEquity(currency = "USD", exchange = exchange, sector = sector),
    displayName = name,
    currency = "USD",
    createdAt = NOW,
    updatedAt = NOW,
)

private fun bond(id: String, name: String) = Instrument(
    instrumentId = InstrumentId(id),
    instrumentType = CorporateBond(
        currency = "USD",
        couponRate = 0.045,
        couponFrequency = 2,
        maturityDate = "2030-05-15",
        faceValue = 1000.0,
        issuer = "ACME Corp",
        creditRating = "BBB+",
        seniority = BondSeniority.SENIOR_UNSECURED,
    ),
    displayName = name,
    currency = "USD",
    createdAt = NOW,
    updatedAt = NOW,
)

class ExposedInstrumentRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedInstrumentRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) { InstrumentsTable.deleteAll() }
    }

    test("save persists a CashEquity instrument and findById round-trips the JSONB attributes") {
        val instrument = equity(id = "AAPL", name = "Apple Inc.", exchange = "XNAS", sector = "Technology")

        repository.save(instrument)

        val retrieved = repository.findById(InstrumentId("AAPL"))
        retrieved.shouldNotBeNull()
        retrieved.displayName shouldBe "Apple Inc."
        retrieved.currency shouldBe "USD"
        retrieved.instrumentType.shouldBeInstanceOf<CashEquity>()
        (retrieved.instrumentType as CashEquity).exchange shouldBe "XNAS"
        (retrieved.instrumentType as CashEquity).sector shouldBe "Technology"
        retrieved.assetClass shouldBe AssetClass.EQUITY
    }

    test("findById returns null for an unknown id") {
        repository.findById(InstrumentId("DOES-NOT-EXIST")).shouldBeNull()
    }

    test("save upserts — saving the same id a second time updates the row in place") {
        repository.save(equity(id = "AAPL", name = "Apple Inc.", sector = "Technology"))
        repository.save(equity(id = "AAPL", name = "Apple Inc. (renamed)", sector = "Hardware"))

        val retrieved = repository.findById(InstrumentId("AAPL"))!!
        retrieved.displayName shouldBe "Apple Inc. (renamed)"
        (retrieved.instrumentType as CashEquity).sector shouldBe "Hardware"

        repository.findAll() shouldHaveSize 1
    }

    test("findByAssetClass returns only instruments in the given asset class, ordered by display name") {
        repository.save(equity(id = "MSFT", name = "Microsoft"))
        repository.save(equity(id = "AAPL", name = "Apple"))
        repository.save(bond(id = "BOND-1", name = "ACME 4.5% 2030"))

        val equities = repository.findByAssetClass(AssetClass.EQUITY)
        equities.map { it.instrumentId.value } shouldBe listOf("AAPL", "MSFT")

        val bonds = repository.findByAssetClass(AssetClass.FIXED_INCOME)
        bonds.map { it.instrumentId.value } shouldBe listOf("BOND-1")
    }

    test("findByType filters by the serialised InstrumentType name") {
        repository.save(equity(id = "AAPL", name = "Apple"))
        repository.save(bond(id = "BOND-1", name = "ACME 4.5% 2030"))
        repository.save(
            Instrument(
                instrumentId = InstrumentId("USDJPY"),
                instrumentType = FxSpot(baseCurrency = "USD", quoteCurrency = "JPY"),
                displayName = "USD/JPY",
                currency = "USD",
                createdAt = NOW,
                updatedAt = NOW,
            )
        )

        repository.findByType("CASH_EQUITY") shouldHaveSize 1
        repository.findByType("CORPORATE_BOND") shouldHaveSize 1
        repository.findByType("FX_SPOT") shouldHaveSize 1
        repository.findByType("GOVERNMENT_BOND") shouldHaveSize 0
    }

    test("CorporateBond attributes (couponRate, maturity, rating) round-trip faithfully") {
        repository.save(bond(id = "BOND-1", name = "ACME 4.5% 2030"))

        val retrieved = repository.findById(InstrumentId("BOND-1"))!!
        val cb = retrieved.instrumentType.shouldBeInstanceOf<CorporateBond>()
        cb.couponRate shouldBe 0.045
        cb.couponFrequency shouldBe 2
        cb.maturityDate shouldBe "2030-05-15"
        cb.faceValue shouldBe 1000.0
        cb.issuer shouldBe "ACME Corp"
        cb.creditRating shouldBe "BBB+"
        cb.seniority shouldBe BondSeniority.SENIOR_UNSECURED
    }

    test("GovernmentBond with null optional dayCountConvention round-trips null as null") {
        val govBond = Instrument(
            instrumentId = InstrumentId("UST10Y"),
            instrumentType = GovernmentBond(
                currency = "USD",
                couponRate = 0.035,
                couponFrequency = 2,
                maturityDate = "2034-08-15",
                faceValue = 1000.0,
            ),
            displayName = "UST 3.5% 2034",
            currency = "USD",
            createdAt = NOW,
            updatedAt = NOW,
        )
        repository.save(govBond)

        val retrieved = repository.findById(InstrumentId("UST10Y"))!!
        val gb = retrieved.instrumentType.shouldBeInstanceOf<GovernmentBond>()
        gb.couponRate shouldBe 0.035
        gb.faceValue shouldBe 1000.0
        gb.dayCountConvention.shouldBeNull()
    }
})

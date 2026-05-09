package com.kinetix.referencedata.persistence

import com.kinetix.referencedata.model.NettingAgreement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val NOW: Instant = Instant.parse("2025-01-15T10:00:00Z")

private suspend fun seedCounterparty(id: String) {
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    newSuspendedTransaction {
        CounterpartyMasterTable.insert {
            it[counterpartyId] = id
            it[legalName] = "$id Legal Entity"
            it[shortName] = id
            it[createdAt] = now
            it[updatedAt] = now
        }
    }
}

private fun agreement(
    nettingSetId: String,
    counterpartyId: String = "CP-GS",
    agreementType: String = "ISDA_2002",
    closeOutNetting: Boolean = true,
    csaThreshold: BigDecimal? = BigDecimal("1000000.00"),
    currency: String? = "USD",
    expiryDate: Instant? = null,
) = NettingAgreement(
    nettingSetId = nettingSetId,
    counterpartyId = counterpartyId,
    agreementType = agreementType,
    closeOutNetting = closeOutNetting,
    csaThreshold = csaThreshold,
    currency = currency,
    createdAt = NOW,
    updatedAt = NOW,
    expiryDate = expiryDate,
)

class ExposedNettingAgreementRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedNettingAgreementRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            NettingAgreementsTable.deleteAll()
            CounterpartyMasterTable.deleteAll()
        }
    }

    test("upsert persists an agreement and findById round-trips all fields including the NUMERIC csaThreshold") {
        seedCounterparty("CP-GS")
        repository.upsert(
            agreement(
                nettingSetId = "NS-GS-001",
                counterpartyId = "CP-GS",
                csaThreshold = BigDecimal("500000.00"),
                currency = "USD",
            )
        )

        val retrieved = repository.findById("NS-GS-001")
        retrieved.shouldNotBeNull()
        retrieved.counterpartyId shouldBe "CP-GS"
        retrieved.agreementType shouldBe "ISDA_2002"
        retrieved.closeOutNetting shouldBe true
        retrieved.csaThreshold shouldBe BigDecimal("500000.000000")
        retrieved.currency shouldBe "USD"
    }

    test("findById returns null for an unknown netting set id") {
        repository.findById("DOES-NOT-EXIST").shouldBeNull()
    }

    test("findByCounterpartyId returns every agreement for the counterparty") {
        seedCounterparty("CP-GS")
        seedCounterparty("CP-MS")
        repository.upsert(agreement(nettingSetId = "NS-GS-001", counterpartyId = "CP-GS"))
        repository.upsert(agreement(nettingSetId = "NS-GS-002", counterpartyId = "CP-GS"))
        repository.upsert(agreement(nettingSetId = "NS-MS-001", counterpartyId = "CP-MS"))

        val gsAgreements = repository.findByCounterpartyId("CP-GS")
        gsAgreements shouldHaveSize 2
        gsAgreements.map { it.nettingSetId }.toSet() shouldBe setOf("NS-GS-001", "NS-GS-002")

        repository.findByCounterpartyId("CP-MS") shouldHaveSize 1
        repository.findByCounterpartyId("CP-UNKNOWN") shouldHaveSize 0
    }

    test("upsert overwrites an existing agreement (upsert semantics, not duplicate insert)") {
        seedCounterparty("CP-GS")
        repository.upsert(
            agreement(
                nettingSetId = "NS-GS-001",
                counterpartyId = "CP-GS",
                csaThreshold = BigDecimal("500000.00"),
                closeOutNetting = true,
            )
        )
        repository.upsert(
            agreement(
                nettingSetId = "NS-GS-001",
                counterpartyId = "CP-GS",
                csaThreshold = BigDecimal("2000000.00"),
                closeOutNetting = false,
            )
        )

        val retrieved = repository.findById("NS-GS-001")!!
        retrieved.csaThreshold shouldBe BigDecimal("2000000.000000")
        retrieved.closeOutNetting shouldBe false

        repository.findByCounterpartyId("CP-GS") shouldHaveSize 1
    }

    test("null csaThreshold and null currency round-trip as null") {
        seedCounterparty("CP-GS")
        repository.upsert(
            agreement(
                nettingSetId = "NS-NO-CSA",
                counterpartyId = "CP-GS",
                csaThreshold = null,
                currency = null,
            )
        )

        val retrieved = repository.findById("NS-NO-CSA")!!
        retrieved.csaThreshold.shouldBeNull()
        retrieved.currency.shouldBeNull()
    }

    test("expiryDate round-trips when set and is null when omitted") {
        seedCounterparty("CP-GS")
        val expiry = Instant.parse("2026-01-23T00:00:00Z")
        repository.upsert(
            agreement(
                nettingSetId = "NS-EXPIRED",
                counterpartyId = "CP-GS",
                expiryDate = expiry,
            )
        )
        repository.upsert(
            agreement(
                nettingSetId = "NS-ACTIVE",
                counterpartyId = "CP-GS",
                expiryDate = null,
            )
        )

        repository.findById("NS-EXPIRED")!!.expiryDate shouldBe expiry
        repository.findById("NS-ACTIVE")!!.expiryDate.shouldBeNull()
    }
})

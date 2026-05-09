package com.kinetix.referencedata.persistence

import com.kinetix.referencedata.model.NettingAgreement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedNettingAgreementRepository(
    private val db: Database? = null,
) : NettingAgreementRepository {

    override suspend fun findById(nettingSetId: String): NettingAgreement? =
        newSuspendedTransaction(db = db) {
            NettingAgreementsTable
                .selectAll()
                .where { NettingAgreementsTable.nettingSetId eq nettingSetId }
                .singleOrNull()
                ?.toNettingAgreement()
        }

    override suspend fun findByCounterpartyId(counterpartyId: String): List<NettingAgreement> =
        newSuspendedTransaction(db = db) {
            NettingAgreementsTable
                .selectAll()
                .where { NettingAgreementsTable.counterpartyId eq counterpartyId }
                .map { it.toNettingAgreement() }
        }

    override suspend fun upsert(agreement: NettingAgreement): Unit =
        newSuspendedTransaction(db = db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            NettingAgreementsTable.upsert {
                it[nettingSetId] = agreement.nettingSetId
                it[counterpartyId] = agreement.counterpartyId
                it[agreementType] = agreement.agreementType
                it[closeOutNetting] = agreement.closeOutNetting
                it[csaThreshold] = agreement.csaThreshold
                it[currency] = agreement.currency
                it[createdAt] = now
                it[updatedAt] = now
                it[expiryDate] = agreement.expiryDate?.atOffset(ZoneOffset.UTC)
            }
        }

    private fun ResultRow.toNettingAgreement() = NettingAgreement(
        nettingSetId = this[NettingAgreementsTable.nettingSetId],
        counterpartyId = this[NettingAgreementsTable.counterpartyId],
        agreementType = this[NettingAgreementsTable.agreementType],
        closeOutNetting = this[NettingAgreementsTable.closeOutNetting],
        csaThreshold = this[NettingAgreementsTable.csaThreshold],
        currency = this[NettingAgreementsTable.currency],
        createdAt = this[NettingAgreementsTable.createdAt].toInstant(),
        updatedAt = this[NettingAgreementsTable.updatedAt].toInstant(),
        expiryDate = this[NettingAgreementsTable.expiryDate]?.toInstant(),
    )
}

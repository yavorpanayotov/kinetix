package com.kinetix.referencedata.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object NettingAgreementsTable : Table("netting_agreements") {
    val nettingSetId = varchar("netting_set_id", 255)
    val counterpartyId = varchar("counterparty_id", 255)
    val agreementType = varchar("agreement_type", 20).default("ISDA_2002")
    val closeOutNetting = bool("close_out_netting").default(true)
    val csaThreshold = decimal("csa_threshold", 24, 6).nullable()
    val currency = varchar("currency", 3).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val expiryDate = timestampWithTimeZone("expiry_date").nullable()

    override val primaryKey = PrimaryKey(nettingSetId)
}

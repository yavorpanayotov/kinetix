package com.kinetix.risk.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object RunManifestMarketDataTable : Table("run_manifest_market_data") {
    val manifestId = uuid("manifest_id").references(RunManifestsTable.manifestId)
    val contentHash = varchar("content_hash", 64)
    val dataType = varchar("data_type", 50)
    val instrumentId = varchar("instrument_id", 255)
    val assetClass = varchar("asset_class", 32)
    val status = varchar("status", 20)
    val sourceService = varchar("source_service", 50)
    val sourcedAt = timestampWithTimeZone("sourced_at")
    val isRequired = bool("is_required").default(true)

    override val primaryKey = PrimaryKey(manifestId, dataType, instrumentId)
}

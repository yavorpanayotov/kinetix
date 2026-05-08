package com.kinetix.risk.persistence

import org.jetbrains.exposed.sql.Table

object RunPositionSnapshotsTable : Table("run_position_snapshots") {
    val manifestId = uuid("manifest_id").references(RunManifestsTable.manifestId)
    val instrumentId = varchar("instrument_id", 255)
    val assetClass = varchar("asset_class", 32)
    val quantity = decimal("quantity", 28, 12)
    val avgCostAmount = decimal("avg_cost_amount", 28, 12)
    val marketPriceAmount = decimal("market_price_amount", 28, 12)
    val currency = varchar("currency", 3)
    val marketValueAmount = decimal("market_value_amount", 28, 12)
    val unrealizedPnlAmount = decimal("unrealized_pnl_amount", 28, 12)
    val instrumentType = varchar("instrument_type", 32)

    override val primaryKey = PrimaryKey(manifestId, instrumentId)
}

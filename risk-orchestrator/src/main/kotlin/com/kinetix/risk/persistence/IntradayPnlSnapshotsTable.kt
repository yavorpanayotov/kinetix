package com.kinetix.risk.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object IntradayPnlSnapshotsTable : Table("intraday_pnl_snapshots") {
    val id = long("id").autoIncrement()
    val bookId = varchar("book_id", 64)
    val snapshotAt = timestampWithTimeZone("snapshot_at")
    val baseCurrency = varchar("base_currency", 3)
    val trigger = varchar("trigger", 32)
    // Precision widened from (20, 8) to (30, 8) — see V71 migration. (20, 8)
    // caps the integer portion at 12 digits, so any P&L value with |value|
    // >= 1e12 crashes the INSERT and kills the TradeEventConsumer coroutine.
    val totalPnl = decimal("total_pnl", 30, 8)
    val realisedPnl = decimal("realised_pnl", 30, 8)
    val unrealisedPnl = decimal("unrealised_pnl", 30, 8)
    val deltaPnl = decimal("delta_pnl", 30, 8)
    val gammaPnl = decimal("gamma_pnl", 30, 8)
    val vegaPnl = decimal("vega_pnl", 30, 8)
    val thetaPnl = decimal("theta_pnl", 30, 8)
    val rhoPnl = decimal("rho_pnl", 30, 8)
    val vannaPnl = decimal("vanna_pnl", 30, 8).default(java.math.BigDecimal.ZERO)
    val volgaPnl = decimal("volga_pnl", 30, 8).default(java.math.BigDecimal.ZERO)
    val charmPnl = decimal("charm_pnl", 30, 8).default(java.math.BigDecimal.ZERO)
    val crossGammaPnl = decimal("cross_gamma_pnl", 30, 8).default(java.math.BigDecimal.ZERO)
    val unexplainedPnl = decimal("unexplained_pnl", 30, 8)
    val highWaterMark = decimal("high_water_mark", 30, 8)
    val instrumentPnlJson = text("instrument_pnl_json").default("[]")
    val correlationId = varchar("correlation_id", 128).nullable()

    override val primaryKey = PrimaryKey(id, snapshotAt)
}

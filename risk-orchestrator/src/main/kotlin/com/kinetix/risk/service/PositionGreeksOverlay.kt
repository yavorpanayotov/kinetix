package com.kinetix.risk.service

import com.kinetix.risk.model.DailyRiskSnapshot
import com.kinetix.risk.model.PositionRisk

/**
 * Fills per-position greeks from the most-recent daily risk snapshot onto live
 * position-risk rows that carry no greeks.
 *
 * Live valuations can run with a partial market-data bundle and then store null
 * option greeks (the option's underlying spot was never fetched, so the engine
 * can't price it — see [MarketDataFetcher] PARTIAL runs). The daily snapshot is
 * computed with a complete bundle and carries the correct greeks, so we use it
 * to fill the gaps.
 *
 * Only rows whose delta/gamma/vega are ALL null are overlaid — a live, computed
 * value (including an explicit zero for a linear instrument like cash equity or
 * FX spot) is never overwritten. When the snapshot itself has no greeks for an
 * instrument (e.g. a genuinely non-convergent option), the row is left as-is so
 * the UI still renders "N/A" rather than a fabricated zero.
 */
fun overlayGreeksFromSnapshots(
    positionRisk: List<PositionRisk>,
    snapshots: List<DailyRiskSnapshot>,
): List<PositionRisk> {
    if (snapshots.isEmpty()) return positionRisk

    val latestByInstrument: Map<String, DailyRiskSnapshot> = snapshots
        .groupBy { it.instrumentId.value }
        .mapValues { (_, snaps) -> snaps.maxByOrNull { it.snapshotDate }!! }

    return positionRisk.map { row ->
        // The live row already has a computed greek — keep it.
        if (row.delta != null || row.gamma != null || row.vega != null) return@map row

        val snap = latestByInstrument[row.instrumentId.value] ?: return@map row
        // The snapshot has nothing to offer either — leave the gap (renders N/A).
        if (snap.delta == null && snap.gamma == null && snap.vega == null) return@map row

        row.copy(
            delta = snap.delta,
            gamma = snap.gamma,
            vega = snap.vega,
            theta = snap.theta,
            rho = snap.rho,
        )
    }
}

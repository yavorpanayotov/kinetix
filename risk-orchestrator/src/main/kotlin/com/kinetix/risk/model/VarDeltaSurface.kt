package com.kinetix.risk.model

/**
 * Snapshot of a book's current VaR alongside the previous distinct
 * completed run, used by the Risk-tab header to render
 * `↑ $X (+Y%)` against a real baseline.
 *
 * [varDelta] and [varDeltaPct] are null when no prior completed run
 * exists — this is intentionally distinct from a zero delta, so the UI
 * can render `—` instead of a misleading `$0.00`.
 */
data class VarDeltaSurface(
    val currentVar: Double,
    val previousVar: Double?,
    val varDelta: Double?,
    val varDeltaPct: Double?,
)

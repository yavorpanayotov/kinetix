package com.kinetix.risk.service

import com.kinetix.risk.model.RunStatus
import com.kinetix.risk.model.VarDeltaSurface

/**
 * Produces the "VaR delta since last" snapshot for the Risk-tab header.
 *
 * The dashboard previously displayed `↑ $0.00 (+0.0%)` because the delta
 * was being computed against the current run itself (tautologically zero).
 * This service resolves it against the **previous distinct completed run**
 * and surfaces a null delta when there is no prior run at all — so the
 * UI can render `—` instead of a misleading `$0.00`.
 */
class VarDeltaSurfaceService(
    private val jobRecorder: ValuationJobRecorder,
) {
    /**
     * @return a [VarDeltaSurface] when at least one completed valuation exists
     *         for [bookId], or null when there are no completed runs.
     *
     *         - With two or more completed runs, [VarDeltaSurface.varDelta]
     *           and [VarDeltaSurface.varDeltaPct] are non-null.
     *         - With exactly one completed run, both are null
     *           ("no prior", not "no change").
     */
    suspend fun surface(bookId: String): VarDeltaSurface? {
        // Pull a small window of recent jobs and filter client-side for
        // COMPLETED with a non-null varValue. We need the two most recent
        // such jobs; querying a larger window than 2 leaves headroom for
        // RUNNING / FAILED jobs interleaved with completions.
        val recent = jobRecorder.findByBookId(bookId, limit = RECENT_WINDOW)
        val completed = recent
            .asSequence()
            .filter { it.status == RunStatus.COMPLETED && it.varValue != null }
            .sortedByDescending { it.startedAt }
            .toList()

        val current = completed.firstOrNull() ?: return null
        val previous = completed.getOrNull(1)
        val currentVar = current.varValue ?: return null

        val previousVar = previous?.varValue
        val varDelta = previousVar?.let { currentVar - it }
        val varDeltaPct = if (previousVar != null && previousVar != 0.0) {
            varDelta!! / previousVar * 100.0
        } else {
            null
        }

        return VarDeltaSurface(
            currentVar = currentVar,
            previousVar = previousVar,
            varDelta = varDelta,
            varDeltaPct = varDeltaPct,
        )
    }

    companion object {
        // Look back over the most recent 20 jobs so RUNNING/FAILED jobs do
        // not starve the lookup of completed predecessors. Bump if necessary
        // — TRADE_EVENT valuations are bursty but COMPLETED ones still
        // dominate steady state.
        private const val RECENT_WINDOW = 20
    }
}

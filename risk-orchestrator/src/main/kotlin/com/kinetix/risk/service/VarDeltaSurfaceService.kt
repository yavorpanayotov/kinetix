package com.kinetix.risk.service

/**
 * Stub awaiting TDD implementation. See [VarDeltaSurfaceTest] for the
 * behavioural contract: surface the latest VaR alongside the previous
 * distinct completed run so the dashboard header can show a non-tautological
 * `VaR delta since last`.
 */
class VarDeltaSurfaceService(
    @Suppress("unused") private val jobRecorder: ValuationJobRecorder,
) {
    suspend fun surface(bookId: String): VarDeltaSurface? {
        // Intentional stub — TDD red phase.
        return null
    }
}

data class VarDeltaSurface(
    val currentVar: Double,
    val previousVar: Double?,
    val varDelta: Double?,
    val varDeltaPct: Double?,
)

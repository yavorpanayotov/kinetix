package com.kinetix.volatility.service

import com.kinetix.common.model.VolSurface
import com.kinetix.volatility.cache.VolatilityCache
import com.kinetix.volatility.kafka.VolatilityPublisher
import com.kinetix.volatility.metrics.VolatilitySurfaceHealthMetrics
import com.kinetix.volatility.persistence.VolSurfaceRepository

/**
 * Ingests implied-volatility surfaces — the real call site at which a vol
 * surface is (re)built/updated.
 *
 * When [healthMetrics] is supplied, a successful ingest records the surface's
 * point count and last-update timestamp; a failed ingest records a calibration
 * failure and re-throws so callers still see the error.
 */
class VolatilityIngestionService(
    private val repository: VolSurfaceRepository,
    private val cache: VolatilityCache,
    private val publisher: VolatilityPublisher,
    private val healthMetrics: VolatilitySurfaceHealthMetrics? = null,
) {
    suspend fun ingest(surface: VolSurface) {
        val underlying = surface.instrumentId.value
        try {
            repository.save(surface)
            cache.putSurface(surface)
            publisher.publishSurface(surface)
        } catch (e: Exception) {
            healthMetrics?.recordCalibrationFailure(underlying)
            throw e
        }
        healthMetrics?.recordSurfaceUpdate(underlying, surface.points.size)
    }
}

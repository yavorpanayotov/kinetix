package com.kinetix.correlation.service

import com.kinetix.common.model.CorrelationMatrix
import com.kinetix.correlation.cache.CorrelationCache
import com.kinetix.correlation.kafka.CorrelationPublisher
import com.kinetix.correlation.metrics.CorrelationMatrixHealthMetrics
import com.kinetix.correlation.persistence.CorrelationMatrixRepository
import com.kinetix.correlation.validation.CorrelationPsdValidator

/**
 * Ingests correlation matrices — the real call site at which a correlation
 * matrix is (re)built/updated.
 *
 * When [healthMetrics] is supplied, a successful ingest records the matrix's
 * entry count and last-update timestamp; a matrix that fails positive-semi-
 * definite (PSD) validation, or an error raised while persisting/caching/
 * publishing it, records a calibration failure. Persistence errors are
 * re-thrown so callers still see them; a non-PSD matrix is still persisted
 * (PSD validation is observational here, not a gate) but counted as a
 * calibration failure.
 */
class CorrelationIngestionService(
    private val repository: CorrelationMatrixRepository,
    private val cache: CorrelationCache,
    private val publisher: CorrelationPublisher,
    private val healthMetrics: CorrelationMatrixHealthMetrics? = null,
) {
    suspend fun ingest(matrix: CorrelationMatrix) {
        val matrixId = CorrelationMatrixHealthMetrics.DEFAULT_MATRIX_ID
        try {
            repository.save(matrix)
            cache.put(matrix.labels, matrix.windowDays, matrix)
            publisher.publish(matrix)
        } catch (e: Exception) {
            healthMetrics?.recordCalibrationFailure(matrixId)
            throw e
        }
        healthMetrics?.let { metrics ->
            if (!CorrelationPsdValidator.validate(matrix).isPsd()) {
                metrics.recordCalibrationFailure(matrixId)
            }
            metrics.recordMatrixUpdate(matrixId, matrix.values.size)
        }
    }
}

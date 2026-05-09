package com.kinetix.price.persistence

import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.PricePoint
import java.time.Instant

interface PriceRepository {
    suspend fun save(point: PricePoint)
    suspend fun findLatest(instrumentId: InstrumentId): PricePoint?
    suspend fun findByInstrumentId(instrumentId: InstrumentId, from: Instant, to: Instant): List<PricePoint>
    suspend fun findDailyCloseByInstrumentId(instrumentId: InstrumentId, from: Instant, to: Instant): List<PricePoint>

    /**
     * Returns instruments whose freshest price is older than [thresholdHours]
     * relative to the platform-wide freshest price across all instruments.
     */
    suspend fun findStaleInstruments(thresholdHours: Long): List<StaleInstrument>
}

package com.kinetix.position.client

import com.kinetix.common.model.InstrumentId

/**
 * Reads the current mid price for an instrument from price-service.
 *
 * Used by order submission to capture the arrival price server-side rather than
 * trusting a caller-supplied value (spec: `execution.allium` —
 * `let arrival_price = current_mid_price(instrument_id)`).
 */
interface PriceLookupClient {
    /**
     * Returns the latest mid price for [instrumentId], or `null` when price-service
     * has no price for it.
     */
    suspend fun currentMidPrice(instrumentId: InstrumentId): MidPrice?
}

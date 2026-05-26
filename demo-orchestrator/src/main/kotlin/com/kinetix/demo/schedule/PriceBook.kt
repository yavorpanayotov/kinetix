package com.kinetix.demo.schedule

import java.math.BigDecimal

/**
 * Source of indicative per-unit USD prices used by [SimulatedTraderJob] to
 * derive a quantity from a notional figure.
 *
 * Implementations may key off the [instrumentId] (e.g. a richer price book
 * that knows each ticker individually), the [assetClass] (e.g. a coarse
 * fallback that returns one price per asset class), or both.
 */
interface PriceBook {
    /**
     * Indicative per-unit USD price for the given [instrumentId] in the
     * given [assetClass]. Implementations must always return a positive
     * price — falling back to a sensible asset-class default if the
     * instrument is unknown.
     */
    fun priceFor(instrumentId: String, assetClass: String): BigDecimal
}

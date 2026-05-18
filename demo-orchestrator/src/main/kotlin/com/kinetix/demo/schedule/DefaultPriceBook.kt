package com.kinetix.demo.schedule

import java.math.BigDecimal

/**
 * Temporary stand-in for a real `price-service` lookup. Maps a
 * [com.kinetix.demo.profile.DemoBookProfile.assetClass] tag to a plausible
 * per-unit price in USD so [SimulatedTraderJob] can derive a `quantity` from a
 * notional. Numbers are deliberately round — these are demo trades, not P&L
 * statements — and will be replaced when checkbox 2.4 introduces a price-service
 * client.
 *
 * Unknown asset classes resolve to a conservative `$100` so the job never blows
 * up on a freshly added profile tag.
 */
class DefaultPriceBook(
    private val prices: Map<String, BigDecimal> = DEFAULT_PRICES,
    private val fallbackPrice: BigDecimal = DEFAULT_FALLBACK_PRICE,
) {

    /** Indicative per-unit USD price for [assetClass]. */
    fun priceFor(assetClass: String): BigDecimal =
        prices[assetClass] ?: fallbackPrice

    companion object {
        private val DEFAULT_FALLBACK_PRICE: BigDecimal = "100.00".toBigDecimal()

        /**
         * Asset-class -> indicative USD spot price. Keys are the canonical
         * `com.kinetix.common.model.AssetClass` enum names so the values
         * deserialise on the wire when posted to `position-service`.
         */
        val DEFAULT_PRICES: Map<String, BigDecimal> = mapOf(
            "EQUITY" to "100.00".toBigDecimal(),
            "FX" to "1.10".toBigDecimal(),
            "FIXED_INCOME" to "0.05".toBigDecimal(),
            "COMMODITY" to "75.00".toBigDecimal(),
            "DERIVATIVE" to "50.00".toBigDecimal(),
        )
    }
}

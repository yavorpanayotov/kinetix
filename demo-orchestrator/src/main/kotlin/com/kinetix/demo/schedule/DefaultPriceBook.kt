package com.kinetix.demo.schedule

import java.math.BigDecimal

/**
 * Coarse fallback [PriceBook]: maps a
 * [com.kinetix.demo.profile.DemoBookProfile.assetClass] tag to a plausible
 * per-unit USD price so [SimulatedTraderJob] can derive a `quantity` from a
 * notional. Numbers are deliberately round — these are demo trades, not P&L
 * statements.
 *
 * This implementation ignores [instrumentId] and looks up purely by asset
 * class. For a richer per-instrument book see [SimulatedPriceBook].
 *
 * Unknown asset classes resolve to a conservative `$100` so the job never
 * blows up on a freshly added profile tag.
 */
class DefaultPriceBook(
    private val prices: Map<String, BigDecimal> = DEFAULT_PRICES,
    private val fallbackPrice: BigDecimal = DEFAULT_FALLBACK_PRICE,
) : PriceBook {

    /** Indicative per-unit USD price for [assetClass]; [instrumentId] is ignored. */
    override fun priceFor(instrumentId: String, assetClass: String): BigDecimal =
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
            "FIXED_INCOME" to "100.00".toBigDecimal(),
            "COMMODITY" to "75.00".toBigDecimal(),
            "DERIVATIVE" to "50.00".toBigDecimal(),
        )
    }
}

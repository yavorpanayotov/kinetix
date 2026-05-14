package com.kinetix.risk.seed

import com.kinetix.common.demo.BlackScholes
import com.kinetix.common.model.AssetClass

/**
 * Per-book demo position used by [PnLAttributionDeriver].
 *
 * Cash positions (equity / FX / commodity / fixed-income) describe a signed quantity
 * of an instrument that tracks the price tape directly. Option positions carry a
 * Black-Scholes spec — strike, expiry-in-years from as-of, call/put — so the deriver
 * can re-price the option at each day on the tape using the underlier's spot path.
 */
data class DemoBookPosition(
    val instrumentId: String,
    val assetClass: AssetClass,
    /** Signed quantity. Negative = short. */
    val quantity: Double,
    /** When non-null, the position is priced via Black-Scholes against [optionSpec]. */
    val optionSpec: OptionSpec? = null,
) {
    data class OptionSpec(
        /** Tape symbol of the underlier (must exist in DemoTapeUniverse). */
        val underlier: String,
        val strike: Double,
        /**
         * Years to expiry as measured from the tape's most-recent ("day 0") date.
         * The deriver shortens this for older days so theta decay shows up in the
         * attribution: tte_at_day_d = yearsToExpiryFromAsOf + d/252 (i.e. the
         * option had longer to live further back in the past).
         */
        val yearsToExpiryFromAsOf: Double,
        val type: BlackScholes.OptionType,
    )

    val isOption: Boolean get() = optionSpec != null
}

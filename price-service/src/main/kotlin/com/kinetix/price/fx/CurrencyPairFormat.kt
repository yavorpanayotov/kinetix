package com.kinetix.price.fx

/**
 * Validate a "BASE/QUOTE" FX currency-pair string, returning the
 * `(base, quote)` legs. Each leg must be a 3-letter upper-case
 * ASCII code; the pair must be exactly two legs separated by a
 * single `/`; the base and quote must differ.
 *
 * A malformed pair propagating into the cross-rate calculator
 * silently picks the wrong leg and produces inverted conversions —
 * the desk's blotter would never reconcile.
 */
fun validateCurrencyPair(pair: String): Pair<String, String> {
    val parts = pair.split("/")
    require(parts.size == 2) {
        "currency pair '$pair' must be in BASE/QUOTE form with a single '/'"
    }
    val (base, quote) = parts
    require(LEG_PATTERN.matches(base) && LEG_PATTERN.matches(quote)) {
        "currency pair '$pair' has legs that are not ISO 4217 alpha-3 codes"
    }
    require(base != quote) {
        "currency pair '$pair' is a self-pair"
    }
    return base to quote
}

private val LEG_PATTERN = Regex("^[A-Z]{3}$")

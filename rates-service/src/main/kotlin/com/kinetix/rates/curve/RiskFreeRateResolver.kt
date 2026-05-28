package com.kinetix.rates.curve

/**
 * Resolve a per-currency risk-free rate, failing closed on bad data
 * or unknown currencies. Different currencies anchor on different
 * RFRs (USD->SOFR, GBP->SONIA, EUR->ESTR, JPY->TONA, CHF->SARON); a
 * silent fallback to USD's curve for an unknown currency would
 * mis-discount the cashflows.
 *
 * @throws IllegalStateException for a null/NaN rate on a known currency.
 * @throws IllegalArgumentException for an unknown currency.
 */
fun resolveRiskFreeRate(currency: String, rate: Double?): Double {
    require(currency in SUPPORTED_CURRENCIES) {
        "no risk-free rate configured for currency '$currency' (supported: $SUPPORTED_CURRENCIES)"
    }
    check(rate != null) { "risk-free rate for $currency is null — refusing to discount with no anchor" }
    check(rate.isFinite()) { "risk-free rate for $currency is non-finite ($rate)" }
    return rate
}

private val SUPPORTED_CURRENCIES: Set<String> = setOf("USD", "GBP", "EUR", "JPY", "CHF")

package com.kinetix.rates.curve

import java.time.LocalDate

/**
 * Resolve a rate-curve request to the actual curve name, applying the
 * post-retirement Libor → overnight-RFR fallback when needed.
 *
 * Legacy systems still request GBP Libor 3M and USD Libor 6M tenors;
 * post-retirement those route to the overnight risk-free rate for the
 * currency (SONIA / SOFR). The retirement date is supplied because
 * different Libor tenors retired on different dates; the cut-off is
 * inclusive (today >= retirementDate → fall back).
 *
 * Non-Libor request kinds pass through unchanged.
 *
 * @throws IllegalArgumentException for an unsupported currency in the
 * Libor fallback path.
 */
fun resolveRateCurve(
    currency: String,
    tenor: String,
    requestKind: String,
    retirementDate: String,
    today: String,
): String {
    if (requestKind != "LIBOR") {
        return "${currency}_${requestKind}"
    }
    val isRetired = !LocalDate.parse(today).isBefore(LocalDate.parse(retirementDate))
    if (!isRetired) {
        return "${currency}_LIBOR_${tenor}"
    }
    val fallbackRfr = LIBOR_FALLBACK[currency]
        ?: throw IllegalArgumentException(
            "no Libor fallback configured for currency '$currency'",
        )
    return "${currency}_${fallbackRfr}"
}

private val LIBOR_FALLBACK: Map<String, String> = mapOf(
    "GBP" to "SONIA",
    "USD" to "SOFR",
    "JPY" to "TONA",
    "CHF" to "SARON",
    "EUR" to "ESTR",
)

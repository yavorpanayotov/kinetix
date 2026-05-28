package com.kinetix.refdata.instrument

/**
 * Validate that [code] is a syntactically-valid ISO 4217 alpha-3
 * currency code: exactly three upper-case ASCII letters. Does NOT
 * verify the code is on the active ISO list — that check is the
 * reference-data feed's responsibility — but it does ensure the
 * pricing engine never receives "USDS" or "us dollar" and silently
 * picks the wrong curve.
 */
fun validateIsoCurrency(code: String): String {
    require(ISO_PATTERN.matches(code)) {
        "currency code '$code' is not a valid ISO 4217 alpha-3 code"
    }
    return code
}

private val ISO_PATTERN = Regex("^[A-Z]{3}$")

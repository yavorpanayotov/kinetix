package com.kinetix.refdata.instrument

/**
 * Validate an ISIN (ISO 6166): exactly 12 characters consisting of a
 * 2-letter ISO 3166 country prefix, 9 alphanumeric national-security
 * identifier characters, and 1 numeric check digit computed via the
 * Luhn algorithm on the digit-expansion of the first 11 characters
 * (each letter expands to its ordinal in 10..35).
 *
 * The spec's "18 alphanumeric characters" wording was incorrect — the
 * ISO 6166 standard is 12. This validator implements the standard.
 *
 * @throws IllegalArgumentException on any format or check-digit
 * mismatch.
 */
fun validateIsin(isin: String): String {
    require(ISIN_FORMAT.matches(isin)) {
        "ISIN '$isin' does not match the 12-character A-Z[0-9A-Z]{9}[0-9] pattern"
    }
    val computedCheck = luhnCheckDigit(isin.substring(0, 11))
    val providedCheck = isin[11].digitToInt()
    require(computedCheck == providedCheck) {
        "ISIN '$isin' has check digit $providedCheck but Luhn expects $computedCheck"
    }
    return isin
}

private val ISIN_FORMAT = Regex("^[A-Z]{2}[0-9A-Z]{9}[0-9]$")

/** Luhn check-digit computation over the digit expansion. */
private fun luhnCheckDigit(payload: String): Int {
    // Expand letters to their (10 + ordinal) numeric value, concatenated.
    val expanded = buildString {
        for (c in payload) {
            if (c in '0'..'9') append(c)
            else append(10 + (c - 'A'))
        }
    }
    // Standard Luhn from the right: double every second digit.
    var total = 0
    var doubleIt = true  // rightmost gets doubled first
    for (i in expanded.length - 1 downTo 0) {
        var d = expanded[i].digitToInt()
        if (doubleIt) {
            d *= 2
            if (d > 9) d -= 9
        }
        total += d
        doubleIt = !doubleIt
    }
    return (10 - (total % 10)) % 10
}

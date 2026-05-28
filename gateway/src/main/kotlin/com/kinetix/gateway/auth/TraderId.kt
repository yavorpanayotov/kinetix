package com.kinetix.gateway.auth

/**
 * Validate a trader ID pulled from the auth token. Must be non-empty,
 * ASCII alphanumeric plus the `_.-` separators common across SAML and
 * OIDC providers, and within the 64-character column width of the
 * downstream audit-trail field.
 *
 * Every downstream service uses this id verbatim in attribution
 * queries; a whitespace or punctuation character can silently break
 * the query or — worse — open a SQL-injection vector. The validator
 * fails fast at the gateway so the malformed id never propagates.
 *
 * @throws IllegalArgumentException if [traderId] is malformed.
 */
fun validateTraderId(traderId: String): String {
    require(traderId.isNotEmpty()) { "traderId must not be empty" }
    require(traderId.isNotBlank()) { "traderId must not be whitespace-only" }
    require(traderId.length <= 64) {
        "traderId (${traderId.length} chars) exceeds the 64-character maximum"
    }
    require(TRADER_ID_PATTERN.matches(traderId)) {
        "traderId '$traderId' contains characters outside [A-Za-z0-9_.-]"
    }
    return traderId
}

private val TRADER_ID_PATTERN = Regex("^[A-Za-z0-9_.-]+$")

package com.kinetix.gateway.routing

import com.kinetix.common.model.AssetClass

/**
 * Parse a caller-supplied asset-class string into the canonical
 * [AssetClass] enum, or return a structured error the route can map to
 * a 400 response without leaking the exception's stack to the wire.
 *
 * Matching is case-insensitive (matches "EQUITY", "equity", "Equity")
 * and trims surrounding whitespace. Null and empty input return
 * [AssetClassParseResult.NotProvided]; anything else that fails to
 * resolve returns [AssetClassParseResult.Unknown] with the offending
 * input and the supported list so the client can self-correct.
 */
fun parseAssetClassOrError(input: String?): AssetClassParseResult {
    if (input == null) return AssetClassParseResult.NotProvided
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return AssetClassParseResult.NotProvided
    val match = AssetClass.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
    return if (match != null) {
        AssetClassParseResult.Parsed(match)
    } else {
        AssetClassParseResult.Unknown(
            input = input,
            supported = AssetClass.entries.map { it.name },
        )
    }
}

sealed interface AssetClassParseResult {
    data class Parsed(val assetClass: AssetClass) : AssetClassParseResult
    data object NotProvided : AssetClassParseResult
    data class Unknown(val input: String, val supported: List<String>) : AssetClassParseResult
}

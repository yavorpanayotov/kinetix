package com.kinetix.position.collateral

/** Outcome of [detectCollateralCurrencyMismatch]. */
sealed interface CollateralCurrencyCheck {
    data object Match : CollateralCurrencyCheck
    data class Mismatch(
        val positionCurrency: String,
        val collateralCurrency: String,
    ) : CollateralCurrencyCheck
}

/**
 * Compare the currency of a position with the currency of the
 * collateral posted against it. Matching currencies allow the haircut
 * to apply directly; a mismatch needs an FX-conversion step before
 * the haircut. Comparison is case-sensitive (USD != usd) so that
 * upstream feeds normalising case errors don't silently mask the
 * mismatch.
 */
fun detectCollateralCurrencyMismatch(
    positionCurrency: String,
    collateralCurrency: String,
): CollateralCurrencyCheck {
    return if (positionCurrency == collateralCurrency && positionCurrency.isNotEmpty()) {
        CollateralCurrencyCheck.Match
    } else {
        CollateralCurrencyCheck.Mismatch(positionCurrency, collateralCurrency)
    }
}

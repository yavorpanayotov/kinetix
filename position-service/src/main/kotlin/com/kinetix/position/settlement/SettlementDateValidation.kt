package com.kinetix.position.settlement

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Validate that [settlementDate] matches the per-asset-class T+N
 * convention given [tradeDate]: equities T+2, bonds T+1, FX T+0.
 *
 * A trade booked with the wrong settlement date misses the clearing
 * cycle and triggers a fail-settle workflow. Catching the mis-tagged
 * date at trade-booking time saves operations an afternoon of
 * forensic work the next morning.
 *
 * @throws IllegalArgumentException for an unsupported asset class,
 * a past-dated settlement, or a settlement that doesn't match the
 * expected T+N delta.
 */
fun validateSettlementDate(
    assetClass: String,
    tradeDate: LocalDate,
    settlementDate: LocalDate,
) {
    val expectedDelta = EXPECTED_DELTA[assetClass]
        ?: throw IllegalArgumentException(
            "no settlement convention configured for asset class '$assetClass'",
        )
    require(!settlementDate.isBefore(tradeDate)) {
        "settlement date $settlementDate is before trade date $tradeDate"
    }
    val actualDelta = ChronoUnit.DAYS.between(tradeDate, settlementDate).toInt()
    require(actualDelta == expectedDelta) {
        "$assetClass trade expects T+$expectedDelta settlement, got T+$actualDelta " +
            "(trade $tradeDate, settle $settlementDate)"
    }
}

private val EXPECTED_DELTA: Map<String, Int> = mapOf(
    "EQUITY" to 2,
    "BOND" to 1,
    "FX" to 0,
)

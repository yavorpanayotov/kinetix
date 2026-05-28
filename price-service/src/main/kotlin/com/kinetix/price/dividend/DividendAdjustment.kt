package com.kinetix.price.dividend

import java.time.LocalDate

/**
 * Apply a dividend adjustment to a historical price.
 *
 * On the ex-dividend date the price drops by the dividend amount — a
 * back-test would otherwise read that drop as a real loss. The
 * adjuster subtracts the per-share dividend from any price dated on
 * or after the ex-date; prices strictly before the ex-date are
 * unchanged. The adjuster keys off the ex-date, not the record date
 * (the record date is the cut-off for the issuer's shareholder
 * register, a day or two after the ex-date; using it back-dates the
 * adjustment by one trading day and corrupts the series).
 */
fun applyDividendAdjustment(
    rawPrice: Double,
    priceDate: LocalDate,
    exDate: LocalDate,
    dividendPerShare: Double,
): Double {
    if (priceDate.isBefore(exDate)) return rawPrice
    return rawPrice - dividendPerShare
}

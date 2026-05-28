package com.kinetix.common.model

import java.math.BigDecimal

data class Tenor(
    val label: String,
    val days: Int,
    val rate: BigDecimal,
) : Comparable<Tenor> {

    init {
        require(label.isNotBlank()) { "Tenor label must not be blank" }
        require(days > 0) { "Tenor days must be positive, was $days" }
        // Note: negative rates are intentionally allowed — ECB deposit rate
        // was -0.50% from 2019 through 2022 and bund yields were negative
        // across the curve. Curve-level invariants (finite values, ordering)
        // live in rates-service YieldCurveValidation, which is the
        // authoritative validator for yield curve quotes.
    }

    override fun compareTo(other: Tenor): Int = days.compareTo(other.days)

    companion object {
        fun overnight(rate: BigDecimal) = Tenor("O/N", 1, rate)
        fun oneWeek(rate: BigDecimal) = Tenor("1W", 7, rate)
        fun oneMonth(rate: BigDecimal) = Tenor("1M", 30, rate)
        fun threeMonths(rate: BigDecimal) = Tenor("3M", 90, rate)
        fun sixMonths(rate: BigDecimal) = Tenor("6M", 180, rate)
        fun oneYear(rate: BigDecimal) = Tenor("1Y", 365, rate)
        fun twoYears(rate: BigDecimal) = Tenor("2Y", 730, rate)
        fun fiveYears(rate: BigDecimal) = Tenor("5Y", 1825, rate)
        fun tenYears(rate: BigDecimal) = Tenor("10Y", 3650, rate)
        fun thirtyYears(rate: BigDecimal) = Tenor("30Y", 10950, rate)
    }
}

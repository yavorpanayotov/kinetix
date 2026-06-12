package com.kinetix.notification.engine

import com.kinetix.notification.model.AlertType
import com.kinetix.notification.model.ComparisonOperator
import java.util.Locale
import kotlin.math.abs

/**
 * Human-readable alert message — the spec's `format_alert_message(rule, metric)`
 * (specs/alerts.allium:298). Alert text is the most prominent thing a trader
 * reads under stress, so values are rendered as formatted amounts (never raw
 * doubles or scientific notation) and enums become words.
 */
fun formatAlertMessage(
    ruleName: String,
    type: AlertType,
    operator: ComparisonOperator,
    threshold: Double,
    currentValue: Double,
    bookId: String,
): String {
    val metric = metricLabel(type)
    val comparison = when (operator) {
        ComparisonOperator.GREATER_THAN -> "above"
        ComparisonOperator.LESS_THAN -> "below"
        ComparisonOperator.EQUALS -> "at"
    }
    val current = formatValue(type, currentValue)
    val limit = formatValue(type, threshold)
    return "$ruleName: $metric $current $comparison $limit limit (book $bookId)"
}

private fun metricLabel(type: AlertType): String = when (type) {
    AlertType.VAR_BREACH -> "VaR"
    AlertType.PNL_THRESHOLD -> "P&L"
    AlertType.RISK_LIMIT -> "Risk limit"
    AlertType.DELTA_BREACH -> "Delta"
    AlertType.VEGA_BREACH -> "Vega"
    AlertType.CONCENTRATION -> "Concentration"
    AlertType.MARGIN_BREACH -> "Margin"
    AlertType.MARGIN_CALL -> "Margin call"
    AlertType.SETTLEMENT_FAILS -> "Settlement fails"
    AlertType.DATA_STALE, AlertType.DATA_STALENESS -> "Data staleness"
    AlertType.LIQUIDITY_CONCENTRATION -> "Liquidity concentration"
    AlertType.REGIME_CHANGE -> "Regime change"
    AlertType.FACTOR_CONCENTRATION -> "Factor concentration"
    AlertType.LIMIT_BREACH -> "Limit"
    AlertType.RISK_BUDGET_EXCEEDED -> "Risk budget"
}

/** Ratio-valued alert types whose metric lives in [0, 1] and reads as a percentage. */
private val RATIO_TYPES = setOf(
    AlertType.CONCENTRATION,
    AlertType.LIQUIDITY_CONCENTRATION,
    AlertType.FACTOR_CONCENTRATION,
)

private fun formatValue(type: AlertType, value: Double): String {
    if (type in RATIO_TYPES && abs(value) <= 1.0) {
        return String.format(Locale.US, "%.1f%%", value * 100)
    }
    val sign = if (value < 0) "-" else ""
    val magnitude = abs(value)
    return if (magnitude >= 1_000) {
        sign + "$" + String.format(Locale.US, "%,d", Math.round(magnitude))
    } else {
        sign + "$" + String.format(Locale.US, "%,.2f", magnitude)
    }
}

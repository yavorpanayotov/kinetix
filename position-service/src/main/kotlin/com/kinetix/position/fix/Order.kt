package com.kinetix.position.fix

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.Side
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Currency

data class Order(
    val orderId: String,
    val bookId: String,
    val instrumentId: String,
    val side: Side,
    val quantity: BigDecimal,
    val orderType: String,
    val limitPrice: BigDecimal?,
    val arrivalPrice: BigDecimal,
    val submittedAt: Instant,
    val status: OrderStatus,
    val riskCheckResult: String?,
    val riskCheckDetails: String?,
    val fixSessionId: String?,
    val assetClass: AssetClass = AssetClass.EQUITY,
    val currency: Currency = Currency.getInstance("USD"),
    val timeInForce: TimeInForce = TimeInForce.DAY,
    /**
     * Expiry timestamp for [TimeInForce.GTD] orders; null for every other TIF.
     * Validated at submit time (must be in the future, must be within the
     * venue's max-GTD horizon — see ADR-0035).
     */
    val expiresAt: Instant? = null,
    val fills: List<ExecutionFill> = emptyList(),
) {
    init {
        require((timeInForce == TimeInForce.GTD) == (expiresAt != null)) {
            "expiresAt must be set IFF timeInForce = GTD (got tif=$timeInForce, expiresAt=$expiresAt)"
        }
    }

    val isTerminal: Boolean
        get() = status.isTerminal

    val filledQuantity: BigDecimal
        get() = fills.fold(BigDecimal.ZERO) { acc, fill -> acc + fill.fillQty }

    val averageFillPrice: BigDecimal?
        get() {
            if (fills.isEmpty()) return null
            val totalValue = fills.fold(BigDecimal.ZERO) { acc, fill ->
                acc + (fill.fillPrice * fill.fillQty)
            }
            return if (filledQuantity.signum() == 0) null
            else totalValue.divide(filledQuantity, 10, RoundingMode.HALF_UP)
        }
}

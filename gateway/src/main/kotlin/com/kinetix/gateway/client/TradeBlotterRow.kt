package com.kinetix.gateway.client

import com.kinetix.common.model.Trade
import java.math.BigDecimal

/**
 * A trade as the blotter needs to render it: the booked [Trade] plus the
 * optional fill-state projection that the upstream position-service may
 * carry on the wire when a row originated from a working order.
 *
 * Trader-review P2 §21: real blotters distinguish
 * `WORKING / FILLED / PARTIAL / CANCELLED / REJECTED` and surface
 * `qtyFilled` / `qtyOpen` per row. The gateway forwards these verbatim
 * when present and derives a sensible default from [Trade.status] when
 * they're absent (booked LIVE/AMENDED trades are by definition FILLED).
 */
data class TradeBlotterRow(
    val trade: Trade,
    val fillStatus: String? = null,
    val qtyFilled: BigDecimal? = null,
    val qtyOpen: BigDecimal? = null,
)

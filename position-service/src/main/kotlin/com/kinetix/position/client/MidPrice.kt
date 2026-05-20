package com.kinetix.position.client

import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Server-captured current mid price for an instrument, sourced from price-service
 * at order-submission time (spec: `execution.allium` — `current_mid_price`).
 *
 * Carries both the price and the instant it was observed so the submission service
 * can enforce the [com.kinetix.position.fix.OrderSubmissionService] staleness
 * invariant (`ArrivalPriceCaptured`, `execution.allium`) unconditionally — arrival
 * price is never a caller input, so its freshness is always known.
 */
data class MidPrice(
    val price: BigDecimal,
    val currency: Currency,
    val observedAt: Instant,
)

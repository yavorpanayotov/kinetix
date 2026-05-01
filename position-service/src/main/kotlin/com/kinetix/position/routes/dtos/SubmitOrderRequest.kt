package com.kinetix.position.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class SubmitOrderRequest(
    val bookId: String,
    val instrumentId: String,
    val side: String,
    val quantity: String,
    val orderType: String,
    val limitPrice: String? = null,
    val arrivalPrice: String,
    val fixSessionId: String? = null,
    val assetClass: String = "EQUITY",
    val currency: String = "USD",
    /**
     * Optional ISO-8601 instant when the arrival price was observed. When supplied,
     * orders are rejected with 400 if the price is stale per
     * `OrderSubmissionService.ARRIVAL_PRICE_MAX_AGE_MS`. Spec: execution.allium
     * arrival-price staleness check.
     */
    val arrivalPriceTimestamp: String? = null,
    /**
     * FIX time-in-force. Defaults to DAY (industry norm) when absent. Must be one of
     * DAY / GTC / IOC / FOK / GTD. GTD requires [expiresAt] to be set; non-GTD must
     * not set [expiresAt].
     */
    val timeInForce: String = "DAY",
    /**
     * ISO-8601 instant when the order should auto-expire. Required when timeInForce
     * is GTD; ignored otherwise. Must be in the future at submit time. The venue's
     * max-GTD horizon (typically 90 days) caps how far ahead this can be.
     */
    val expiresAt: String? = null,
)

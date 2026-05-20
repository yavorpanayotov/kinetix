package com.kinetix.position.routes.dtos

import kotlinx.serialization.Serializable

/**
 * Order-submission request body.
 *
 * The arrival price is NOT a caller input — it is captured server-side from
 * price-service at submission time (spec: `execution.allium` —
 * `let arrival_price = current_mid_price(instrument_id)`). Any `arrivalPrice` /
 * `arrivalPriceTimestamp` fields sent by a client are ignored.
 */
@Serializable
data class SubmitOrderRequest(
    val bookId: String,
    val instrumentId: String,
    val side: String,
    val quantity: String,
    val orderType: String,
    val limitPrice: String? = null,
    val fixSessionId: String? = null,
    val assetClass: String = "EQUITY",
    val currency: String = "USD",
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
    /**
     * Required instrument-type code (e.g. CASH_EQUITY, EQUITY_OPTION). Persisted on
     * the order and propagated to the trade booked from each fill so positions and
     * trade history always carry a real Type. Must match an `InstrumentTypeCode` value.
     */
    val instrumentType: String,
)

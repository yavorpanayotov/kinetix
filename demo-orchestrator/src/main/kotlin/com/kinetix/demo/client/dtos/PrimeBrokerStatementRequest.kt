package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of `POST /api/v1/execution/reconciliation/{bookId}/statements`
 * exposed by `position-service` (see its `PrimeBrokerStatementRequest` DTO).
 *
 * The simulator submits a deliberately mismatched prime-broker statement for
 * a sampled fraction of trades so the server-side reconciliation produces at
 * least one break row, populating the Trades > Reconciliation subtab.
 */
@Serializable
data class PrimeBrokerStatementRequest(
    val bookId: String,
    val date: String,
    val positions: List<PrimeBrokerPositionDto>,
)

/** A single prime-broker position line. Numeric values stay as strings. */
@Serializable
data class PrimeBrokerPositionDto(
    val instrumentId: String,
    val quantity: String,
    val price: String,
)

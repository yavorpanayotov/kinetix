package com.kinetix.position.trader

import com.kinetix.common.model.TraderId

class UnknownTraderException(val traderId: TraderId) :
    IllegalArgumentException("Trader '${traderId.value}' is not registered")

/**
 * Resolves a [TraderId] against the trader registry held by
 * reference-data-service. Position-service rejects a [BookTradeCommand]
 * with [UnknownTraderException] (mapped to HTTP 422 / gRPC FAILED_PRECONDITION
 * upstream) when the id is unknown.
 *
 * Transport failures surface as [TraderLookupRpcException]; the booking
 * route layer should map those to 503 so a degraded reference-data does
 * not silently let unknown traders through.
 */
class TraderValidator(
    private val lookupClient: TraderLookupClient,
) {
    fun validate(traderId: TraderId) {
        if (!lookupClient.exists(traderId)) {
            throw UnknownTraderException(traderId)
        }
    }
}

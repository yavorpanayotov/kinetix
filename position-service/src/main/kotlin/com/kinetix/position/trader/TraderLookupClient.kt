package com.kinetix.position.trader

import com.kinetix.common.model.TraderId

/**
 * Look up a [TraderId] against the reference-data trader registry.
 *
 * Implementations either call reference-data-service over gRPC
 * ([GrpcTraderLookupClient]) or short-circuit for tests.
 */
interface TraderLookupClient {
    /**
     * @return true when the trader is known; false when reference-data
     *         responds NOT_FOUND.
     * @throws TraderLookupRpcException for transport / non-NOT_FOUND failures.
     */
    fun exists(traderId: TraderId): Boolean
}

class TraderLookupRpcException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

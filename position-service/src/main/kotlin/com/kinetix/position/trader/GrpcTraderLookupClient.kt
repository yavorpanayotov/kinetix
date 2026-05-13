package com.kinetix.position.trader

import com.kinetix.common.model.TraderId
import com.kinetix.proto.referencedata.GetTraderRequest
import com.kinetix.proto.referencedata.TraderLookupServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * gRPC client wrapping reference-data-service's TraderLookupService.
 *
 * NOT_FOUND from the server maps to `exists(...) = false`. Every other
 * Status is treated as transport failure and bubbles up as a
 * [TraderLookupRpcException] so the booking path can refuse to accept
 * the trade rather than silently let an unknown trader through.
 */
class GrpcTraderLookupClient(
    channel: ManagedChannel,
    private val rpcDeadlineMs: Long = 1_000L,
) : TraderLookupClient {

    private val logger = LoggerFactory.getLogger(GrpcTraderLookupClient::class.java)
    private val stub: TraderLookupServiceGrpc.TraderLookupServiceBlockingStub =
        TraderLookupServiceGrpc.newBlockingStub(channel)

    override fun exists(traderId: TraderId): Boolean {
        val request = GetTraderRequest.newBuilder().setTraderId(traderId.value).build()
        return try {
            stub.withDeadlineAfter(rpcDeadlineMs, TimeUnit.MILLISECONDS).getTrader(request)
            true
        } catch (e: StatusRuntimeException) {
            when (e.status.code) {
                Status.Code.NOT_FOUND -> false
                else -> {
                    logger.warn("TraderLookup RPC failed: traderId={} status={}", traderId.value, e.status)
                    throw TraderLookupRpcException(
                        "TraderLookup RPC failed for ${traderId.value}: ${e.status.code}",
                        e,
                    )
                }
            }
        }
    }
}

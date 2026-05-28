package com.kinetix.referencedata.grpc

import com.kinetix.common.model.DeskId
import com.kinetix.common.model.TraderId
import com.kinetix.proto.referencedata.GetTraderRequest
import com.kinetix.proto.referencedata.GetTraderResponse
import com.kinetix.proto.referencedata.ListTradersForDeskRequest
import com.kinetix.proto.referencedata.ListTradersForDeskResponse
import com.kinetix.proto.referencedata.TraderLookupServiceGrpc
import com.kinetix.referencedata.service.TraderService
import io.grpc.Status
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * gRPC implementation of [TraderLookupServiceGrpc.TraderLookupServiceImplBase].
 *
 * Position-service calls [getTrader] at trade-booking time to validate the
 * `traderId` on a `BookTradeCommand`; NOT_FOUND is a hard reject upstream.
 */
class TraderLookupServiceImpl(
    private val traderService: TraderService,
) : TraderLookupServiceGrpc.TraderLookupServiceImplBase() {

    private val logger = LoggerFactory.getLogger(TraderLookupServiceImpl::class.java)

    override fun getTrader(
        request: GetTraderRequest,
        responseObserver: StreamObserver<GetTraderResponse>,
    ) {
        val traderId = request.traderId
        if (traderId.isBlank()) {
            responseObserver.onError(
                Status.INVALID_ARGUMENT
                    .withDescription("trader_id must not be blank")
                    .asRuntimeException(),
            )
            return
        }
        val trader = try {
            runBlocking { traderService.findById(TraderId(traderId)) }
        } catch (e: Exception) {
            logger.error("getTrader failed for traderId={}", traderId, e)
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException())
            return
        }
        if (trader == null) {
            responseObserver.onError(
                Status.NOT_FOUND
                    .withDescription("Trader '$traderId' not found")
                    .asRuntimeException(),
            )
            return
        }
        responseObserver.onNext(
            GetTraderResponse.newBuilder()
                .setTraderId(trader.id.value)
                .setName(trader.name)
                .setDeskId(trader.deskId.value)
                .setEmail(trader.email ?: "")
                .setNotionalLimitUsd(trader.notionalLimitUsd?.toPlainString() ?: "")
                .build(),
        )
        responseObserver.onCompleted()
    }

    override fun listTradersForDesk(
        request: ListTradersForDeskRequest,
        responseObserver: StreamObserver<ListTradersForDeskResponse>,
    ) {
        val deskId = request.deskId
        if (deskId.isBlank()) {
            responseObserver.onError(
                Status.INVALID_ARGUMENT
                    .withDescription("desk_id must not be blank")
                    .asRuntimeException(),
            )
            return
        }
        val traders = try {
            runBlocking { traderService.findByDesk(DeskId(deskId)) }
        } catch (e: Exception) {
            logger.error("listTradersForDesk failed for deskId={}", deskId, e)
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException())
            return
        }
        val builder = ListTradersForDeskResponse.newBuilder()
        for (t in traders) {
            builder.addTraders(
                GetTraderResponse.newBuilder()
                    .setTraderId(t.id.value)
                    .setName(t.name)
                    .setDeskId(t.deskId.value)
                    .setEmail(t.email ?: "")
                    .setNotionalLimitUsd(t.notionalLimitUsd?.toPlainString() ?: "")
                    .build(),
            )
        }
        responseObserver.onNext(builder.build())
        responseObserver.onCompleted()
    }
}

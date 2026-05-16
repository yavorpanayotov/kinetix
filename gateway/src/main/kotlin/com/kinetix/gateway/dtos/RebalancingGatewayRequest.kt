package com.kinetix.gateway.dtos

import com.kinetix.gateway.client.RebalancingRequestParams
import com.kinetix.gateway.client.RebalancingTradeParam
import kotlinx.serialization.Serializable

@Serializable
data class RebalancingGatewayRequest(
    val trades: List<RebalancingGatewayTradeDto>,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
)

fun RebalancingGatewayRequest.toRebalancingParams(bookId: String): RebalancingRequestParams =
    RebalancingRequestParams(
        bookId = bookId,
        trades = trades.map {
            RebalancingTradeParam(
                instrumentId = it.instrumentId,
                assetClass = it.assetClass,
                side = it.side,
                quantity = it.quantity,
                priceAmount = it.priceAmount,
                priceCurrency = it.priceCurrency,
                instrumentType = it.instrumentType,
                bidAskSpreadBps = it.bidAskSpreadBps,
            )
        },
        calculationType = calculationType,
        confidenceLevel = confidenceLevel,
    )

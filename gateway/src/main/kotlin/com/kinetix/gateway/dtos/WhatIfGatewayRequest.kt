package com.kinetix.gateway.dtos

import com.kinetix.gateway.client.HypotheticalTradeParam
import com.kinetix.gateway.client.WhatIfRequestParams
import kotlinx.serialization.Serializable

@Serializable
data class WhatIfGatewayRequest(
    val hypotheticalTrades: List<WhatIfGatewayTradeDto>,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
)

fun WhatIfGatewayRequest.toParams(bookId: String): WhatIfRequestParams =
    WhatIfRequestParams(
        bookId = bookId,
        hypotheticalTrades = hypotheticalTrades.map {
            HypotheticalTradeParam(
                instrumentId = it.instrumentId,
                assetClass = it.assetClass,
                side = it.side,
                quantity = it.quantity,
                priceAmount = it.priceAmount,
                priceCurrency = it.priceCurrency,
                instrumentType = it.instrumentType,
            )
        },
        calculationType = calculationType,
        confidenceLevel = confidenceLevel,
    )

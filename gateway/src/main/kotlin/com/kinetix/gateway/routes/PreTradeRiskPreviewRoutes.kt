package com.kinetix.gateway.routes

import com.kinetix.gateway.client.HypotheticalTradeParam
import com.kinetix.gateway.client.RiskServiceClient
import com.kinetix.gateway.client.WhatIfRequestParams
import com.kinetix.gateway.client.WhatIfResultSummary
import com.kinetix.gateway.dtos.PreTradeRiskPreviewRequest
import com.kinetix.gateway.dtos.PreTradeRiskPreviewResponse
import io.github.smiley4.ktoropenapi.post
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Advisory pre-trade risk-impact preview shown on the UI's Place Order
 * ticket on form-blur (trader-review P2, ui-trader-review.md). Reuses the
 * existing What-If valuation path (risk.allium AnalyseWhatIf) — same
 * pricing flow, no parallel risk engine — and projects the upstream
 * response into a focused four-delta summary:
 *
 *   - Δ VaR        = hypothetical_var - base_var (from what-if)
 *   - Δ Delta      = sum across asset-class greeks of hypothetical - base
 *   - Δ Notional   = signed notional of the candidate trade
 *                    (BUY → positive, SELL → negative)
 *   - Δ Counterparty exposure = signed notional applied to the bilateral
 *                    counterparty; null for venue-routed / cleared orders.
 *
 * Books no order; persists nothing. Synchronous and interactive.
 *
 * Spec: execution.allium ComputePreTradeRiskPreview.
 */
fun Route.preTradeRiskPreviewRoutes(client: RiskServiceClient) {
    post("/api/v1/risk/pretrade-preview", {
        summary = "Compute risk-impact preview for a candidate order"
        tags = listOf("Risk", "Pre-Trade")
    }) {
        val request = call.receive<PreTradeRiskPreviewRequest>()
        val whatIfResult = client.runWhatIf(request.toWhatIfParams())
        call.respond(buildPreTradeRiskPreviewResponse(request, whatIfResult))
    }
}

private fun PreTradeRiskPreviewRequest.toWhatIfParams(): WhatIfRequestParams =
    WhatIfRequestParams(
        bookId = bookId,
        hypotheticalTrades = listOf(
            HypotheticalTradeParam(
                instrumentId = instrumentId,
                assetClass = assetClass,
                side = side,
                quantity = quantity,
                priceAmount = priceAmount,
                priceCurrency = priceCurrency,
                instrumentType = instrumentType,
            ),
        ),
        calculationType = null,
        confidenceLevel = null,
    )

internal fun buildPreTradeRiskPreviewResponse(
    request: PreTradeRiskPreviewRequest,
    result: WhatIfResultSummary,
): PreTradeRiskPreviewResponse {
    val baseDelta = result.baseGreeks?.assetClassGreeks?.sumOf { BigDecimal(it.delta) }
        ?: BigDecimal.ZERO
    val hypotheticalDelta = result.hypotheticalGreeks?.assetClassGreeks?.sumOf { BigDecimal(it.delta) }
        ?: BigDecimal.ZERO
    val deltaChange = hypotheticalDelta - baseDelta

    val notional = BigDecimal(request.quantity) * BigDecimal(request.priceAmount)
    val sideSign = when (request.side.uppercase()) {
        "BUY" -> BigDecimal.ONE
        "SELL" -> BigDecimal.ONE.negate()
        else -> BigDecimal.ZERO
    }
    val notionalChange = (notional * sideSign).setScale(2, RoundingMode.HALF_EVEN)

    // Δ counterparty exposure: bilateral OTC trades carry a counterparty;
    // exchange-cleared cash equities do not. When the field is absent we
    // emit explicit nulls rather than a misleading zero so the UI can
    // distinguish "no bilateral exposure" from "computed and zero".
    val counterpartyExposureChange = request.counterpartyId?.let { notionalChange }

    return PreTradeRiskPreviewResponse(
        baseVaR = result.baseVaR,
        hypotheticalVaR = result.hypotheticalVaR,
        varChange = result.varChange,
        baseDelta = baseDelta.setScale(6, RoundingMode.HALF_EVEN).toPlainString(),
        hypotheticalDelta = hypotheticalDelta.setScale(6, RoundingMode.HALF_EVEN).toPlainString(),
        deltaChange = deltaChange.setScale(6, RoundingMode.HALF_EVEN).toPlainString(),
        notionalChange = notionalChange.toPlainString(),
        counterpartyId = request.counterpartyId,
        counterpartyExposureChange = counterpartyExposureChange?.toPlainString(),
        calculatedAt = result.calculatedAt,
    )
}

package com.kinetix.gateway.dtos

import com.kinetix.gateway.client.PositionRiskSummaryItem
import kotlinx.serialization.Serializable

@Serializable
data class WhatIfGatewayPositionRiskDto(
    val instrumentId: String,
    val assetClass: String,
    val marketValue: String,
    val delta: String? = null,
    val gamma: String? = null,
    val vega: String? = null,
    // Per-instrument Theta / Rho carried end-to-end so the UI's
    // PositionRiskTable no longer renders `—` for options' time-decay
    // and rate-sensitivity columns (trader-review P0 #2).
    val theta: String? = null,
    val rho: String? = null,
    // DV01 (dollar value of a 1bp parallel rates shift). Surfaced for
    // FIXED_INCOME rows so a rates trader can size hedges; zero for
    // instruments where rate exposure doesn't apply (cash equity / FX).
    val dv01: String? = null,
    val varContribution: String,
    val esContribution: String,
    val percentageOfTotal: String,
)

fun PositionRiskSummaryItem.toPositionRiskResponse(): WhatIfGatewayPositionRiskDto =
    toWhatIfDto()

fun PositionRiskSummaryItem.toWhatIfDto(): WhatIfGatewayPositionRiskDto =
    WhatIfGatewayPositionRiskDto(
        instrumentId = instrumentId,
        assetClass = assetClass,
        marketValue = marketValue,
        delta = delta,
        gamma = gamma,
        vega = vega,
        theta = theta,
        rho = rho,
        dv01 = dv01,
        varContribution = varContribution,
        esContribution = esContribution,
        percentageOfTotal = percentageOfTotal,
    )

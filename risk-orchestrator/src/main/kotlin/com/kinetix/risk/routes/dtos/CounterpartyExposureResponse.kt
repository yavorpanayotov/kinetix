package com.kinetix.risk.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class CounterpartyExposureResponse(
    val counterpartyId: String,
    val calculatedAt: String,
    val currentNetExposure: Double,
    val peakPfe: Double,
    val pfeProfile: List<ExposureAtTenorResponse>,
    val cva: Double?,
    val cvaEstimated: Boolean,
    val currency: String,
    val nettingSetExposures: List<NettingSetExposureResponse> = emptyList(),
    val collateralHeld: Double = 0.0,
    val collateralPosted: Double = 0.0,
    val netNetExposure: Double? = null,
    val wrongWayRiskFlags: List<String> = emptyList(),
    val agreementStatus: String? = null,
)

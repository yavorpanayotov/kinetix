package com.kinetix.risk.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class FactorRiskResponse(
    val bookId: String,
    val calculatedAt: String,
    val totalVar: Double,
    val systematicVar: Double,
    val idiosyncraticVar: Double,
    val rSquared: Double,
    val concentrationWarning: Boolean,
    val factors: List<FactorContributionDto>,
)

@Serializable
data class FactorContributionDto(
    val factorType: String,
    val factorExposure: Double,
    val varContribution: Double,
    val pnlAttribution: Double,
    val pctOfTotal: Double,
    val loading: Double,
    val loadingMethod: String,
)

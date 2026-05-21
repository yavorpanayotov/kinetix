package com.kinetix.risk.model

data class FactorContribution(
    val factorType: String,
    val factorExposure: Double,
    val varContribution: Double,
    val pnlAttribution: Double,
    val pctOfTotal: Double,
    val loading: Double,
    val loadingMethod: String,
)

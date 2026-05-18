package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of `POST /api/v1/risk/budgets` exposed by `risk-orchestrator`
 * (see `RiskBudgetRoutes.kt`).
 *
 * Field names must match the upstream contract exactly so the JSON round-trips
 * on the wire. `budgetAmount` is serialised as a string to preserve precision
 * (the upstream parses it back via `toBigDecimalOrNull`).
 */
@Serializable
data class CreateRiskBudgetRequest(
    val entityLevel: String,
    val entityId: String,
    val budgetType: String,
    val budgetPeriod: String,
    val budgetAmount: String,
    val effectiveFrom: String,
    val allocatedBy: String,
)

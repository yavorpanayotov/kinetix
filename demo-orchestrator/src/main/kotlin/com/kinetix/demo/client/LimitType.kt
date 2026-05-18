package com.kinetix.demo.client

/**
 * Limit types the demo orchestrator can seed against risk-orchestrator.
 *
 * Maps onto the upstream `budgetType` field on the `risk-orchestrator`
 * `POST /api/v1/risk/budgets` endpoint — the demo seeds VaR (95%) and absolute
 * delta budgets at the BOOK level so the UI has populated utilisation gauges.
 */
enum class LimitType {
    VAR_95,
    DELTA_ABS,
}

package com.kinetix.demo.client.dtos

import java.math.BigDecimal

/**
 * Domain snapshot of a book's risk exposure read from the risk-orchestrator
 * hierarchy endpoint. Used by the demo orchestrator to size VaR/Delta budgets.
 *
 * The upstream `GET /api/v1/risk/hierarchy/{level}/{entityId}` route
 * (`HierarchyRiskRoutes.kt`) currently emits `varValue` (95% VaR) but does NOT
 * surface an absolute-delta aggregate. We model [absoluteDelta] as nullable
 * until that field — or a sibling endpoint that returns it — is wired up; the
 * demo will fall back to a default ratio when null.
 */
data class BookExposureSnapshot(
    val bookId: String,
    val varValue: BigDecimal,
    val absoluteDelta: BigDecimal?,
)

package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /api/v1/risk/hierarchy/{level}/{entityId}` exposed by
 * `risk-orchestrator` (see `HierarchyRiskRoutes.kt` /
 * `HierarchyNodeRiskResponse.kt`). All numeric fields are emitted as formatted
 * strings (`%.2f`, `%.6f`) — the demo client parses them back into BigDecimal.
 *
 * Only the fields the demo seeder needs are non-nullable; the rest are kept
 * nullable so an upstream contract evolution does not break decoding.
 */
@Serializable
data class HierarchyRiskResponse(
    val level: String,
    val entityId: String,
    val entityName: String? = null,
    val parentId: String? = null,
    val varValue: String,
    val expectedShortfall: String? = null,
    val pnlToday: String? = null,
    val limitUtilisation: String? = null,
    val marginalVar: String? = null,
    val incrementalVar: String? = null,
    val childCount: Int? = null,
    val isPartial: Boolean? = null,
    val generatedAt: String? = null,
)

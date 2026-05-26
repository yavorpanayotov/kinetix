package com.kinetix.demo.client.dtos

import kotlinx.serialization.Serializable

/**
 * Subset of `position-service`'s `StrategyResponse` consumed by the demo
 * orchestrator when discovering the strategies seeded for a book.
 *
 * Only [strategyId] is required for trade routing; [name] is kept so we can
 * log a human-readable strategy when a wire-level failure surfaces.
 */
@Serializable
data class StrategyListItemResponse(
    val strategyId: String,
    val bookId: String? = null,
    val strategyType: String? = null,
    val name: String? = null,
    val createdAt: String? = null,
)

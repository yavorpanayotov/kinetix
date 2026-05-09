package com.kinetix.gateway.dtos

import kotlinx.serialization.Serializable

@Serializable
data class TradeHistoryPageResponse(
    val items: List<TradeResponse>,
    val total: Long,
    val offset: Long,
    val limit: Int,
    val hasMore: Boolean,
)

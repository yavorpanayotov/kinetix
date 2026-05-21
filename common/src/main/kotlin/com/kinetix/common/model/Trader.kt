package com.kinetix.common.model

import java.math.BigDecimal
import java.time.Instant

data class Trader(
    val id: TraderId,
    val name: String,
    val deskId: DeskId,
    val email: String? = null,
    val notionalLimitUsd: BigDecimal? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
) {
    init {
        require(name.isNotBlank()) { "Trader name must not be blank" }
        require(notionalLimitUsd == null || notionalLimitUsd >= BigDecimal.ZERO) {
            "Trader notionalLimitUsd must be non-negative, was $notionalLimitUsd"
        }
    }
}

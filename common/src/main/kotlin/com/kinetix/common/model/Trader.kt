package com.kinetix.common.model

import java.math.BigDecimal

data class Trader(
    val id: TraderId,
    val name: String,
    val deskId: DeskId,
    val email: String? = null,
    val notionalLimitUsd: BigDecimal? = null,
) {
    init {
        require(name.isNotBlank()) { "Trader name must not be blank" }
        require(notionalLimitUsd == null || notionalLimitUsd >= BigDecimal.ZERO) {
            "Trader notionalLimitUsd must be non-negative, was $notionalLimitUsd"
        }
    }
}

package com.kinetix.referencedata.routes.dtos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateTraderRequest(
    @SerialName("trader_id") val traderId: String,
    val name: String,
    val deskId: String,
    val email: String? = null,
    val notionalLimitUsd: String? = null,
)

package com.kinetix.referencedata.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class CreateTraderRequest(
    val id: String,
    val name: String,
    val deskId: String,
    val email: String? = null,
    val notionalLimitUsd: String? = null,
)

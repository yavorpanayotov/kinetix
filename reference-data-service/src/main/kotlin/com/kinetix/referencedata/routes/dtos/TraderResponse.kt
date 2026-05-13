package com.kinetix.referencedata.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class TraderResponse(
    val id: String,
    val name: String,
    val deskId: String,
    val email: String? = null,
    val notionalLimitUsd: String? = null,
)

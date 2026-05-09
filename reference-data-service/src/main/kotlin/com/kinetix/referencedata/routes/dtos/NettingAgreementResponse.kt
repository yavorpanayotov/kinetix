package com.kinetix.referencedata.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class NettingAgreementResponse(
    val nettingSetId: String,
    val counterpartyId: String,
    val agreementType: String,
    val closeOutNetting: Boolean,
    val csaThreshold: Double?,
    val currency: String?,
    val createdAt: String,
    val updatedAt: String,
    val expiryDate: String? = null,
    val agreementStatus: String,
)

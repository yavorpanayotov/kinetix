package com.kinetix.referencedata.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class UpsertNettingAgreementRequest(
    val nettingSetId: String,
    val counterpartyId: String,
    val agreementType: String = "ISDA_2002",
    val closeOutNetting: Boolean = true,
    val csaThreshold: Double? = null,
    val currency: String? = null,
    val expiryDate: String? = null,
)

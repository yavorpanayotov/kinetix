package com.kinetix.risk.client.dtos

import kotlinx.serialization.Serializable

@Serializable
data class CounterpartyDto(
    val counterpartyId: String,
    val legalName: String,
    val shortName: String? = null,
    val lei: String? = null,
    val ratingSp: String? = null,
    val ratingMoodys: String? = null,
    val ratingFitch: String? = null,
    val sector: String,
    val country: String? = null,
    val isFinancial: Boolean = false,
    val pd1y: Double? = null,
    val lgd: Double = 0.4,
    val cdsSpreadBps: Double? = null,
)

@Serializable
data class NettingAgreementDto(
    val nettingSetId: String,
    val counterpartyId: String,
    val agreementType: String,
    val closeOutNetting: Boolean,
    val csaThreshold: Double? = null,
    val currency: String,
    val expiryDate: String? = null,
    val agreementStatus: String = "ACTIVE",
)

package com.kinetix.referencedata.model

import java.math.BigDecimal
import java.time.Instant

data class NettingAgreement(
    val nettingSetId: String,
    val counterpartyId: String,
    val agreementType: String,
    val closeOutNetting: Boolean,
    val csaThreshold: BigDecimal?,
    val currency: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiryDate: Instant? = null,
)

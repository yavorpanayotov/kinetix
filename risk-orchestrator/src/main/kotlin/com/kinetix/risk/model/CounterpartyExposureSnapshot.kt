package com.kinetix.risk.model

import java.time.Instant

data class ExposureAtTenor(
    val tenor: String,
    val tenorYears: Double,
    val expectedExposure: Double,
    val pfe95: Double,
    val pfe99: Double,
)

data class NettingSetExposure(
    val nettingSetId: String,
    val agreementType: String,
    val netExposure: Double,
    val peakPfe: Double,
)

data class CounterpartyExposureSnapshot(
    val id: Long? = null,
    val counterpartyId: String,
    val calculatedAt: Instant,
    val pfeProfile: List<ExposureAtTenor>,
    val currentNetExposure: Double,
    val peakPfe: Double,
    val cva: Double?,
    val cvaEstimated: Boolean,
    val currency: String = "USD",

    // Per-netting-set breakdown
    val nettingSetExposures: List<NettingSetExposure>? = null,

    // Collateral values
    val collateralHeld: Double = 0.0,
    val collateralPosted: Double = 0.0,

    // Net-net exposure: netExposure - collateralHeld + collateralPosted
    val netNetExposure: Double? = null,

    // Wrong-way risk flags; null when not computed, empty when clean
    val wrongWayRiskFlags: List<String>? = null,

    // Live overlay from reference-data (not persisted on the snapshot row).
    // ACTIVE / EXPIRED / SUSPENDED, or null when no agreement is associated.
    val agreementStatus: String? = null,
)

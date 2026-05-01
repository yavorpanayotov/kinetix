package com.kinetix.risk.service

/**
 * Sector taxonomy for wrong-way risk (WWR) detection.
 *
 * Counterparty- and position-side sector strings come from reference data and
 * the position pipeline respectively, with no enforced enum on either side.
 * Both are normalised through [fromSector] into a small set of WWR groups so
 * the [WrongWayRiskSectorMatch] invariant in `specs/counterparty-risk.allium`
 * can be checked with a simple group equality.
 *
 * The flag fires only when the counterparty group equals the position group
 * AND the group is not [OTHER]. The OTHER guard is deliberate: when both
 * sides map to OTHER, no specific WWR is identifiable, and per Basel CRE54
 * a WWR flag should not fire without a traceable structural correlation.
 */
enum class WrongWayRiskSectorGroup {
    FINANCIALS,
    SOVEREIGN,
    ENERGY_UTILITIES,
    REAL_ESTATE,
    OTHER;

    companion object {
        fun fromSector(sector: String?): WrongWayRiskSectorGroup {
            if (sector.isNullOrBlank()) return OTHER
            val normalised = sector.uppercase().trim()
            return when (normalised) {
                "BANK", "BANKS", "BANKING",
                "BROKER_DEALER", "BROKER", "DEALER", "BROKERAGE",
                "INSURANCE", "INSURER",
                "ASSET_MANAGER", "ASSET MANAGEMENT", "ASSET MANAGER",
                "FINANCIAL", "FINANCIALS", "FINANCIAL_OTHER", "FINANCIAL SERVICES",
                -> FINANCIALS

                "SOVEREIGN", "GOVERNMENT", "SOVEREIGNS",
                "SUPRANATIONAL", "CENTRAL_BANK", "CENTRAL BANK",
                -> SOVEREIGN

                "ENERGY", "OIL", "OIL_GAS", "OIL & GAS", "GAS",
                "UTILITY", "UTILITIES",
                -> ENERGY_UTILITIES

                "REAL_ESTATE", "REAL ESTATE", "REIT", "REITS", "PROPERTY",
                -> REAL_ESTATE

                else -> OTHER
            }
        }
    }
}

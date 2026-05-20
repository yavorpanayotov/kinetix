package com.kinetix.common.model

import kotlinx.serialization.Serializable

/**
 * Canonical bond seniority ranking shared across all Kinetix services.
 *
 * Declared in `core.allium` as
 * `enum BondSeniority { senior_secured | senior_unsecured | subordinated }`.
 */
@Serializable
enum class BondSeniority {
    SENIOR_SECURED,
    SENIOR_UNSECURED,
    SUBORDINATED,
}

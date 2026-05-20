package com.kinetix.common.model

import kotlinx.serialization.Serializable

/**
 * Canonical option payoff direction shared across all Kinetix services.
 *
 * Declared in `core.allium` as `enum OptionType { call | put }`. Matches the
 * Python risk-engine `OptionType` (`risk-engine/src/kinetix_risk/models.py`) so
 * both sides of the gRPC contract agree on the same member set.
 */
@Serializable
enum class OptionType {
    CALL,
    PUT,
}

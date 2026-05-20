package com.kinetix.common.model

import kotlinx.serialization.Serializable

/**
 * Canonical interest-rate-swap leg direction shared across all Kinetix services.
 *
 * Declared in `core.allium` as
 * `enum SwapDirection { pay_fixed | receive_fixed }`.
 */
@Serializable
enum class SwapDirection {
    PAY_FIXED,
    RECEIVE_FIXED,
}

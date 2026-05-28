package com.kinetix.position.limit

/** Outcome of [checkPositionLimit]. */
sealed interface LimitCheck {
    data object WithinLimit : LimitCheck
    data class Breach(val overage: Long) : LimitCheck
}

/**
 * Check whether a position [quantity] respects the configured [limit].
 *
 * Convention: the limit value itself is INCLUSIVE — a 1,000,000-share
 * limit allows a 1,000,000-share position. One additional share is
 * the first breach, with overage = 1.
 *
 * @throws IllegalArgumentException if [limit] is negative.
 */
fun checkPositionLimit(quantity: Long, limit: Long): LimitCheck {
    require(limit >= 0L) { "limit must be non-negative (got $limit)" }
    return if (quantity <= limit) {
        LimitCheck.WithinLimit
    } else {
        LimitCheck.Breach(overage = quantity - limit)
    }
}

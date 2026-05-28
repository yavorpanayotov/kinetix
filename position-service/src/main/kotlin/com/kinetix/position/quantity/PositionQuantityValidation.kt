package com.kinetix.position.quantity

/**
 * Validate that a [quantity] for a [PositionQuantity] field is
 * non-negative.
 *
 * PositionQuantity carries SIZE (absolute magnitude). The long/short
 * direction is on the surrounding domain type, so a negative
 * quantity here would conflict with the direction field and produce
 * ambiguous P&L attribution downstream. Zero is allowed — a flat
 * position (size 0) is the legitimate result of a closing trade.
 *
 * @throws IllegalArgumentException for a negative quantity.
 */
fun validatePositionQuantity(quantity: Long): Long {
    require(quantity >= 0L) {
        "PositionQuantity must be non-negative (got $quantity); use the direction field for long/short"
    }
    return quantity
}

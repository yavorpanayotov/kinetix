package com.kinetix.position.limit

/**
 * Type of position limit. Determines how exposure is aggregated
 * before being compared against the limit value.
 *
 *   - GROSS — sum of `|notional|` across positions.
 *   - NET — signed sum of notional (longs cancel shorts).
 *   - BY_COUNTERPARTY — slice the book by counterparty, apply per
 *     slice.
 *   - BY_SECTOR — slice by GICS sector, apply per slice.
 */
enum class LimitType {
    GROSS,
    NET,
    BY_COUNTERPARTY,
    BY_SECTOR;

    /** True if the limit needs an upstream grouping step before checking. */
    fun requiresGrouping(): Boolean = this == BY_COUNTERPARTY || this == BY_SECTOR

    companion object {
        /** Parse a string into a [LimitType]; case-insensitive. */
        fun parse(input: String): LimitType {
            val upper = input.uppercase()
            return entries.firstOrNull { it.name == upper }
                ?: throw IllegalArgumentException(
                    "unknown LimitType '$input' (supported: ${entries.joinToString { it.name }})",
                )
        }
    }
}

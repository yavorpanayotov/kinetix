// NOTE: This package (com.kinetix.position.fix) is intended for future extraction
// into a standalone fix-gateway service per ADR-0035.

package com.kinetix.position.fix

/**
 * FIX-tag-59 time-in-force values supported by the platform. Drives the
 * `ScheduledOrderExpirySweeper` (per ADR-0035 / audit A-13):
 *
 * - [DAY]: order expires at the venue's session-close cutoff. The sweeper
 *   transitions OPEN/PARTIAL DAY orders to [OrderStatus.EXPIRED] when the
 *   venue-cutoff has passed.
 * - [GTC]: stays alive until filled or manually cancelled. Existing orders
 *   migrated to this value (V22 backfill) so the rollout never auto-expires
 *   live state.
 * - [GTD]: stays alive until [Order.expiresAt], at which point the sweeper
 *   transitions it to EXPIRED. Validation requires expiresAt > now and
 *   <= the venue's max-GTD horizon.
 * - [IOC] / [FOK]: handled at submission time at the venue, never long-lived.
 *   Included in the enum for FIX-protocol completeness.
 */
enum class TimeInForce {
    DAY,
    GTC,
    IOC,
    FOK,
    GTD;
}

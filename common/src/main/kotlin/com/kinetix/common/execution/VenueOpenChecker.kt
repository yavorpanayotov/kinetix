package com.kinetix.common.execution

import java.time.Instant

/**
 * Resolves whether a venue's regular trading session is currently open. Per ADR-0035
 * phase 2, fix-gateway is the sole owner of cutoff data; position-service consumes it
 * via the `IsVenueOpen` gRPC RPC through the `GrpcVenueOpenChecker` adapter. The
 * `ScheduledOrderExpirySweeper` depends on this interface so it can be swapped for
 * fakes in tests and for an in-process registry in dev-mode (`FIX_GATEWAY_ENABLED=false`).
 *
 * The contract is "best effort": when fix-gateway is unreachable the adapter falls
 * back to "always open" + emits `venue_cutoff_check_failed_total{venue}`, matching the
 * existing degraded-routing failure mode.
 */
fun interface VenueOpenChecker {
    /** True when [venue] is open at instant [at]. */
    fun isOpen(venue: String, at: Instant): Boolean
}

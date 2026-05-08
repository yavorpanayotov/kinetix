package com.kinetix.fix.session

/**
 * Persistence boundary for `fix_message_log` — the append-only audit and replay source
 * for all inbound/outbound FIX traffic on fix-gateway (ADR-0035 §2.6).
 *
 * The reconciliation query on [findOpenClOrdIds] is the primary read path used by
 * [SessionReconciliationCoordinator] on logon to determine which clOrdIDs need a
 * status-request (35=H) sent to the venue.
 */
interface FixMessageLogRepository {

    /**
     * Record an outbound FIX message in the log. The row is inserted with [orderStatus] = OPEN
     * for 35=D messages so it appears in subsequent [findOpenClOrdIds] queries until a terminal
     * 35=8 or 35=9 arrives.
     *
     * @param entry  The message entry to persist.
     */
    suspend fun insert(entry: FixMessageLogEntry)

    /**
     * Mark all rows for [venue] + [clOrdId] as terminal (order_status = TERMINAL). Called when
     * an inbound 35=8 with a terminal ExecType (FILL, CANCELLED, REJECTED) or a 35=9
     * (OrderCancelReject) arrives for the given clOrdID.
     */
    suspend fun markTerminal(venue: String, clOrdId: String)

    /**
     * Return the clOrdIDs of outbound 35=D (NewOrderSingle) messages logged for [venue] within
     * the last [withinHours] hours whose [orderStatus] is still OPEN.
     *
     * Used by [SessionReconciliationCoordinator] on session logon to build the list of
     * outstanding orders for which `OrderStatusRequest` (35=H) messages must be sent.
     */
    suspend fun findOpenClOrdIds(venue: String, withinHours: Int = 24): List<String>
}

package com.kinetix.position.fix

interface ExecutionOrderRepository {
    suspend fun save(order: Order)
    suspend fun updateStatus(orderId: String, status: OrderStatus, riskCheckResult: String? = null, riskCheckDetails: String? = null)
    suspend fun updateQuantityAndPrice(orderId: String, quantity: java.math.BigDecimal, limitPrice: java.math.BigDecimal?)

    /**
     * Persist the FIX tag 37 OrderID assigned by the venue at PENDING_NEW (ADR-0035 phase 4).
     * Called by `OrderSubmissionService` after a successful `FixGatewayClient.placeOrder`.
     */
    suspend fun setVenueOrderId(orderId: String, venueOrderId: String)
    suspend fun findById(orderId: String): Order?
    suspend fun findByBookId(bookId: String): List<Order>

    /**
     * Returns all DAY and GTD orders in non-terminal status — the candidates the
     * `ScheduledOrderExpirySweeper` evaluates for venue-cutoff or GTD-expiry. The query
     * intentionally returns more than the sweeper will actually expire (the time-based
     * filter is applied per-order in the sweeper, not in SQL, because venue cutoffs
     * depend on per-venue local-time-zone arithmetic that doesn't translate cleanly to
     * a Postgres predicate).
     */
    suspend fun findOpenDayAndGtdOrders(): List<Order>
}

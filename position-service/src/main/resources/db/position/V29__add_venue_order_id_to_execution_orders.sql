-- ADR-0035 phase 4: persist FIX tag 37 OrderID assigned by the venue.
--
-- The PlaceOrder gRPC flow returns the venue's OrderID on PENDING_NEW; the
-- ScheduledOrderExpirySweeper then hands it to fix-gateway when issuing
-- 35=F OrderCancelRequests (most venues require tag 37 to identify the order).
-- Nullable because pre-phase-4 rows never had a venueOrderId, and orders that
-- never reach PENDING_NEW (REJECTED at the venue, PENDING_FAILED, in-flight
-- PENDING_RISK_CHECK) legitimately have no value to record.
ALTER TABLE execution_orders
    ADD COLUMN venue_order_id VARCHAR(255);

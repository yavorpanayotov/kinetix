-- ADR-0035 phase 4: introduce a CHECK constraint on execution_orders.status
-- and admit the new PENDING_FAILED value.
--
-- The Kotlin enum com.kinetix.position.fix.OrderStatus has always been the
-- de-facto authority for this column; phase 4 adds PENDING_FAILED for orders
-- whose routing through fix-gateway failed (FIX session down or gRPC deadline
-- exceeded). A defensive CHECK constraint binds the enum to the schema so a
-- buggy column write surfaces as a constraint violation rather than as silent
-- corruption — and so the migration ordering rule documented in the deploy
-- runbook (V28 must precede any binary that writes PENDING_FAILED) has a
-- visible enforcement point.
--
-- Adding a new status in the future requires a follow-up migration to relax
-- this check.
ALTER TABLE execution_orders
    ADD CONSTRAINT chk_execution_orders_status CHECK (status IN (
        'PENDING_RISK_CHECK',
        'APPROVED',
        'REJECTED',
        'SENT',
        'PARTIAL',
        'FILLED',
        'CANCELLED',
        'EXPIRED',
        'PENDING_FAILED'
    ));

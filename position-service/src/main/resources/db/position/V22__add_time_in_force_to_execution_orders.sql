-- V22: Adds TIF (FIX tag 59) and GTD expires_at to execution_orders.
--
-- Per ADR-0035 (fix-gateway service extraction) and audit A-13
-- (ExpireDayOrder): every order needs an explicit time-in-force so the
-- scheduled sweeper knows which orders to expire at venue close.
--
-- Backfill: existing rows default to GTC (good 'til cancelled). The platform
-- has been treating every order as effectively GTC-undecided to date, and
-- flipping them to DAY retroactively would auto-expire live orders on the
-- next sweeper tick. New orders default to DAY at the API layer (industry
-- norm) but the column-level default here is GTC to make the backfill safe.
--
-- expires_at is nullable; non-null only when time_in_force = GTD. The
-- check constraint is enforced in the application layer (Kotlin Order
-- model) rather than the DB so we keep migrations transaction-safe and
-- avoid late surprises during rollout.

ALTER TABLE execution_orders
    ADD COLUMN time_in_force VARCHAR(10) NOT NULL DEFAULT 'GTC',
    ADD COLUMN expires_at    TIMESTAMPTZ;

ALTER TABLE execution_orders
    ADD CONSTRAINT chk_execution_orders_tif
        CHECK (time_in_force IN ('DAY', 'GTC', 'IOC', 'FOK', 'GTD'));

-- The sweeper queries by (status, time_in_force) in the hot path; an index
-- keeps it fast as the orders table grows.
CREATE INDEX idx_execution_orders_status_tif
    ON execution_orders (status, time_in_force);

-- For GTD orders the sweeper also queries by expires_at; partial index
-- only covers rows where expires_at is not null.
CREATE INDEX idx_execution_orders_expires_at
    ON execution_orders (expires_at)
    WHERE expires_at IS NOT NULL;

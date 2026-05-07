-- ADR-0035 phase 2: cancel_attempts persistence.
--
-- Records every outbound cancel emission outcome so the ghost-fill alerter
-- (FIXExecutionReportProcessor) can correlate EXPIRED orders that received
-- fills against failed-cancel attempts. Append-only; one row per cancel call,
-- not one per order.
--
-- The status column mirrors com.kinetix.position.fix.CancelAttemptStatus —
-- the CHECK constraint enforces the bounded vocabulary so a metric label
-- explosion can never arise from a buggy enum extension. Adding a new status
-- requires a follow-up migration to relax this check.
CREATE TABLE IF NOT EXISTS cancel_attempts (
    id              BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(255) NOT NULL,
    venue           VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    detail          TEXT         NOT NULL DEFAULT '',
    attempted_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_cancel_attempts_status CHECK (status IN (
        'ACCEPTED', 'SESSION_DOWN', 'UNKNOWN_VENUE', 'INVALID_REQUEST', 'RPC_FAILED'
    ))
);

-- Hot-path query: "given an EXPIRED order, has any cancel attempt been
-- recorded for it (and what was the outcome)?" — drives the ghost-fill
-- alerter's correlation lookup.
CREATE INDEX IF NOT EXISTS idx_cancel_attempts_order_id
    ON cancel_attempts (order_id, attempted_at DESC);

-- Ops-side query: "what's the cancel-attempt rate by venue + outcome over
-- the last hour?" — drives the cancel_failed_total metric reconciliation.
CREATE INDEX IF NOT EXISTS idx_cancel_attempts_venue_status_at
    ON cancel_attempts (venue, status, attempted_at DESC);

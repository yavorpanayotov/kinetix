-- Indexes supporting the support-facing audit query API (PR 4):
-- "show all events for trade X / type Y / last hour".
-- Plain CREATE INDEX (no CONCURRENTLY) — Flyway runs migrations inside a transaction.

-- Support trade-scoped lookups: WHERE trade_id = ?
-- (idx_audit_events_trade_id was created in V1; kept idempotent here.)
CREATE INDEX IF NOT EXISTS idx_audit_events_trade_id
    ON audit_events (trade_id);

-- Support event-type + time-window queries:
-- WHERE event_type = ? AND received_at >= ? ORDER BY received_at DESC
CREATE INDEX IF NOT EXISTS idx_audit_events_event_type_received_at
    ON audit_events (event_type, received_at DESC);

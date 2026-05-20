-- AI v2 plan §6: breach history for the morning brief.
-- Each row records a single limit breach event — when an entity's measured
-- value (position, notional, VaR, concentration, etc.) crossed its cap.
-- Rows are append-only when a breach opens; `resolved_at` is filled in later
-- when the breach clears, and stays NULL while the breach is still open.
--
-- This table powers the `get_recent_breaches` MCP tool (see plans/ai-v2.md
-- §6.3), which the morning brief queries for "what breached overnight".
--
-- The composite indexes support the two hot queries:
--   * (book_id, breached_at DESC)  — "recent breaches for this book, newest first"
--   * (severity, breached_at DESC) — "recent HARD breaches across the platform"
CREATE TABLE IF NOT EXISTS limit_breach_events (
    id              UUID         PRIMARY KEY,
    entity_id       TEXT         NOT NULL,
    book_id         TEXT         NOT NULL,
    limit_type      TEXT         NOT NULL,
    severity        TEXT         NOT NULL,
    current_value   NUMERIC      NOT NULL,
    limit_value     NUMERIC      NOT NULL,
    breached_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_limit_breach_events_book
    ON limit_breach_events (book_id, breached_at DESC);

CREATE INDEX IF NOT EXISTS idx_limit_breach_events_severity
    ON limit_breach_events (severity, breached_at DESC);

-- V69: Persist copilot alert thresholds — the configurable trigger levels that
-- power the intraday-push threshold evaluator (see plans/ai-v2.md §7.2).
--
-- Each row defines, for a given scope (GLOBAL platform-wide default, a specific
-- BOOK, or a specific USER), the value at which an alert_type fires and the
-- cooldown that suppresses repeat alerts of the same type within the window.
-- A GLOBAL row has scope_id NULL; BOOK / USER rows carry the book id or user id.

CREATE TABLE IF NOT EXISTS copilot_alert_thresholds (
    id               UUID          PRIMARY KEY,
    scope_type       TEXT          NOT NULL
        CHECK (scope_type IN ('GLOBAL', 'BOOK', 'USER')),
    scope_id         TEXT,                          -- NULL for GLOBAL; book/user id otherwise
    alert_type       TEXT          NOT NULL,
    threshold_value  NUMERIC       NOT NULL,
    cooldown_minutes INTEGER       NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_copilot_alert_thresholds_scope
    ON copilot_alert_thresholds (scope_type, scope_id, alert_type);

-- Seed the ten GLOBAL platform-wide defaults. Stable literal UUIDs keep the
-- seed deterministic; ON CONFLICT (id) makes the INSERT safe to re-run.
INSERT INTO copilot_alert_thresholds
    (id, scope_type, scope_id, alert_type, threshold_value, cooldown_minutes)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'GLOBAL', NULL, 'VAR_BREACH',            5.0,        30),
    ('00000000-0000-0000-0000-000000000002', 'GLOBAL', NULL, 'POSITION_DELTA',        500000,     30),
    ('00000000-0000-0000-0000-000000000003', 'GLOBAL', NULL, 'VOL_INVERSION',         2.0,        30),
    ('00000000-0000-0000-0000-000000000004', 'GLOBAL', NULL, 'GAMMA_CONCENTRATION',   15.0,       30),
    ('00000000-0000-0000-0000-000000000005', 'GLOBAL', NULL, 'LIMIT_UTILISATION',     80.0,       15),
    ('00000000-0000-0000-0000-000000000006', 'GLOBAL', NULL, 'COUNTERPARTY_EXPOSURE', 10000000,   30),
    ('00000000-0000-0000-0000-000000000007', 'GLOBAL', NULL, 'PRICE_MOVE',            3.0,        15),
    ('00000000-0000-0000-0000-000000000008', 'GLOBAL', NULL, 'UNEXPLAINED_PNL',       100000,     30),
    ('00000000-0000-0000-0000-000000000009', 'GLOBAL', NULL, 'REGIME_CHANGE',         1.0,        60),
    ('00000000-0000-0000-0000-00000000000a', 'GLOBAL', NULL, 'DIVERSIFICATION',       20.0,       30)
ON CONFLICT (id) DO NOTHING;

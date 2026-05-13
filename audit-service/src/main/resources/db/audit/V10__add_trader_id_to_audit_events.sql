-- Adds trader_id to audit_events alongside the existing user_id /
-- user_role identity fields. trader_id captures the ref-data trader
-- that owns the ticket (demo Phase 2 Gap 6); user_id stays the
-- authenticated principal that submitted the action.
--
-- Hash chain impact: AuditHasher.computeHash now folds trader_id into
-- the digest input. Pre-migration events were hashed without the field
-- so they hash with `NULL` in the new position; the chain stays valid
-- because the change is append-only.

ALTER TABLE audit_events
    ADD COLUMN trader_id VARCHAR(64);

CREATE INDEX idx_audit_events_trader ON audit_events (trader_id);

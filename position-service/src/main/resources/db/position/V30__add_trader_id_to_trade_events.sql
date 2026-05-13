-- Adds trader_id to trade_events. Nullable for now so existing rows
-- (which pre-date the trader registry) remain valid; a follow-up
-- migration will flip to NOT NULL once the booking path always supplies
-- a trader id.
--
-- An index on trader_id supports per-trader P&L / blotter filtering
-- (Phase 1 Gap 3 / demo-review.md Gap 6).

ALTER TABLE trade_events
    ADD COLUMN trader_id VARCHAR(64);

CREATE INDEX idx_trade_events_trader ON trade_events (trader_id);

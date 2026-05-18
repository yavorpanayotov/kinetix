-- Snooze support: a future timestamp during which the alert's underlying rule
-- should not re-fire. NULL means the alert is not snoozed.
ALTER TABLE alert_events ADD COLUMN snoozed_until TIMESTAMPTZ;

-- Index for the evaluator's "skip if snoozed" guard: quickly filter active alerts
-- whose snooze has not yet expired.
CREATE INDEX idx_alert_events_snoozed_until ON alert_events (snoozed_until) WHERE snoozed_until IS NOT NULL;

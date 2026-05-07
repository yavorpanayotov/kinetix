-- V25: Add instrument_type to execution_orders so the FIX fill path can carry the
-- order's instrument type onto the booked trade. Without this column, every trade
-- booked via FIXExecutionReportProcessor was created with a null instrument type,
-- which displayed as "—" in the positions/trades UI.
--
-- Nullable: legacy orders (and orders submitted without an instrument_type) carry
-- null. The trade still books successfully; the position falls back to the
-- reference-data-service lookup at the gateway enrichment layer.

ALTER TABLE execution_orders
    ADD COLUMN instrument_type VARCHAR(50);

-- V70: Add `is_required` to run_manifest_market_data so the EOD-promotion gate
-- can distinguish required-MISSING (a real data gap that should block) from
-- optional-MISSING (best-effort feeds the engine already tolerates).
--
-- Legacy rows captured before this column existed don't carry the requiredness
-- flag — defaulting to TRUE preserves the previous strict behaviour for them
-- and matches the safe assumption that anything we asked for was required.

ALTER TABLE run_manifest_market_data
    ADD COLUMN is_required BOOLEAN NOT NULL DEFAULT TRUE;

-- V68: Add instrument_type to run_position_snapshots so replays preserve the
-- non-null instrument-type that the rest of the platform now requires.
--
-- The column is NOT NULL with no default. New snapshots must populate it
-- explicitly from the position they capture; legacy rows do not exist in
-- production yet so a backfill is unnecessary, but if any did the migration
-- would surface them as NOT NULL violations rather than hiding behind a
-- placeholder.

ALTER TABLE run_position_snapshots
    ADD COLUMN instrument_type VARCHAR(32) NOT NULL;

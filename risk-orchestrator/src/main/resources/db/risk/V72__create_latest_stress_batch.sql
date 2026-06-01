-- V72: Persist the latest batch stress-test result per book (issue kx-kjse).
--
-- The Scenarios tab must populate on cold open without the user clicking
-- "Run All Scenarios". To make that work we persist the most recent batch
-- sweep per book here; a GET endpoint reads it back and the UI fetches it on
-- mount. The demo-orchestrator's StressScenarioSeedJob already fires a batch
-- sweep per book at bootstrap, so a reseed leaves a stored "latest batch"
-- ready for the cold-open path.
--
-- Mirrors the canned-stress persist-and-fetch shape (kx-wxy), but where the
-- canned tile caches a single three-field payload, a batch result is a list of
-- ranked scenario results plus failures and a worst-scenario summary. We store
-- the whole BatchStressRunResultResponse as a JSONB blob keyed on book id —
-- one row per book, overwritten on each sweep (UPSERT on the primary key).
--
-- Flyway runs migrations inside a transaction, so no CREATE INDEX CONCURRENTLY
-- here — the primary key on book_id is sufficient for the single-row lookup.

CREATE TABLE IF NOT EXISTS latest_stress_batches (
    book_id      VARCHAR(64)              PRIMARY KEY,
    result       JSONB                    NOT NULL,
    computed_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE valuation_jobs
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(255);

COMMENT ON COLUMN valuation_jobs.correlation_id IS 'Correlation id from the originating CalculateVaRRequested trigger. Propagated end-to-end for traceability across services and Kafka events. Nullable for legacy rows and direct-call paths.';

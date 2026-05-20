-- Adds correlation_id to audit_events as a cross-reference pointer that
-- joins a stored audit record to its Loki logs and Tempo trace.
--
-- correlation_id is operational metadata, not an audited fact: it is
-- intentionally NOT folded into AuditHasher.computeHash. The hash chain
-- protects regulatory facts (who/what/when/trade detail); a debugging
-- cross-reference has no integrity requirement, and including it would
-- break every existing chain. The column is nullable — events stored
-- before this migration simply carry NULL.

ALTER TABLE audit_events
    ADD COLUMN correlation_id VARCHAR(255);

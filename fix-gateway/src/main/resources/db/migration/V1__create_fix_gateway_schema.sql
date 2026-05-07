-- ADR-0035 phase 1: skeleton-only baseline migration.
-- Real tables (fix_session_state, fix_message_log, etc.) land in V2 with phase 2.
-- The fix-gateway service uses the default `public` schema of the kinetix_fix_gateway
-- database (per ADR-0011 database-per-service). This migration exists so that
-- Flyway has a baseline to record in flyway_schema_history.
SELECT 1;

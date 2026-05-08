-- ADR-0035 phase 2 follow-on: add order_status column to fix_message_log.
--
-- Purpose: supports the mass-cancel-on-disconnect reconciliation query that
-- needs to identify "open" outbound orders (35=D sent but no terminal 35=8
-- received) for a given venue within the last 24 hours.
--
-- order_status is set to OPEN when a 35=D is logged (direction=OUT) and
-- updated to TERMINAL when a terminal 35=8 or 35=9 is received. Only
-- outbound 35=D rows are relevant for reconciliation; all other rows carry
-- the column but it is not semantically meaningful.
--
-- PARTITION CAVEAT: ALTER TABLE on a partitioned parent propagates the column
-- to existing partitions automatically; new partitions inherit it via the
-- parent DDL.
ALTER TABLE fix_message_log
    ADD COLUMN IF NOT EXISTS order_status VARCHAR(16) NOT NULL DEFAULT 'OPEN';

-- Partial index: only outbound rows with OPEN status — the exact predicate the
-- reconciliation query uses. Declared on the parent; inherited by partitions
-- only on Postgres 14+. If the Postgres version in use is < 14, this index
-- only covers the parent and per-partition indexes are created separately by
-- the nightly partition-creation job. For the acceptance tests (Postgres 17 via
-- Testcontainers) this works correctly.
CREATE INDEX IF NOT EXISTS idx_fix_message_log_open_out
    ON fix_message_log (venue, clord_id)
    WHERE direction = 'OUT' AND msg_type = 'D' AND order_status = 'OPEN';

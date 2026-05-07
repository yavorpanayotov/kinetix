-- ADR-0035 phase 2: FIX session state + message log.
--
-- Lives in the default `public` schema of the kinetix_fix_gateway database
-- (per ADR-0011 database-per-service; V1 set the convention).
--
-- fix_session_state holds per-venue sequence numbers so the QuickFIX/J initiator
-- can recover seq state on boot. Single row per venue enforced via primary key.
CREATE TABLE IF NOT EXISTS fix_session_state (
    venue           VARCHAR(32) PRIMARY KEY,
    sender_seq_num  BIGINT NOT NULL CHECK (sender_seq_num >= 1),
    target_seq_num  BIGINT NOT NULL CHECK (target_seq_num >= 1),
    last_logon_at   TIMESTAMP WITH TIME ZONE,
    last_logout_at  TIMESTAMP WITH TIME ZONE,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- fix_message_log is the append-only audit / replay source for FIX traffic.
-- Declarative monthly partitioning by sent_at; partition pruning keeps queries
-- fast at sustained 50 fills/sec (~1.3B-row hot cap with 90-day retention).
CREATE TABLE IF NOT EXISTS fix_message_log (
    id              BIGSERIAL,
    venue           VARCHAR(32) NOT NULL,
    direction       VARCHAR(8)  NOT NULL CHECK (direction IN ('IN', 'OUT')),
    msg_type        VARCHAR(8)  NOT NULL,
    raw_message     TEXT        NOT NULL,
    clord_id        VARCHAR(64),
    venue_order_id  VARCHAR(64),
    sent_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id, sent_at)
) PARTITION BY RANGE (sent_at);

-- Bootstrap the current month + the next two so partition-aware writes do not
-- error on day-one. Operationally, a nightly job creates the next month and
-- detaches partitions older than 90 days for S3 archive.
CREATE TABLE IF NOT EXISTS fix_message_log_2026_05
    PARTITION OF fix_message_log
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE IF NOT EXISTS fix_message_log_2026_06
    PARTITION OF fix_message_log
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE TABLE IF NOT EXISTS fix_message_log_2026_07
    PARTITION OF fix_message_log
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

-- Indexes are declared on the partitioned parent so each partition inherits.
CREATE INDEX IF NOT EXISTS idx_fix_message_log_venue_clord
    ON fix_message_log (venue, clord_id);

CREATE INDEX IF NOT EXISTS idx_fix_message_log_venue_msg_type_sent
    ON fix_message_log (venue, msg_type, sent_at);

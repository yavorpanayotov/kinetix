-- Kafka inbox-dedup: make audit ingestion idempotent under at-least-once delivery.
--
-- Kafka redelivers records on rebalance, consumer crash before offset commit,
-- or producer retry. Without dedup, a redelivered trade event would create a
-- second audit row. We record the Kafka coordinates of every consumed record
-- and reject a re-insert of the same (topic, partition, offset).
--
-- The columns are NULLABLE: rows written before this migration, and any
-- non-Kafka inserts (DLQ replay, dev seeding), carry NULL coordinates. Both
-- the trade-event and governance consumers populate them, so audit rows that
-- originate from Kafka are deduped.
--
-- source_topic + source_partition + source_offset uniquely identify a Kafka
-- record. received_at is included in the unique index because audit_events is
-- a TimescaleDB hypertable partitioned on received_at, and TimescaleDB requires
-- the partitioning column to be part of every unique index. The repository
-- performs the duplicate check explicitly under the audit-chain advisory lock
-- (which already serialises every append), so dedup does not depend on the
-- index's exact column set; the unique index is defence-in-depth.
--
-- Plain CREATE [UNIQUE] INDEX — no CONCURRENTLY — because Flyway runs each
-- migration inside a transaction.

ALTER TABLE audit_events
    ADD COLUMN source_topic     VARCHAR(255),
    ADD COLUMN source_partition INT,
    ADD COLUMN source_offset    BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_audit_events_kafka_source
    ON audit_events (source_topic, source_partition, source_offset, received_at)
    WHERE source_topic IS NOT NULL;

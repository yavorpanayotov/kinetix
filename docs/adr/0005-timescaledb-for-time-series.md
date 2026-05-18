# ADR-0005: Use TimescaleDB for Time-Series Data

## Status
Accepted

## Context
Risk calculation results and market data history are time-series data requiring efficient time-range queries, aggregation, and retention policies. Options: TimescaleDB (PostgreSQL extension), QuestDB, InfluxDB, plain PostgreSQL with partitioning.

## Decision
Use TimescaleDB (latest for PostgreSQL 17) as a PostgreSQL extension for time-series storage.

## Applies when
- Designing a new table whose primary access pattern is "rows are time-stamped, queries are by time range".
- Considering a separate TSDB (QuestDB, InfluxDB) for a new service.
- Writing a migration that converts an existing table to a hypertable, or adds a column to one.

## Rules
- **DO** model new time-series tables as hypertables via `create_hypertable(...)`. Read ADR-0027 first — there are non-obvious migration constraints.
- **DO** include the partitioning column (typically `received_at`, `started_at`, `valuation_date`) in every unique constraint and primary key on the hypertable.
- **DO** enable the extension with `CREATE EXTENSION IF NOT EXISTS timescaledb;` at the top of the first migration that needs it (idempotent and safe).
- **DO** use continuous aggregates for pre-computed rollups (hourly/daily aggregations).
- **DON'T** introduce a separate time-series database. PostgreSQL + TimescaleDB is the only sanctioned TSDB.
- **DON'T** use PostgreSQL `RULE` objects on a hypertable — replace with `BEFORE UPDATE`/`BEFORE DELETE` triggers (see `audit-service V4`).
- **DON'T** alter a compressed chunk in-place. Decompress → alter → recompress (see `risk-orchestrator V19`/`V21` in ADR-0027), and only during a maintenance window.

## Consequences

### Positive
- Single database technology to operate — TimescaleDB is a PostgreSQL extension, not a separate database. Same driver (`postgresql`), same SQL, same tooling (pgAdmin, Flyway, Exposed)
- Automatic time-based partitioning via hypertables
- Continuous aggregates for pre-computed rollups (hourly VaR averages, daily P&L)
- Compression for historical data (10-20x compression ratios)
- Full SQL support including JOINs with relational tables in the same database

### Negative
- Slightly lower raw ingestion throughput than purpose-built TSDBs like QuestDB
- Extension must be enabled in PostgreSQL — requires a custom Docker image or the official TimescaleDB image

### Stored Time-Series Data
- `market_data` — Price ticks, bid/ask, volume per instrument (price-service)
- `valuation_jobs` — VaR values, expected shortfall, component breakdown per book/calculation type (risk-orchestrator)
- `audit_events` — Tamper-evident audit trail with hash chain (audit-service)
- `alert_events` — Triggered alert history (notification-service)
- Regulatory submission and scenario result tables (regulatory-service)

TimescaleDB is now used by 5 databases: price-service, risk-orchestrator, audit-service, notification-service, and regulatory-service. All share the same operational model (TimescaleDB extension, hypertables, retention policies).

### Alternatives Considered
- **QuestDB**: Faster for pure analytical queries and high-frequency ingestion, but introduces a separate database technology with its own query language, driver, and operational model. Not worth the complexity for our throughput needs.
- **InfluxDB**: Purpose-built TSDB but uses Flux query language (not SQL). Weaker JOIN capabilities.
- **Plain PostgreSQL partitioning**: Manual partition management is tedious. TimescaleDB automates this.

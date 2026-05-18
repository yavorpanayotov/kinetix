# ADR-0027: Database Migration Practices and Constraints

## Status
Accepted

## Context
Kinetix runs Flyway for schema migrations across ten services, each with its own PostgreSQL database (database-per-service, see ADR-0011). Several services use TimescaleDB for time-series tables. The combination of Flyway's transactional migration model, TimescaleDB's extension prerequisites, and compressed hypertable constraints has produced a set of non-obvious rules that are not captured anywhere. This ADR documents them so contributors avoid known failure modes.

## Decision

### Flyway naming convention

Every migration file must follow the naming pattern:

```
V{N}__{description}.sql
```

- `{N}` is an integer version number, unique within the service. No gaps are required, but gaps are allowed.
- Double underscore separates the version number from the description.
- The description uses underscores for spaces (e.g., `add_instrument_type`, not `addInstrumentType`).
- Checksum-verified: once a migration has run, its content must never change. Flyway will refuse to start if a migration's content differs from what was checksummed at first application.

**Per-service migration paths:**

| Service | Migration path | Current version |
|---|---|---|
| position-service | `db/position/` | V9 |
| price-service | `db/price/` | V5 |
| rates-service | `db/rates/` | V2 |
| volatility-service | `db/volatility/` | V3 |
| correlation-service | `db/correlation/` | V4 |
| reference-data-service | `db/referencedata/` | V9 |
| risk-orchestrator | `db/risk/` | V64 |
| regulatory-service | `db/regulatory/` | V9 |
| notification-service | `db/notification/` | V8 |
| audit-service | `db/audit/` | V11 |

### Transaction constraints

Flyway wraps every migration script in a single database transaction. Statements that cannot execute inside a transaction will cause the migration to fail (and Flyway to mark it as failed in the `flyway_schema_history` table, blocking further migrations).

**Never use inside a Flyway migration:**

- `CREATE INDEX CONCURRENTLY` — requires running outside a transaction. Use plain `CREATE INDEX` instead; the lock duration is acceptable at the data volumes Kinetix handles.
- `CREATE UNIQUE INDEX CONCURRENTLY` — same constraint.
- `ALTER TYPE ... ADD VALUE` (adding a value to a PostgreSQL enum) — requires a transaction commit before the new value is visible. Use a lookup table or a new migration that is split across two transactions if this pattern is unavoidable.
- `VACUUM`, `CLUSTER` — maintenance statements that are incompatible with normal transaction semantics.
- Any DDL that calls a TimescaleDB function that internally creates background workers, unless the function is designed to be called transactionally (e.g., `create_hypertable` is safe; `add_compression_policy` is safe when called within a transaction).

### TimescaleDB prerequisites

Services using TimescaleDB hypertables (risk-orchestrator, price-service, audit-service, regulatory-service) require the `timescaledb` extension to exist in the database before any migration that calls `create_hypertable`. The extension is created inside the migration itself using `CREATE EXTENSION IF NOT EXISTS timescaledb;` (idempotent), placed at the top of the first migration that needs it.

**Hypertable primary key constraint**: TimescaleDB requires the partitioning column (typically a timestamp) to be part of every unique constraint on a hypertable. When converting an existing table to a hypertable, the existing single-column primary key must be dropped and replaced with a composite primary key or unique constraint that includes the partitioning column. Examples from the codebase:
- `audit-service V4`: drops `pk_audit_events` (on `id`), adds `pk_audit_events (id, received_at)`, then calls `create_hypertable('audit_events', 'received_at')`.
- `risk-orchestrator V8`: drops `calculation_runs_pkey`, adds `uq_valuation_jobs UNIQUE (job_id, started_at)`, then calls `create_hypertable('valuation_jobs', 'started_at')`.

**Rules vs. triggers on hypertables**: PostgreSQL `RULE` objects are not supported on TimescaleDB hypertables. If a table uses rules for immutability enforcement (e.g., `prevent_update ON audit_events`) and is later converted to a hypertable, the rules must be dropped and replaced with `BEFORE UPDATE`/`BEFORE DELETE` triggers. See `audit-service V4` for the pattern.

### Compression and decompression as maintenance operations

Two migrations in risk-orchestrator require decompressing all compressed chunks before adding columns to `valuation_jobs`:

- **V19** (`V19__add_run_label_and_eod_designations.sql`): Removes the compression policy, decompresses all chunks via a `DO $$` loop, adds three nullable columns, rebuilds a partial index, then re-enables the compression policy.
- **V21** (`V21__add_run_manifests.sql`): Same decompression pattern to add `manifest_id UUID NULL`.

These migrations are **maintenance-window-only operations** in production. Decompressing a large hypertable can take minutes and creates significant I/O pressure. Do not run V19 or V21 (or any future migration that decompresses a hypertable) during peak trading hours. Schedule them in the 18:00–06:00 UTC window when market data ingestion is minimal.

The decompression pattern used is safe within a Flyway migration because `decompress_chunk` is transactionally safe. However, if the migration is interrupted mid-way (e.g., by a timeout), some chunks will be decompressed and some will not. The re-run via Flyway repair is safe because `decompress_chunk` is idempotent on an already-decompressed chunk.

### Rollback SQL

Risk-orchestrator maintains paired rollback files (e.g., `V19-rollback.sql`, `V21-rollback.sql`, `V22-rollback.sql`, `V27-rollback.sql`) alongside the forward migrations. These are **not** applied by Flyway (Flyway Community does not support rollback). They are manual SQL scripts to be applied by an operator in the event of an emergency rollback. Keep rollback files current whenever a migration is added.

The rollback file naming convention (`V{N}-rollback.sql`) keeps rollback scripts collocated with their corresponding forward migration in alphabetical order.

### Backward compatibility

See ADR-0025 for the expand-contract pattern governing all breaking schema changes. Migrations that rename or drop columns require two releases. This ADR documents the mechanical Flyway and TimescaleDB constraints; ADR-0025 governs the sequencing discipline.

## Applies when
- Writing any Flyway migration in any Kinetix service.
- Touching a TimescaleDB hypertable (create, alter, add column, change indexes).
- Considering a maintenance-window operation (decompression, large rebuild).

## Rules
- **DO** name files `V{N}__{description}.sql` with double underscore and snake_case description, under the service's path (`db/<service>/`).
- **DO** treat applied migrations as immutable. Once Flyway has checksummed a file, never edit it — add a follow-up migration.
- **DO** use plain `CREATE INDEX` (transactional). Long lock times are acceptable at our volumes; schedule against peak hours if needed.
- **DO** include the hypertable partitioning column in every unique constraint and primary key (see `audit-service V4`, `risk-orchestrator V8` for the pattern).
- **DO** replace `RULE` objects with triggers when converting a table to a hypertable (PostgreSQL rules are not supported on hypertables).
- **DO** add a paired `V{N}-rollback.sql` for risk-orchestrator migrations. These are manual operator scripts; Flyway Community has no rollback.
- **DON'T** use `CREATE INDEX CONCURRENTLY`, `CREATE UNIQUE INDEX CONCURRENTLY`, `ALTER TYPE ... ADD VALUE`, `VACUUM`, or `CLUSTER` inside a Flyway migration — all incompatible with a single transaction.
- **DON'T** alter columns on a compressed hypertable. Decompress → alter → recompress (see V19/V21) and schedule in 18:00–06:00 UTC.
- **DON'T** put integration tests requiring Docker in the `common` module — Docker client deps are missing on its classpath (CLAUDE.md gotcha).

## Consequences

### Positive
- Contributors have a single reference for non-obvious Flyway and TimescaleDB constraints, reducing failed migrations in CI and production.
- Documented maintenance-window requirements for decompression migrations prevent unplanned production incidents.
- The per-service migration path table gives new contributors a map of where to add migrations.

### Negative
- The decompression-then-alter pattern in V19 and V21 is unavoidable given TimescaleDB's constraint that compressed chunks cannot have columns added. Future migrations that need to alter `valuation_jobs` will need the same pattern until all existing compressed chunks are beyond the retention window and dropped.
- TimescaleDB's transactional incompatibility with `CREATE INDEX CONCURRENTLY` means indexes on large hypertables must use plain `CREATE INDEX`, which holds an `AccessShareLock` on the table for the duration of the index build. For write-heavy tables during market hours, this can cause query queuing. Schedule such migrations outside peak hours.

### Alternatives Considered
- **Liquibase instead of Flyway**: Liquibase supports rollback via its own rollback DSL and has better support for managing migration state. Flyway was chosen (implicitly, from the start of the project) and migration to Liquibase would require rewriting all existing migration history. Not worth the disruption.
- **Manual DDL without Flyway**: Rejected at project inception. Flyway's checksum verification and schema history table are essential for auditing and preventing schema drift across environments.

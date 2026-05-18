# ADR-0025: Flyway Backward-Compatible Migration Convention

## Status
Accepted

## Context
During rolling deployments, old and new application versions run simultaneously against the same database. The new pod comes up, Flyway runs the migration, and then both old and new pods serve traffic until the old pods are terminated. If the migration removes or renames a column the old code reads, the old pods immediately begin throwing errors — potentially crashing under load before the rollout completes.

We have encountered this three times in the Kinetix codebase:

1. **position-service V7** (`V7__rename_trade_type_to_event_type.sql`) — renamed column `trade_type` to `event_type` on `trade_events` in a single migration. Any old pod still running read `trade_type` via Exposed and received `null` (column gone), breaking trade lifecycle queries until the rollout finished.

2. **price-service V2** (`V2__rename_table_to_prices.sql`) — renamed table `market_data` to `prices` and its index in one step. Old pods referencing `market_data` in their queries failed immediately after the migration ran.

3. **notification-service V4** (`V4__rename_portfolio_to_book.sql`) — renamed column `portfolio_id` to `book_id` on `alert_events`. Old pods querying by `portfolio_id` returned no rows (column gone), silently breaking alert filtering until the old pods were replaced.

All three cases required emergency rollbacks or fast-tracked full rollouts outside the normal deployment window.

## Decision
Apply the **expand-contract pattern** for all schema changes that remove, rename, or narrow existing structure.

### The expand-contract pattern

A breaking schema change is split across two releases:

**Release N — Expand:** Add the new structure alongside the old. Both old and new code work against the schema simultaneously.
- Add a new column with a DEFAULT value (or NULL) — never drop the old one yet.
- Add a new table — the old table remains untouched.
- Populate the new column via a backfill migration or application logic.

**Release N+1 — Contract:** Remove the old structure once no running code references it.
- Drop the old column or table only after all pods have been updated to Release N.

### Forbidden patterns in a single migration step

Never apply the following in a single migration during a normal rolling deployment:

| Pattern | Why it breaks rolling deployment |
|---|---|
| `DROP COLUMN` | Old pods referencing the column fail immediately |
| `RENAME COLUMN` | Old pods using the original column name get null or a missing-column error |
| `RENAME TABLE` | Any query using the old table name fails |
| `ALTER COLUMN ... TYPE` (narrowing) | Old data or old code may not fit the narrower type; implicit casts can silently truncate |
| `ADD COLUMN ... NOT NULL` without a DEFAULT | Existing rows violate the constraint; old pods inserting without the new column fail |
| `DROP TABLE` | Self-evidently fatal for old pods |

### Migration that is always safe in a single step

- `ADD COLUMN ... NULL` (no NOT NULL constraint)
- `ADD COLUMN ... NOT NULL DEFAULT <literal>` (database fills the default; old pods do not need to supply the value)
- `CREATE TABLE` (new table; old pods never touch it)
- `CREATE INDEX` (non-concurrent, wrapped in Flyway's transaction)
- `CREATE INDEX CONCURRENTLY` — **never use**; this statement cannot run inside a transaction, and Flyway wraps every migration in a transaction. Use plain `CREATE INDEX` instead.

### Handling NOT NULL columns

When a genuinely non-nullable column must be introduced:
1. **Expand:** `ADD COLUMN new_col TEXT NULL;` — backfill all rows in the same migration or a subsequent one.
2. **Backfill:** Update every existing row: `UPDATE table SET new_col = <value> WHERE new_col IS NULL;`
3. **Contract (next release):** `ALTER TABLE table ALTER COLUMN new_col SET NOT NULL;` — safe once all rows are populated and old pods are gone.

## Applies when
- Writing any Flyway migration that changes existing schema (rename, drop, type narrow, NOT NULL).
- Reviewing a PR that mixes new schema with a column drop in a single migration.
- Tempted to "just rename" a column to clean up naming.

## Rules
- **DO** split every destructive schema change into two migrations across two releases: **Release N expand**, **Release N+1 contract**.
- **DO** add new columns as `NULL` or with a literal `DEFAULT`. Backfill in the same migration or the next one before adding `NOT NULL`.
- **DO** dual-write to both old and new columns in application code during the expand phase if a rollback must keep working.
- **DO** add a paired `V{N}-rollback.sql` for risk-orchestrator migrations (ADR-0027 §Rollback SQL).
- **DON'T** `DROP COLUMN`, `RENAME COLUMN`, `RENAME TABLE`, `ALTER COLUMN ... TYPE` (narrowing), `ADD COLUMN ... NOT NULL` without DEFAULT, or `DROP TABLE` in a single rolling-deploy migration. Each of these has caused production breakage already.
- **DON'T** use `CREATE INDEX CONCURRENTLY` — Flyway wraps migrations in a transaction. Use plain `CREATE INDEX` (see ADR-0027 for the full forbidden list).
- **DON'T** combine schema rename and code rename in one PR. Rename column in expand release; switch code reads in expand release; drop old column in contract release.

## Consequences

### Positive
- Old pods continue to serve traffic correctly during the new-pod startup window.
- Rollbacks are safe — dropping the new column (expansion) is always a safe operation.
- Schema changes are auditable across two clearly labelled migrations rather than hidden in a combined rename.

### Negative
- Breaking changes take two releases instead of one. For a weekly release cadence, this adds one week to the delivery of a rename or column removal.
- The intermediate state (both old and new columns coexist) requires discipline — the application code must write to both columns during the expand phase if backward compatibility is needed for rollback.
- More migration files per feature, but each file is simpler and safer.

### Alternatives Considered
- **Maintenance windows**: Run breaking migrations during a scheduled outage. Avoided because Kinetix targets zero-downtime deployments and maintenance windows conflict with that goal.
- **Blue-green with traffic cutover before migration**: Eliminates the dual-version window, but requires twice the infrastructure and a full-fleet swap before the schema change runs. Expand-contract achieves the same safety with no extra infrastructure.

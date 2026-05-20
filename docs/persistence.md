# Persistence Layer

This document is the single reference for every database, table, repository, and connection-pool setting in the Kinetix platform. It also describes how data flows between services via Kafka (no service shares a database).

---

## Overview

| Concern | Technology |
|---------|------------|
| RDBMS | PostgreSQL 17 (TimescaleDB image) |
| Time-series | TimescaleDB hypertables (Price, Audit, Risk Orchestrator, Notification) |
| ORM | Exposed 0.58.0 (core, dao, jdbc, kotlin-datetime, json) |
| Migrations | Flyway 11.3.1 (core + postgresql module) |
| Connection pool | HikariCP 6.2.1 |
| JDBC driver | PostgreSQL JDBC 42.7.5 |

Every microservice owns its own database — there is no shared schema. Schema evolution is managed by Flyway migrations under each service's `src/main/resources/db/<name>/` directory. Data access goes through the repository pattern: an interface defines the contract, and an `Exposed*Repository` implementation provides the SQL via Exposed's type-safe DSL inside `newSuspendedTransaction` blocks.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            PostgreSQL (TimescaleDB)                              │
│                                                                                 │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐           │
│  │kinetix_      │ │kinetix_      │ │kinetix_      │ │kinetix_      │           │
│  │position      │ │price         │ │risk          │ │audit         │           │
│  │              │ │ (hypertable) │ │ (hypertable) │ │ (hypertable) │           │
│  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └──────┬───────┘           │
│         │                │                │                │                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐           │
│  │kinetix_      │ │kinetix_      │ │kinetix_      │ │kinetix_      │           │
│  │notification  │ │regulatory    │ │rates         │ │reference_data│           │
│  │ (hypertable) │ │              │ │              │ │              │           │
│  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └──────┬───────┘           │
│         │                │                │                │                    │
│  ┌──────────────┐ ┌──────────────┐                                             │
│  │kinetix_      │ │kinetix_      │                                             │
│  │volatility    │ │correlation   │                                             │
│  └──────┬───────┘ └──────┬───────┘                                             │
└─────────┼───────────────┼────────────────────────────────────────────────────────┘
          │               │
          ▼               ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                               Kafka Topics                                      │
│                                                                                 │
│  trades.lifecycle   price.updates   risk.results   risk.anomalies               │
│  rates.yield-curves   rates.risk-free   rates.forwards                          │
│  reference-data.dividends   reference-data.credit-spreads                       │
│  volatility.surfaces   correlation.matrices                                     │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Per-Service Persistence

### 1. Position Service

| Property | Value |
|----------|-------|
| Database | `kinetix_position` |
| Migration path | `db/position/` |
| Migrations | V1–V21 (21 files) |
| Pool | maxPoolSize=15, minIdle=3 |

#### Tables

**trade_events** (V1, lifecycle columns added V3, V6–V9, V12–V14, V19)

| Column | Type | Constraints |
|--------|------|-------------|
| trade_id | VARCHAR(255) | **PK** |
| book_id | VARCHAR(255) | Indexed |
| instrument_id | VARCHAR(255) | |
| asset_class | VARCHAR(50) | |
| side | VARCHAR(10) | |
| quantity | DECIMAL(28,12) | |
| price_amount | DECIMAL(28,12) | |
| price_currency | VARCHAR(3) | |
| traded_at | TIMESTAMPTZ | Indexed |
| created_at | TIMESTAMPTZ | |
| event_type | VARCHAR(10) | Default `'NEW'` |
| status | VARCHAR(20) | Default `'LIVE'` |
| original_trade_id | VARCHAR(255) | Nullable — links amendments/cancels to originating trade |
| counterparty_id | VARCHAR(255) | Nullable |
| instrument_type | VARCHAR(50) | Default `'UNKNOWN'` |
| strategy_id | VARCHAR(36) | Nullable — FK to trade_strategies |

An immutability trigger (V10) prevents updates and deletes. Column `portfolio_id` was renamed to `book_id` in V13; the trigger was updated in V14. A composite index on `(book_id, traded_at DESC)` was added in V8.

**positions** (V2, extended V3, V9, V12–V13, V20)

| Column | Type | Constraints |
|--------|------|-------------|
| book_id | VARCHAR(255) | **PK** (composite) |
| instrument_id | VARCHAR(255) | **PK** (composite) |
| asset_class | VARCHAR(50) | |
| quantity | DECIMAL(28,12) | |
| avg_cost_amount | DECIMAL(28,12) | |
| market_price_amount | DECIMAL(28,12) | |
| currency | VARCHAR(3) | |
| updated_at | TIMESTAMPTZ | Indexed |
| realized_pnl_amount | DECIMAL(28,12) | Default 0 |
| instrument_type | VARCHAR(50) | Default `'UNKNOWN'` |
| strategy_id | VARCHAR(36) | Nullable |

**limit_definitions** (V5)

| Column | Type | Constraints |
|--------|------|-------------|
| id | VARCHAR(255) | **PK** |
| level | VARCHAR(20) | FIRM / DESK / TRADER / COUNTERPARTY |
| entity_id | VARCHAR(255) | |
| limit_type | VARCHAR(20) | POSITION / NOTIONAL / CONCENTRATION |
| limit_value | DECIMAL(28,12) | |
| intraday_limit | DECIMAL(28,12) | Nullable |
| overnight_limit | DECIMAL(28,12) | Nullable |
| active | BOOLEAN | |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**limit_temporary_increases** (V5)

| Column | Type | Constraints |
|--------|------|-------------|
| id | VARCHAR(255) | **PK** |
| limit_id | VARCHAR(255) | FK → limit_definitions |
| new_value | DECIMAL(28,12) | |
| approved_by | VARCHAR(255) | |
| expires_at | TIMESTAMPTZ | |
| reason | TEXT | |
| created_at | TIMESTAMPTZ | |

**book_hierarchy** (V15)

| Column | Type | Constraints |
|--------|------|-------------|
| book_id | VARCHAR(64) | **PK** |
| desk_id | VARCHAR(64) | |
| book_name | VARCHAR(255) | Nullable |
| book_type | VARCHAR(64) | Nullable |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**execution_orders** (V16, extended V21)

| Column | Type | Constraints |
|--------|------|-------------|
| order_id | VARCHAR(255) | **PK** |
| book_id | VARCHAR(255) | |
| instrument_id | VARCHAR(255) | |
| side | VARCHAR(10) | |
| quantity | DECIMAL(28,12) | |
| order_type | VARCHAR(50) | |
| limit_price | DECIMAL(28,12) | Nullable |
| arrival_price | DECIMAL(28,12) | |
| submitted_at | TIMESTAMPTZ | |
| status | VARCHAR(30) | |
| risk_check_result | VARCHAR(20) | Nullable |
| risk_check_details | TEXT | Nullable |
| fix_session_id | VARCHAR(255) | Nullable |
| asset_class | VARCHAR(30) | |
| currency | VARCHAR(10) | |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**execution_fills** (V16)

| Column | Type | Constraints |
|--------|------|-------------|
| fill_id | VARCHAR(255) | **PK** |
| order_id | VARCHAR(255) | |
| book_id | VARCHAR(255) | |
| instrument_id | VARCHAR(255) | |
| fill_time | TIMESTAMPTZ | |
| fill_qty | DECIMAL(28,12) | |
| fill_price | DECIMAL(28,12) | |
| fill_type | VARCHAR(20) | |
| venue | VARCHAR(100) | Nullable |
| cumulative_qty | DECIMAL(28,12) | |
| average_price | DECIMAL(28,12) | |
| fix_exec_id | VARCHAR(255) | Nullable |
| created_at | TIMESTAMPTZ | |

**execution_cost_analysis** (V16)

| Column | Type | Constraints |
|--------|------|-------------|
| order_id | VARCHAR(255) | **PK** |
| book_id | VARCHAR(255) | |
| instrument_id | VARCHAR(255) | |
| completed_at | TIMESTAMPTZ | |
| arrival_price | DECIMAL(28,12) | |
| average_fill_price | DECIMAL(28,12) | |
| side | VARCHAR(10) | |
| total_qty | DECIMAL(28,12) | |
| slippage_bps | DECIMAL(20,10) | |
| market_impact_bps | DECIMAL(20,10) | Nullable |
| timing_cost_bps | DECIMAL(20,10) | Nullable |
| total_cost_bps | DECIMAL(20,10) | |
| created_at | TIMESTAMPTZ | |

**fix_sessions** (V16)

| Column | Type | Constraints |
|--------|------|-------------|
| session_id | VARCHAR(255) | **PK** |
| counterparty | VARCHAR(255) | |
| status | VARCHAR(30) | |
| last_message_at | TIMESTAMPTZ | Nullable |
| inbound_seq_num | INTEGER | |
| outbound_seq_num | INTEGER | |
| updated_at | TIMESTAMPTZ | |

**prime_broker_reconciliation** (V16)

| Column | Type | Constraints |
|--------|------|-------------|
| id | VARCHAR(255) | **PK** |
| reconciliation_date | VARCHAR(10) | |
| book_id | VARCHAR(255) | |
| status | VARCHAR(20) | |
| total_positions | INTEGER | |
| matched_count | INTEGER | |
| break_count | INTEGER | |
| breaks | TEXT | JSON stored as text |
| reconciled_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ | |

**collateral_balances** (V17)

| Column | Type | Constraints |
|--------|------|-------------|
| id | BIGSERIAL | **PK** |
| counterparty_id | VARCHAR(255) | |
| netting_set_id | VARCHAR(255) | Nullable |
| collateral_type | VARCHAR(30) | |
| amount | DECIMAL(24,6) | |
| currency | VARCHAR(3) | Default `'USD'` |
| direction | VARCHAR(10) | |
| as_of_date | DATE | |
| value_after_haircut | DECIMAL(24,6) | |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**netting_set_trades** (V17)

| Column | Type | Constraints |
|--------|------|-------------|
| trade_id | VARCHAR(255) | **PK** |
| netting_set_id | VARCHAR(255) | |
| created_at | TIMESTAMPTZ | |

**fx_rates** (V18)

| Column | Type | Constraints |
|--------|------|-------------|
| from_currency | CHAR(3) | **PK** (composite) |
| to_currency | CHAR(3) | **PK** (composite) |
| rate | DECIMAL(18,8) | |
| updated_at | TIMESTAMPTZ | |

**trade_strategies** (V19)

| Column | Type | Constraints |
|--------|------|-------------|
| strategy_id | VARCHAR(36) | **PK** |
| book_id | VARCHAR(255) | |
| strategy_type | VARCHAR(50) | |
| name | VARCHAR(255) | Nullable |
| created_at | TIMESTAMPTZ | |

#### Repositories

- `TradeEventRepository` — save, findByTradeId, findByBookId
- `PositionRepository` — save, findByBookId, findByKey, findByInstrumentId, delete, findDistinctBookIds
- `LimitRepository` — save, findAll, findByLevel, findById, delete
- `BookHierarchyRepository` — save, findByBookId, findAll
- `FxRateRepository` — save, findRate
- `TradeStrategyRepository` — save, findByBookId, findById

---

### 2. Price Service

| Property | Value |
|----------|-------|
| Database | `kinetix_price` (TimescaleDB extension enabled) |
| Migration path | `db/price/` |
| Migrations | V1–V5 (5 files) |
| Pool | maxPoolSize=20, minIdle=5 |

#### Tables

**prices** (V1 as `market_data`, renamed V2)

| Column | Type | Constraints |
|--------|------|-------------|
| instrument_id | VARCHAR(255) | **PK** (composite) |
| timestamp | TIMESTAMPTZ | **PK** (composite) |
| price_amount | DECIMAL(28,12) | |
| price_currency | VARCHAR(3) | |
| source | VARCHAR(50) | |
| created_at | TIMESTAMPTZ | |

TimescaleDB hypertable partitioned on `timestamp`. An index on `(instrument_id, timestamp DESC)` supports instrument-scoped range queries. Note: the Exposed column is declared as `varchar("source", 50)` — the logical name is `dataSource` in Kotlin but the SQL column is `source`.

#### Notable Features

- **Compression** (V3): enabled on `instrument_id` segment, `timestamp DESC` order, compressing chunks older than 30 days.
- **Retention policy** (V3): raw chunks older than 2 years are dropped.
- **Continuous aggregate** `daily_close_prices` (V5): pre-computes the daily close price per instrument using `last()`. Refreshes hourly, looking back 30 days. Columns: `instrument_id`, `bucket` (day), `close_price_amount`, `close_price_currency`, `close_source`, `close_timestamp`.

#### Repository — `PriceRepository`

```kotlin
suspend fun save(point: PricePoint)
suspend fun findLatest(instrumentId: InstrumentId): PricePoint?
suspend fun findByInstrumentId(instrumentId: InstrumentId, from: Instant, to: Instant): List<PricePoint>
```

---

### 3. Risk Orchestrator

| Property | Value |
|----------|-------|
| Database | `kinetix_risk` |
| Migration path | `db/risk/` |
| Migrations | V1–V64 (64 files, excluding rollback scripts) |
| Pool | maxPoolSize=8, minIdle=2 |

This is the most complex schema in the platform. It tracks every valuation run lifecycle, per-instrument risk snapshots, P&L attribution, SOD baselines, run manifests for audit/replay, cross-counterparty exposures, factor model data, and reporting.

#### Tables

**valuation_jobs** (V1–V4, extended V12–V16, V19–V22, V25–V27, V29, V34, V43, V59)

TimescaleDB hypertable (V8) on `started_at`. Retention 7 years (V17). Compression after 90 days, segmented by `book_id`, ordered by `started_at DESC`.

| Column | Type | Notes |
|--------|------|-------|
| job_id | UUID | **PK** |
| book_id | VARCHAR(255) | Indexed (composite with started_at) |
| trigger_type | VARCHAR(50) | |
| status | VARCHAR(20) | |
| valuation_date | DATE | |
| started_at | TIMESTAMPTZ | Hypertable partition column |
| completed_at | TIMESTAMPTZ | Nullable |
| duration_ms | BIGINT | Nullable |
| calculation_type | VARCHAR(50) | Nullable |
| confidence_level | VARCHAR(10) | Nullable |
| var_value | DOUBLE PRECISION | Nullable |
| expected_shortfall | DOUBLE PRECISION | Nullable |
| pv_value | DOUBLE PRECISION | Nullable |
| delta | DOUBLE PRECISION | Nullable |
| gamma | DOUBLE PRECISION | Nullable |
| vega | DOUBLE PRECISION | Nullable |
| theta | DOUBLE PRECISION | Nullable |
| rho | DOUBLE PRECISION | Nullable |
| position_risk | JSONB | Nullable — `List<PositionRiskJson>` |
| component_breakdown | JSONB | Nullable — `List<ComponentBreakdownJson>` |
| computed_outputs | JSONB | Nullable — `List<String>` |
| asset_class_greeks | JSONB | Nullable — `List<AssetClassGreeksJson>` |
| phases | JSONB | Per-phase timing — `List<JobPhaseJson>` (renamed from `steps` in V27) |
| current_phase | VARCHAR(50) | Nullable |
| error | TEXT | Nullable |
| triggered_by | VARCHAR(255) | Not null (V59) |
| run_label | VARCHAR(20) | Nullable — `EOD`, `SOD`, etc. |
| promoted_at | TIMESTAMPTZ | Nullable — when promoted to official EOD |
| promoted_by | VARCHAR(255) | Nullable |
| market_data_snapshot_id | VARCHAR(255) | Nullable |
| manifest_id | UUID | Nullable — FK to run_manifests |
| time_horizon_days | INTEGER | Nullable |
| requested_calculation_type | VARCHAR(50) | Nullable |
| requested_confidence_level | VARCHAR(10) | Nullable |
| requested_time_horizon_days | INTEGER | Nullable |

**Continuous aggregate** `hourly_var_summary` (V11, rebuilt V31): hourly bucket per `book_id` and `calculation_type`. Columns: `bucket`, `book_id`, `calculation_type`, `avg_var`, `min_var`, `max_var`, `avg_es`, `job_count`. Refreshes every 30 minutes, looking back 3 hours.

**sod_baselines** (V6, extended V7)

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | **PK** |
| book_id | VARCHAR(255) | |
| baseline_date | DATE | |
| snapshot_type | VARCHAR(16) | |
| created_at | TIMESTAMPTZ | |
| source_job_id | UUID | Nullable |
| calculation_type | VARCHAR(50) | Nullable |
| var_value | DOUBLE PRECISION | Nullable |
| expected_shortfall | DOUBLE PRECISION | Nullable |
| greek_snapshot_id | BIGINT | Nullable — FK to sod_greek_snapshots |

**daily_risk_snapshots** (V5, extended V13, V18, V28, V49, V57)

TimescaleDB hypertable (V18) on `snapshot_date`. Indexed on `(book_id, snapshot_date DESC, instrument_id)`.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | **PK** (composite with snapshot_date) |
| book_id | VARCHAR(64) | |
| snapshot_date | TIMESTAMPTZ | Hypertable partition column |
| instrument_id | VARCHAR(255) | |
| asset_class | VARCHAR(32) | |
| quantity | DECIMAL(20,8) | |
| market_price | DECIMAL(20,8) | |
| delta | DOUBLE PRECISION | Nullable |
| gamma | DOUBLE PRECISION | Nullable |
| vega | DOUBLE PRECISION | Nullable |
| theta | DOUBLE PRECISION | Nullable |
| rho | DOUBLE PRECISION | Nullable |
| var_contribution | DECIMAL(28,8) | Nullable |
| es_contribution | DECIMAL(28,8) | Nullable |
| sod_vol | DOUBLE PRECISION | Nullable |
| sod_rate | DOUBLE PRECISION | Nullable |
| created_at | TIMESTAMPTZ | |

**pnl_attributions** (V5, extended V32, V52)

TimescaleDB hypertable (V10) on `attribution_date`. Unique constraint on `(book_id, attribution_date)`.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | **PK** |
| book_id | VARCHAR(64) | |
| attribution_date | DATE | Hypertable partition column |
| total_pnl | DECIMAL(20,8) | |
| delta_pnl | DECIMAL(20,8) | |
| gamma_pnl | DECIMAL(20,8) | |
| vega_pnl | DECIMAL(20,8) | |
| theta_pnl | DECIMAL(20,8) | |
| rho_pnl | DECIMAL(20,8) | |
| vanna_pnl | DECIMAL(20,8) | Default 0 |
| volga_pnl | DECIMAL(20,8) | Default 0 |
| charm_pnl | DECIMAL(20,8) | Default 0 |
| cross_gamma_pnl | DECIMAL(20,8) | Default 0 |
| unexplained_pnl | DECIMAL(20,8) | |
| position_attributions | JSONB | Nullable — `List<PositionPnlAttributionJson>` |
| currency | VARCHAR(3) | Default `'USD'` |
| data_quality_flag | VARCHAR(32) | Default `'PRICE_ONLY'` |
| created_at | TIMESTAMPTZ | |

**Continuous aggregate** `daily_pnl_summary` (V10, rebuilt V54): daily bucket per `book_id`. Includes full cross-Greek columns (vanna, volga, charm, cross_gamma). Refreshes daily, looking back 3 days.

**run_manifests** (V21)

| Column | Type | Notes |
|--------|------|-------|
| manifest_id | UUID | **PK** |
| job_id | UUID | |
| book_id | VARCHAR(255) | |
| valuation_date | DATE | |
| captured_at | TIMESTAMPTZ | |
| model_version | VARCHAR(100) | |
| calculation_type | VARCHAR(50) | |
| confidence_level | VARCHAR(10) | |
| time_horizon_days | INTEGER | |
| num_simulations | INTEGER | |
| monte_carlo_seed | BIGINT | |
| position_count | INTEGER | |
| position_digest | VARCHAR(64) | SHA-256 of inputs |
| market_data_digest | VARCHAR(64) | |
| input_digest | VARCHAR(64) | |
| status | VARCHAR(20) | |
| var_value | DOUBLE PRECISION | Nullable |
| expected_shortfall | DOUBLE PRECISION | Nullable |
| output_digest | VARCHAR(64) | Nullable |

**run_position_snapshots** (V21)

Point-in-time position snapshot per manifest, FK to `run_manifests`.

| Column | Type | Notes |
|--------|------|-------|
| manifest_id | UUID | **PK** (composite), FK → run_manifests |
| instrument_id | VARCHAR(255) | **PK** (composite) |
| asset_class | VARCHAR(32) | |
| quantity | DECIMAL(28,12) | |
| avg_cost_amount | DECIMAL(28,12) | |
| market_price_amount | DECIMAL(28,12) | |
| currency | VARCHAR(3) | |
| market_value_amount | DECIMAL(28,12) | |
| unrealized_pnl_amount | DECIMAL(28,12) | |

**run_manifest_market_data** (V21)

| Column | Type | Notes |
|--------|------|-------|
| manifest_id | UUID | **PK** (composite), FK → run_manifests |
| data_type | VARCHAR(50) | **PK** (composite) |
| instrument_id | VARCHAR(255) | **PK** (composite) |
| content_hash | VARCHAR(64) | FK to run_market_data_blobs |
| asset_class | VARCHAR(32) | |
| status | VARCHAR(20) | |
| source_service | VARCHAR(50) | |
| sourced_at | TIMESTAMPTZ | |

**run_market_data_blobs** (V22)

Content-addressed store — deduplicated by hash, so identical market data shared across runs is stored once.

| Column | Type | Notes |
|--------|------|-------|
| content_hash | VARCHAR(64) | **PK** |
| data_type | VARCHAR(50) | |
| instrument_id | VARCHAR(255) | |
| asset_class | VARCHAR(32) | |
| payload | JSONB | Raw market data |
| created_at | TIMESTAMPTZ | |

**replay_runs** (V22)

| Column | Type | Notes |
|--------|------|-------|
| replay_id | UUID | **PK** |
| manifest_id | UUID | FK → run_manifests |
| original_job_id | UUID | |
| replayed_at | TIMESTAMPTZ | |
| triggered_by | VARCHAR(255) | |
| replay_var_value | DOUBLE PRECISION | Nullable |
| replay_expected_shortfall | DOUBLE PRECISION | Nullable |
| replay_model_version | VARCHAR(100) | Nullable |
| replay_output_digest | VARCHAR(64) | Nullable |
| original_var_value | DOUBLE PRECISION | Nullable |
| original_expected_shortfall | DOUBLE PRECISION | Nullable |
| input_digest_match | BOOLEAN | |
| original_input_digest | VARCHAR(64) | |
| replay_input_digest | VARCHAR(64) | |

**official_eod_designations** (V19)

| Column | Type | Notes |
|--------|------|-------|
| book_id | VARCHAR(255) | **PK** (composite) |
| valuation_date | DATE | **PK** (composite) |
| job_id | UUID | |
| promoted_at | TIMESTAMPTZ | |
| promoted_by | VARCHAR(255) | |

**intraday_pnl_snapshots** (V35)

TimescaleDB hypertable on `snapshot_at`.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | **PK** (composite with snapshot_at) |
| book_id | VARCHAR(64) | |
| snapshot_at | TIMESTAMPTZ | Hypertable partition column |
| base_currency | VARCHAR(3) | |
| trigger | VARCHAR(32) | |
| total_pnl | DECIMAL(20,8) | |
| realised_pnl | DECIMAL(20,8) | |
| unrealised_pnl | DECIMAL(20,8) | |
| delta_pnl | DECIMAL(20,8) | |
| gamma_pnl | DECIMAL(20,8) | |
| vega_pnl | DECIMAL(20,8) | |
| theta_pnl | DECIMAL(20,8) | |
| rho_pnl | DECIMAL(20,8) | |
| vanna_pnl | DECIMAL(20,8) | Default 0 |
| volga_pnl | DECIMAL(20,8) | Default 0 |
| charm_pnl | DECIMAL(20,8) | Default 0 |
| cross_gamma_pnl | DECIMAL(20,8) | Default 0 |
| unexplained_pnl | DECIMAL(20,8) | |
| high_water_mark | DECIMAL(20,8) | |
| instrument_pnl_json | TEXT | JSON array, default `'[]'` |
| correlation_id | VARCHAR(128) | Nullable |

**factor_decomposition_snapshots** (V36)

| Column | Type | Notes |
|--------|------|-------|
| snapshot_id | UUID | **PK** |
| book_id | VARCHAR(255) | |
| calculated_at | TIMESTAMPTZ | |
| total_var | DECIMAL(24,6) | |
| systematic_var | DECIMAL(24,6) | |
| idiosyncratic_var | DECIMAL(24,6) | |
| r_squared | DECIMAL(8,6) | |
| concentration_warning | BOOLEAN | |
| factors_json | JSONB | |

**risk_budget_allocations** (V37)

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(36) | **PK** |
| entity_level | VARCHAR(16) | FIRM / DESK / BOOK |
| entity_id | VARCHAR(64) | |
| budget_type | VARCHAR(16) | VaR / ES / NOTIONAL |
| budget_period | VARCHAR(16) | DAILY / MONTHLY / ANNUAL |
| budget_amount | DECIMAL(24,6) | |
| effective_from | DATE | |
| effective_to | DATE | Nullable |
| allocated_by | VARCHAR(255) | |
| allocation_note | TEXT | Nullable |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**liquidity_risk_snapshots** (V38, extended V58)

| Column | Type | Notes |
|--------|------|-------|
| snapshot_id | UUID | **PK** |
| book_id | VARCHAR(255) | |
| calculated_at | TIMESTAMPTZ | |
| portfolio_lvar | DECIMAL(24,6) | |
| data_completeness | DECIMAL(5,4) | |
| portfolio_concentration_status | VARCHAR(20) | |
| position_risks_json | JSONB | |
| var_1day | DECIMAL(24,6) | Default 0 |
| lvar_ratio | DECIMAL(10,6) | Default 0 |
| weighted_avg_horizon | DECIMAL(10,4) | Default 0 |
| max_horizon | DECIMAL(10,4) | Default 0 |
| concentration_count | INTEGER | Default 0 |
| adv_data_as_of | TIMESTAMPTZ | Nullable |

**risk_hierarchy_snapshots** (V39)

TimescaleDB hypertable on `snapshot_at`.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | **PK** (composite with snapshot_at) |
| snapshot_at | TIMESTAMPTZ | Hypertable partition column |
| level | VARCHAR(16) | FIRM / DESK / BOOK |
| entity_id | VARCHAR(64) | |
| parent_id | VARCHAR(64) | Nullable |
| var_value | DECIMAL(24,6) | |
| expected_shortfall | DECIMAL(24,6) | Nullable |
| pnl_today | DECIMAL(24,6) | Nullable |
| limit_utilisation | DECIMAL(10,6) | Nullable |
| marginal_var | DECIMAL(24,6) | Nullable |
| top_contributors | JSONB | |
| is_partial | BOOLEAN | |

**market_regime_history** (V40)

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | **PK** (composite with started_at) |
| regime | VARCHAR(32) | NORMAL / STRESS / CRISIS / RECOVERY |
| started_at | TIMESTAMPTZ | |
| ended_at | TIMESTAMPTZ | Nullable |
| duration_ms | BIGINT | Nullable |
| realised_vol_20d | DECIMAL(12,6) | |
| cross_asset_correlation | DECIMAL(12,6) | |
| credit_spread_bps | DECIMAL(12,4) | Nullable |
| pnl_volatility | DECIMAL(12,6) | Nullable |
| calculation_type | VARCHAR(32) | |
| confidence_level | VARCHAR(16) | |
| time_horizon_days | INTEGER | |
| correlation_method | VARCHAR(32) | |
| num_simulations | INTEGER | Nullable |
| confidence | DECIMAL(6,4) | Regime detection confidence |
| degraded_inputs | BOOLEAN | |
| consecutive_observations | INTEGER | |
| created_at | TIMESTAMPTZ | |

**hedge_recommendations** (V41, extended V46)

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | **PK** |
| book_id | VARCHAR(255) | |
| target_metric | VARCHAR(20) | VaR / DELTA / VEGA |
| target_reduction_pct | DECIMAL(8,6) | |
| requested_at | TIMESTAMPTZ | |
| status | VARCHAR(20) | |
| expires_at | TIMESTAMPTZ | |
| accepted_by | VARCHAR(255) | Nullable |
| accepted_at | TIMESTAMPTZ | Nullable |
| source_job_id | VARCHAR(255) | Nullable |
| message | TEXT | Nullable |
| constraints_json | JSONB | |
| suggestions_json | JSONB | |
| pre_hedge_greeks_json | JSONB | |

**counterparty_exposure_history** (V42, extended V48)

TimescaleDB hypertable on `calculated_at`.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | **PK** (composite with calculated_at) |
| counterparty_id | VARCHAR(255) | |
| calculated_at | TIMESTAMPTZ | Hypertable partition column |
| pfe_profile_json | JSONB | Potential future exposure profile |
| netting_set_exposures_json | JSONB | |
| wrong_way_risk_flags_json | JSONB | |
| current_net_exposure | DECIMAL(24,6) | |
| peak_pfe | DECIMAL(24,6) | |
| cva | DECIMAL(24,6) | Nullable |
| cva_estimated | BOOLEAN | |
| currency | VARCHAR(3) | |
| collateral_held | DECIMAL(24,6) | Default 0 |
| collateral_posted | DECIMAL(24,6) | Default 0 |
| net_net_exposure | DECIMAL(24,6) | Nullable — post-collateral |

**sod_greek_snapshots** (V53)

Immutable SOD market state per instrument, locked once confirmed. Referenced by `sod_baselines.greek_snapshot_id`.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | **PK** |
| book_id | VARCHAR(64) | |
| snapshot_date | DATE | |
| instrument_id | VARCHAR(64) | |
| sod_price | DECIMAL(20,8) | |
| sod_vol | DOUBLE PRECISION | Nullable |
| sod_rate | DOUBLE PRECISION | Nullable |
| delta | DOUBLE PRECISION | Nullable |
| gamma | DOUBLE PRECISION | Nullable |
| vega | DOUBLE PRECISION | Nullable |
| theta | DOUBLE PRECISION | Nullable |
| rho | DOUBLE PRECISION | Nullable |
| vanna | DOUBLE PRECISION | Nullable |
| volga | DOUBLE PRECISION | Nullable |
| charm | DOUBLE PRECISION | Nullable |
| bond_dv01 | DOUBLE PRECISION | Nullable |
| swap_dv01 | DOUBLE PRECISION | Nullable |
| is_locked | BOOLEAN | Default false |
| locked_at | TIMESTAMPTZ | Nullable |
| locked_by | VARCHAR(128) | Nullable |
| created_at | TIMESTAMPTZ | |

**sa_ccr_results** (V63)

TimescaleDB hypertable on `calculated_at`.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | **PK** (composite with calculated_at) |
| counterparty_id | VARCHAR(255) | |
| netting_set_id | VARCHAR(255) | |
| calculated_at | TIMESTAMPTZ | Hypertable partition column |
| replacement_cost | DECIMAL(24,6) | |
| pfe_addon | DECIMAL(24,6) | |
| multiplier | DECIMAL(10,8) | |
| ead | DECIMAL(24,6) | Exposure at default |
| alpha | DECIMAL(6,4) | SA-CCR alpha parameter (typically 1.4) |
| collateral_net | DECIMAL(24,6) | |
| position_count | INTEGER | |

**factor_definitions** (V60)

| Column | Type | Notes |
|--------|------|-------|
| factor_name | VARCHAR(64) | **PK** |
| proxy_instrument_id | VARCHAR(64) | |
| description | TEXT | |

**factor_returns** (V61)

| Column | Type | Notes |
|--------|------|-------|
| factor_name | VARCHAR(64) | **PK** (composite) |
| as_of_date | DATE | **PK** (composite) |
| return_value | DOUBLE PRECISION | |
| source | VARCHAR(64) | |

**instrument_factor_loadings** (V62)

| Column | Type | Notes |
|--------|------|-------|
| instrument_id | VARCHAR(64) | **PK** (composite) |
| factor_name | VARCHAR(64) | **PK** (composite) |
| loading | DOUBLE PRECISION | Beta / sensitivity |
| r_squared | DOUBLE PRECISION | Nullable |
| method | VARCHAR(32) | OLS / RIDGE / etc. |
| estimation_date | DATE | |
| estimation_window | INTEGER | Days of history used |

**report_templates** (V56)

| Column | Type | Notes |
|--------|------|-------|
| template_id | VARCHAR(36) | **PK** |
| name | VARCHAR(255) | |
| template_type | VARCHAR(50) | |
| owner_user_id | VARCHAR(255) | |
| definition | JSONB | `ReportDefinitionJson` |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**report_outputs** (V56)

| Column | Type | Notes |
|--------|------|-------|
| output_id | VARCHAR(36) | **PK** |
| template_id | VARCHAR(36) | |
| generated_at | TIMESTAMPTZ | |
| output_format | VARCHAR(20) | JSON / CSV |
| row_count | INTEGER | |
| output_data | JSONB | Nullable |

#### Materialized Views

| View | Created | Description |
|------|---------|-------------|
| `hourly_var_summary` | V11, rebuilt V31, V34 | Hourly VaR averages per `book_id` and `calculation_type`. TimescaleDB continuous aggregate. Refreshes every 30 min. |
| `daily_pnl_summary` | V10, rebuilt V34, V54 | Daily P&L totals per `book_id` including all cross-Greek columns. TimescaleDB continuous aggregate. Refreshes daily. |
| `daily_official_eod_summary` | V34 | Joined view of `official_eod_designations` + `valuation_jobs` showing all promoted EOD results per book and date. Regular materialized view (not TimescaleDB). |
| `daily_eod_completeness` | V50 | Per-date count of books with completed jobs vs. books promoted to EOD. Used for operational monitoring. |
| `risk_positions_flat` | V55 | Denormalised flat view joining `daily_risk_snapshots` (latest per instrument), `valuation_jobs` (latest greeks), and `pnl_attributions` (latest P&L) per book. Refreshed after EOD promotion. Supports concurrent refresh via unique index on `(book_id, instrument_id)`. |

#### Repository — `ValuationJobRepository`

Tracks the full lifecycle of every risk calculation run. Key operations: save, findByJobId, findByBookId (paginated), findLatestByBookId, promoteToEod.

---

### 4. Audit Service

| Property | Value |
|----------|-------|
| Database | `kinetix_audit` |
| Migration path | `db/audit/` |
| Migrations | V1–V11 (11 files) |
| Pool | maxPoolSize=8, minIdle=2 |

#### Tables

**audit_events** (V1, extended V2–V5, V9–V11)

TimescaleDB hypertable on `received_at` (V4). The PK is composite `(id, received_at)` as required by TimescaleDB. Immutability enforced via trigger (UPDATE and DELETE blocked). Compression on `book_id` segment after 30 days. Retention: 7 years (regulatory requirement). A sequence number was added in V11 for ordered verification.

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | **PK** (composite with received_at) |
| trade_id | VARCHAR(255) | Nullable — null for governance events |
| book_id | VARCHAR(255) | Nullable — null for governance events (renamed from portfolio_id in V9) |
| instrument_id | VARCHAR(255) | Nullable |
| asset_class | VARCHAR(50) | Nullable |
| side | VARCHAR(10) | Nullable |
| quantity | DECIMAL(28,12) | Nullable |
| price_amount | DECIMAL(28,12) | Nullable |
| price_currency | VARCHAR(3) | Nullable |
| traded_at | TIMESTAMPTZ | Nullable |
| received_at | TIMESTAMPTZ | Hypertable partition column |
| previous_hash | VARCHAR(64) | Nullable — null for the first event |
| record_hash | VARCHAR(64) | SHA-256 hash of this record |
| user_id | VARCHAR(255) | Nullable |
| user_role | VARCHAR(100) | Nullable |
| event_type | VARCHAR(100) | Default `'TRADE_BOOKED'` |
| model_name | VARCHAR(255) | Nullable — governance events |
| scenario_id | VARCHAR(255) | Nullable — governance events |
| limit_id | VARCHAR(255) | Nullable — governance events |
| submission_id | VARCHAR(255) | Nullable — governance events |
| details | TEXT | Nullable — governance events |
| sequence_number | BIGINT | Nullable — monotonic ordering |

**audit_verification_checkpoints** (V7)

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | **PK** |
| last_event_id | BIGINT | |
| last_hash | VARCHAR(64) | |
| verified_at | TIMESTAMPTZ | |
| event_count | BIGINT | |

#### Repository — `AuditEventRepository`

```kotlin
suspend fun save(event: AuditEvent)
suspend fun findAll(): List<AuditEvent>
suspend fun findByBookId(bookId: String): List<AuditEvent>
suspend fun verifyHashChain(): Boolean
```

---

### 5. Notification Service

| Property | Value |
|----------|-------|
| Database | `kinetix_notification` |
| Migration path | `db/notification/` |
| Migrations | V1–V8 (8 files) |
| Pool | maxPoolSize=8, minIdle=2 |

#### Tables

**alert_rules** (V1)

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(255) | **PK** |
| name | VARCHAR(255) | |
| type | VARCHAR(50) | VAR_BREACH / LIMIT_BREACH / etc. |
| threshold | DECIMAL(20,6) | |
| operator | VARCHAR(50) | GT / LT / EQ |
| severity | VARCHAR(50) | LOW / MEDIUM / HIGH / CRITICAL |
| channels | VARCHAR(500) | CSV — e.g. `"EMAIL,WEBHOOK"` |
| enabled | BOOLEAN | |

**alert_events** (V2, extended V3–V8)

TimescaleDB hypertable on `triggered_at` (V3). Compression on `book_id` segment after 30 days. Two partial indexes: active alerts `(triggered_at DESC) WHERE status = 'TRIGGERED'`; critical active alerts `(severity, triggered_at DESC) WHERE status = 'TRIGGERED'`.

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(255) | **PK** |
| rule_id | VARCHAR(255) | Indexed |
| rule_name | VARCHAR(255) | |
| type | VARCHAR(50) | |
| severity | VARCHAR(50) | |
| message | TEXT | |
| current_value | DECIMAL(20,6) | |
| threshold | DECIMAL(20,6) | |
| book_id | VARCHAR(255) | Indexed (renamed from portfolio_id in V4) |
| triggered_at | TIMESTAMPTZ | Indexed DESC; hypertable partition column |
| status | VARCHAR(20) | Default `'TRIGGERED'` — lifecycle tracking |
| acknowledged_at | TIMESTAMPTZ | Nullable (V8) |
| resolved_at | TIMESTAMPTZ | Nullable (V5) |
| resolved_reason | TEXT | Nullable (V5) |
| escalated_at | TIMESTAMPTZ | Nullable (V8) |
| escalated_to | VARCHAR(255) | Nullable (V8) |
| contributors | TEXT | Nullable (V5) — JSON drill-down |
| correlation_id | VARCHAR(255) | Nullable (V5) |
| suggested_action | TEXT | Nullable (V7) |

**alert_acknowledgements** (V6)

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(255) | **PK** |
| alert_event_id | VARCHAR(255) | |
| alert_triggered_at | TIMESTAMPTZ | |
| acknowledged_by | VARCHAR(255) | |
| acknowledged_at | TIMESTAMPTZ | |
| notes | TEXT | Nullable |

#### Repository — `AlertRuleRepository`

```kotlin
suspend fun save(rule: AlertRule)
suspend fun findAll(): List<AlertRule>
suspend fun deleteById(id: String): Boolean
```

#### Repository — `AlertEventRepository`

```kotlin
suspend fun save(event: AlertEvent)
suspend fun findRecent(limit: Int = 50): List<AlertEvent>
suspend fun acknowledge(id: String, by: String, notes: String?)
suspend fun resolve(id: String, reason: String)
```

---

### 6. Regulatory Service

| Property | Value |
|----------|-------|
| Database | `kinetix_regulatory` |
| Migration path | `db/regulatory/` |
| Migrations | V1–V19 (19 files) |
| Pool | maxPoolSize=8, minIdle=2 |

#### Tables

**frtb_calculations** (V1, extended V7, V10, V13)

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(255) | **PK** |
| book_id | VARCHAR(255) | Indexed (renamed from portfolio_id in V13) |
| total_sbm_charge | DECIMAL(28,8) | |
| gross_jtd | DECIMAL(28,8) | |
| hedge_benefit | DECIMAL(28,8) | |
| net_drc | DECIMAL(28,8) | |
| exotic_notional | DECIMAL(28,8) | |
| other_notional | DECIMAL(28,8) | |
| total_rrao | DECIMAL(28,8) | |
| total_capital_charge | DECIMAL(28,8) | |
| sbm_charges_json | JSONB | SBM charges by risk bucket (V7 converted from TEXT) |
| calculated_at | TIMESTAMPTZ | Indexed DESC |
| stored_at | TIMESTAMPTZ | |

**backtest_results** (V2, extended V11)

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(255) | **PK** |
| book_id | VARCHAR(255) | |
| calculation_type | VARCHAR(50) | |
| confidence_level | DOUBLE PRECISION | |
| total_days | INTEGER | |
| violation_count | INTEGER | |
| violation_rate | DOUBLE PRECISION | |
| kupiec_statistic | DOUBLE PRECISION | |
| kupiec_p_value | DOUBLE PRECISION | |
| kupiec_pass | BOOLEAN | |
| christoffersen_statistic | DOUBLE PRECISION | |
| christoffersen_p_value | DOUBLE PRECISION | |
| christoffersen_pass | BOOLEAN | |
| traffic_light_zone | VARCHAR(10) | GREEN / AMBER / RED |
| calculated_at | TIMESTAMPTZ | |
| input_digest | CHAR(64) | Nullable — SHA-256 of inputs |
| window_start | DATE | Nullable |
| window_end | DATE | Nullable |
| model_version | VARCHAR(100) | Nullable |

**model_versions** (V3, extended V17, V19)

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(255) | **PK** |
| model_name | VARCHAR(255) | |
| version | VARCHAR(50) | |
| status | VARCHAR(20) | DRAFT / PENDING_APPROVAL / APPROVED / DEPRECATED |
| parameters | JSONB | Model configuration |
| registered_by | VARCHAR(255) | |
| approved_by | VARCHAR(255) | Nullable — four-eyes principle |
| approved_at | TIMESTAMPTZ | Nullable |
| created_at | TIMESTAMPTZ | |
| model_tier | VARCHAR(50) | Nullable — governance tier |
| validation_report_url | VARCHAR(2048) | Nullable |
| known_limitations | TEXT | Nullable |
| approved_use_cases | TEXT | Nullable |
| next_validation_date | DATE | Nullable |

**regulatory_submissions** (V4)

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(255) | **PK** |
| report_type | VARCHAR(100) | CCAR / FRTB / etc. |
| status | VARCHAR(30) | DRAFT / PENDING_APPROVAL / SUBMITTED / ACKNOWLEDGED |
| preparer_id | VARCHAR(255) | |
| approver_id | VARCHAR(255) | Nullable — four-eyes principle |
| deadline | TIMESTAMPTZ | |
| submitted_at | TIMESTAMPTZ | Nullable |
| acknowledged_at | TIMESTAMPTZ | Nullable |
| created_at | TIMESTAMPTZ | |

**stress_scenarios** (V5, extended V8–V9, V14–V16, V18)

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(255) | **PK** |
| name | VARCHAR(255) | |
| description | TEXT | |
| shocks | JSONB | Per-factor shock magnitudes |
| status | VARCHAR(30) | DRAFT / PENDING_APPROVAL / APPROVED |
| created_by | VARCHAR(255) | |
| approved_by | VARCHAR(255) | Nullable |
| approved_at | TIMESTAMPTZ | Nullable |
| created_at | TIMESTAMPTZ | |
| scenario_type | VARCHAR(30) | Default `'PARAMETRIC'` |
| category | VARCHAR(30) | Default `'INTERNAL_APPROVED'` |
| version | INTEGER | Default 1 |
| parent_scenario_id | VARCHAR(255) | Nullable — versioning lineage |
| correlation_override | TEXT | Nullable |
| liquidity_stress_factors | TEXT | Nullable |
| historical_period_id | VARCHAR(255) | Nullable — links to historical_scenario_periods |
| target_loss | DECIMAL(28,8) | Nullable |

**stress_test_results** (V12, extended V13)

| Column | Type | Notes |
|--------|------|-------|
| id | VARCHAR(255) | **PK** |
| scenario_id | VARCHAR(255) | |
| book_id | VARCHAR(255) | |
| calculated_at | TIMESTAMPTZ | |
| base_pv | DECIMAL(28,8) | Nullable |
| stressed_pv | DECIMAL(28,8) | Nullable |
| pnl_impact | DECIMAL(28,8) | Nullable |
| var_impact | DOUBLE PRECISION | Nullable |
| position_impacts | JSONB | Nullable |
| model_version | VARCHAR(100) | Nullable |

**historical_scenario_periods** (V16)

| Column | Type | Notes |
|--------|------|-------|
| period_id | VARCHAR(255) | **PK** |
| name | VARCHAR(255) | e.g. `'2008 Financial Crisis'` |
| description | TEXT | Nullable |
| start_date | VARCHAR(10) | ISO-8601 date string |
| end_date | VARCHAR(10) | ISO-8601 date string |
| asset_class_focus | VARCHAR(100) | Nullable |
| severity_label | VARCHAR(50) | Nullable |

**historical_scenario_returns** (V16)

| Column | Type | Notes |
|--------|------|-------|
| period_id | VARCHAR(255) | **PK** (composite) |
| instrument_id | VARCHAR(255) | **PK** (composite) |
| return_date | VARCHAR(10) | **PK** (composite) |
| daily_return | DECIMAL(18,8) | |
| source | VARCHAR(100) | Default `'HISTORICAL'` |

#### Repository — `FrtbCalculationRepository`

```kotlin
suspend fun save(record: FrtbCalculationRecord)
suspend fun findByBookId(bookId: String, limit: Int, offset: Int): List<FrtbCalculationRecord>
suspend fun findLatestByBookId(bookId: String): FrtbCalculationRecord?
```

---

### 7. Rates Service

| Property | Value |
|----------|-------|
| Database | `kinetix_rates` |
| Migration path | `db/rates/` |
| Migrations | V1–V2 (2 files) |
| Pool | maxPoolSize=10, minIdle=3 |

#### Tables

**yield_curves** (V1)

| Column | Type | Constraints |
|--------|------|-------------|
| curve_id | VARCHAR(255) | **PK** (composite) |
| as_of_date | TIMESTAMPTZ | **PK** (composite) |
| currency | VARCHAR(3) | |
| source | VARCHAR(50) | |
| created_at | TIMESTAMPTZ | |

**yield_curve_tenors** (V1)

| Column | Type | Constraints |
|--------|------|-------------|
| curve_id | VARCHAR(255) | **PK** (composite), FK → yield_curves (CASCADE) |
| as_of_date | TIMESTAMPTZ | **PK** (composite), FK → yield_curves (CASCADE) |
| label | VARCHAR(50) | **PK** (composite) |
| days | INTEGER | |
| rate | DECIMAL(28,12) | |

**risk_free_rates** (V1)

| Column | Type | Constraints |
|--------|------|-------------|
| currency | VARCHAR(3) | **PK** (composite) |
| tenor | VARCHAR(50) | **PK** (composite) |
| as_of_date | TIMESTAMPTZ | **PK** (composite) |
| rate | DECIMAL(28,12) | |
| source | VARCHAR(50) | |
| created_at | TIMESTAMPTZ | |

**forward_curves** (V1)

| Column | Type | Constraints |
|--------|------|-------------|
| instrument_id | VARCHAR(255) | **PK** (composite) |
| as_of_date | TIMESTAMPTZ | **PK** (composite) |
| asset_class | VARCHAR(50) | |
| source | VARCHAR(50) | |
| created_at | TIMESTAMPTZ | |

**forward_curve_points** (V1)

| Column | Type | Constraints |
|--------|------|-------------|
| instrument_id | VARCHAR(255) | **PK** (composite), FK → forward_curves (CASCADE) |
| as_of_date | TIMESTAMPTZ | **PK** (composite), FK → forward_curves (CASCADE) |
| tenor | VARCHAR(50) | **PK** (composite) |
| value | DECIMAL(28,12) | |

A retention policy (V2) is applied to yield_curves data.

#### Repository — `YieldCurveRepository`

```kotlin
suspend fun save(curve: YieldCurve)
suspend fun findLatest(curveId: String): YieldCurve?
suspend fun findByTimeRange(curveId: String, from: Instant, to: Instant): List<YieldCurve>
```

#### Repository — `RiskFreeRateRepository`

```kotlin
suspend fun save(rate: RiskFreeRate)
suspend fun findLatest(currency: Currency, tenor: String): RiskFreeRate?
suspend fun findByTimeRange(currency: Currency, tenor: String, from: Instant, to: Instant): List<RiskFreeRate>
```

#### Repository — `ForwardCurveRepository`

```kotlin
suspend fun save(curve: ForwardCurve)
suspend fun findLatest(instrumentId: InstrumentId): ForwardCurve?
suspend fun findByTimeRange(instrumentId: InstrumentId, from: Instant, to: Instant): List<ForwardCurve>
```

---

### 8. Reference Data Service

| Property | Value |
|----------|-------|
| Database | `kinetix_reference_data` |
| Migration path | `db/referencedata/` |
| Migrations | V1–V11 (11 files) |
| Pool | maxPoolSize=8, minIdle=2 |

#### Tables

**dividend_yields** (V1)

| Column | Type | Constraints |
|--------|------|-------------|
| instrument_id | VARCHAR(255) | **PK** (composite) |
| as_of_date | TIMESTAMPTZ | **PK** (composite) |
| yield | DECIMAL(18,8) | |
| ex_date | VARCHAR(10) | Nullable |
| source | VARCHAR(50) | |
| created_at | TIMESTAMPTZ | |

Composite DESC indexes added in V2.

**credit_spreads** (V1)

| Column | Type | Constraints |
|--------|------|-------------|
| instrument_id | VARCHAR(255) | **PK** (composite) |
| as_of_date | TIMESTAMPTZ | **PK** (composite) |
| spread | DECIMAL(18,8) | |
| rating | VARCHAR(20) | Nullable |
| source | VARCHAR(50) | |
| created_at | TIMESTAMPTZ | |

**instruments** (V3)

Instrument master table supporting 11 typed subtypes (equity, bond, option, future, swap, FX spot, FX forward, credit default swap, commodity, ETF, index). Type-specific attributes stored in JSONB. JSONB GIN indexes on `attributes` added in V5.

| Column | Type | Constraints |
|--------|------|-------------|
| instrument_id | VARCHAR(255) | **PK** |
| instrument_type | VARCHAR(50) | |
| display_name | VARCHAR(255) | |
| asset_class | VARCHAR(50) | |
| currency | VARCHAR(3) | |
| attributes | JSONB | Type-specific attributes |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**divisions** (V4)

| Column | Type | Constraints |
|--------|------|-------------|
| id | VARCHAR(255) | **PK** |
| name | VARCHAR(255) | |
| description | TEXT | Nullable |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**desks** (V4)

| Column | Type | Constraints |
|--------|------|-------------|
| id | VARCHAR(255) | **PK** |
| name | VARCHAR(255) | |
| division_id | VARCHAR(255) | FK → divisions |
| desk_head | VARCHAR(255) | Nullable |
| description | TEXT | Nullable |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**instrument_liquidity** (V6, extended V8, V10–V11)

| Column | Type | Constraints |
|--------|------|-------------|
| instrument_id | VARCHAR(255) | **PK** |
| adv | DECIMAL(24,6) | Average daily volume |
| bid_ask_spread_bps | DECIMAL(10,4) | |
| asset_class | VARCHAR(50) | |
| liquidity_tier | VARCHAR(20) | Default `'ILLIQUID'` |
| adv_updated_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |
| adv_shares | DECIMAL(24,6) | Nullable |
| market_depth_score | DECIMAL(10,4) | Nullable |
| source | VARCHAR(50) | Default `'unknown'` |
| hedging_eligible | BOOLEAN | Nullable |

**counterparty_master** (V7)

| Column | Type | Constraints |
|--------|------|-------------|
| counterparty_id | VARCHAR(255) | **PK** |
| legal_name | VARCHAR(500) | |
| short_name | VARCHAR(100) | |
| lei | VARCHAR(20) | Nullable — Legal Entity Identifier |
| rating_sp | VARCHAR(10) | Nullable — S&P rating |
| rating_moodys | VARCHAR(10) | Nullable |
| rating_fitch | VARCHAR(10) | Nullable |
| sector | VARCHAR(100) | Default `'OTHER'` |
| country | VARCHAR(3) | Nullable — ISO-3166 |
| is_financial | BOOLEAN | Default false |
| pd_1y | DECIMAL(12,8) | Nullable — 1-year probability of default |
| lgd | DECIMAL(8,6) | Default 0.4 — loss given default |
| cds_spread_bps | DECIMAL(12,4) | Nullable |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**netting_agreements** (V7)

| Column | Type | Constraints |
|--------|------|-------------|
| netting_set_id | VARCHAR(255) | **PK** |
| counterparty_id | VARCHAR(255) | |
| agreement_type | VARCHAR(20) | Default `'ISDA_2002'` |
| close_out_netting | BOOLEAN | Default true |
| csa_threshold | DECIMAL(24,6) | Nullable — CSA collateral threshold |
| currency | VARCHAR(3) | Nullable |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

**benchmarks** (V9)

| Column | Type | Constraints |
|--------|------|-------------|
| benchmark_id | VARCHAR(36) | **PK** |
| name | VARCHAR(255) | |
| description | TEXT | Nullable |
| created_at | TIMESTAMPTZ | |

**benchmark_constituents** (V9)

| Column | Type | Constraints |
|--------|------|-------------|
| benchmark_id | VARCHAR(36) | **PK** (composite) |
| instrument_id | VARCHAR(255) | **PK** (composite) |
| weight | DECIMAL(18,10) | |
| as_of_date | DATE | **PK** (composite) |

**benchmark_returns** (V9)

| Column | Type | Constraints |
|--------|------|-------------|
| benchmark_id | VARCHAR(36) | **PK** (composite) |
| return_date | DATE | **PK** (composite) |
| daily_return | DECIMAL(18,10) | |

#### Repository — `DividendYieldRepository`

```kotlin
suspend fun save(dividendYield: DividendYield)
suspend fun findLatest(instrumentId: InstrumentId): DividendYield?
suspend fun findByTimeRange(instrumentId: InstrumentId, from: Instant, to: Instant): List<DividendYield>
```

#### Repository — `CreditSpreadRepository`

```kotlin
suspend fun save(creditSpread: CreditSpread)
suspend fun findLatest(instrumentId: InstrumentId): CreditSpread?
suspend fun findByTimeRange(instrumentId: InstrumentId, from: Instant, to: Instant): List<CreditSpread>
```

#### Repository — `InstrumentRepository`

```kotlin
suspend fun save(instrument: Instrument)
suspend fun findById(instrumentId: InstrumentId): Instrument?
suspend fun findAll(): List<Instrument>
suspend fun findByType(instrumentType: InstrumentType): List<Instrument>
```

---

### 9. Volatility Service

| Property | Value |
|----------|-------|
| Database | `kinetix_volatility` |
| Migration path | `db/volatility/` |
| Migrations | V1–V3 (3 files) |
| Pool | maxPoolSize=10, minIdle=3 |

#### Tables

**volatility_surfaces** (V1)

| Column | Type | Constraints |
|--------|------|-------------|
| instrument_id | VARCHAR(255) | **PK** (composite) |
| as_of_date | TIMESTAMPTZ | **PK** (composite) |
| source | VARCHAR(50) | |
| created_at | TIMESTAMPTZ | |

Composite DESC index added in V2. Retention policy added in V3.

**volatility_surface_points** (V1)

| Column | Type | Constraints |
|--------|------|-------------|
| instrument_id | VARCHAR(255) | **PK** (composite), FK → volatility_surfaces |
| as_of_date | TIMESTAMPTZ | **PK** (composite), FK → volatility_surfaces |
| strike | DECIMAL(28,12) | **PK** (composite) |
| maturity_days | INTEGER | **PK** (composite) |
| implied_vol | DECIMAL(18,8) | |

#### Repository — `VolSurfaceRepository`

```kotlin
suspend fun save(surface: VolSurface)
suspend fun findLatest(instrumentId: InstrumentId): VolSurface?
suspend fun findByTimeRange(instrumentId: InstrumentId, from: Instant, to: Instant): List<VolSurface>
```

---

### 10. Correlation Service

| Property | Value |
|----------|-------|
| Database | `kinetix_correlation` |
| Migration path | `db/correlation/` |
| Migrations | V1–V4 (4 files) |
| Pool | maxPoolSize=8, minIdle=2 |

#### Tables

**correlation_matrices** (V1, extended V2–V4)

| Column | Type | Constraints |
|--------|------|-------------|
| id | BIGSERIAL | **PK** |
| labels | JSONB | JSON array of instrument labels (converted from TEXT in V3) |
| values | JSONB | JSON 2-D matrix (converted from TEXT in V3) |
| window_days | INTEGER | |
| as_of_date | TIMESTAMPTZ | Indexed |
| method | VARCHAR(50) | SAMPLE / EWMA / LEDOIT_WOLF |
| created_at | TIMESTAMPTZ | |
| labels_hash | VARCHAR(32) | MD5 of sorted labels for fast lookup (V2) |

Indexed on `(labels_hash, window_days)` for latest-matrix lookups and on `as_of_date` for time-range queries. A functional index expression was fixed in V4.

#### Repository — `CorrelationMatrixRepository`

```kotlin
suspend fun save(matrix: CorrelationMatrix)
suspend fun findLatest(labels: List<String>, windowDays: Int): CorrelationMatrix?
suspend fun findByTimeRange(labels: List<String>, windowDays: Int, from: Instant, to: Instant): List<CorrelationMatrix>
```

---

### 11. Gateway

| Property | Value |
|----------|-------|
| Database | — |
| Migration path | — (no migrations) |

The gateway is stateless. It aggregates responses from backend services over HTTP and has no database of its own.

---

## Cross-Service Data Flow

No service reads another service's database. All inter-service data propagation happens through Kafka topics.

### Kafka Topics

| Topic | Event | Publisher | Consumers |
|-------|-------|-----------|-----------|
| `price.updates` | PriceEvent | Price Service | Position Service, Risk Orchestrator |
| `trades.lifecycle` | TradeEvent | Position Service | Risk Orchestrator, Audit Service |
| `risk.results` | RiskResultEvent | Risk Orchestrator | Notification Service |
| `risk.anomalies` | AnomalyEvent | Risk Orchestrator | Notification Service |
| `rates.yield-curves` | YieldCurveEvent | Rates Service | Risk Orchestrator (HTTP) |
| `rates.risk-free` | RiskFreeRateEvent | Rates Service | Risk Orchestrator (HTTP) |
| `rates.forwards` | ForwardCurveEvent | Rates Service | Risk Orchestrator (HTTP) |
| `reference-data.dividends` | DividendYieldEvent | Reference Data Service | Risk Orchestrator (HTTP) |
| `reference-data.credit-spreads` | CreditSpreadEvent | Reference Data Service | Risk Orchestrator (HTTP) |
| `volatility.surfaces` | VolSurfaceEvent | Volatility Service | Risk Orchestrator (HTTP) |
| `correlation.matrices` | CorrelationMatrixEvent | Correlation Service | Risk Orchestrator (HTTP) |

Market-data topics (rates, reference data, volatility, correlation) are published for downstream consumers. The Risk Orchestrator fetches this data via HTTP REST APIs rather than Kafka consumers.

### Consumer Groups

| Group ID | Service | Topic |
|----------|---------|-------|
| `position-service-group` | Position Service | `price.updates` |
| `risk-orchestrator-trades-group` | Risk Orchestrator | `trades.lifecycle` |
| `risk-orchestrator-prices-group` | Risk Orchestrator | `price.updates` |
| `notification-service-risk-group` | Notification Service | `risk.results` |
| `notification-service-anomaly-group` | Notification Service | `risk.anomalies` |
| `audit-service-group` | Audit Service | `trades.lifecycle` |

All consumer groups use `auto.offset.reset = earliest`. All consumers are wrapped in `RetryableConsumer` (common module) for DLQ routing on repeated failures.

### Primary Data Flows

**Trade → VaR → Alert:**

```
Position Service ──trades.lifecycle──▶ Risk Orchestrator ──risk.results──▶ Notification Service
Position Service ──trades.lifecycle──▶ Audit Service (persist)
```

**Price tick → Position update → VaR recalculation:**

```
Price Service ──price.updates──▶ Position Service (update market price)
Price Service ──price.updates──▶ Risk Orchestrator (recalculate VaR for affected books)
```

---

## Data Retention Policies

All retention policies — for both the per-service databases and the observability
stack — are listed here so they can be reviewed in one place. Database retention
is enforced by TimescaleDB `add_retention_policy` calls inside Flyway migrations;
observability retention is configured in the Helm `observability` sub-chart values
(`deploy/helm/kinetix/charts/observability/values.yaml`).

### Database retention (PostgreSQL / TimescaleDB)

| Store | Table / data | Retention | Configured in |
|-------|--------------|-----------|---------------|
| `kinetix_price` | `prices` raw chunks | 2 years | `db/price/V3` |
| `kinetix_risk` | `valuation_jobs` | 7 years | `db/risk/V17` |
| `kinetix_audit` | `audit_events` | 7 years (regulatory requirement) | `db/audit/` |
| `kinetix_rates` | `yield_curves` | retention policy applied | `db/rates/V2` |
| `kinetix_volatility` | `volatility_surfaces` | retention policy applied | `db/volatility/V3` |
| `kinetix_notification` | `alert_events` | retention policy applied | `db/notification/V3` |

Tables not listed above have no automatic retention — they are reference data,
governance records, or content-addressed stores that are retained indefinitely
by design.

### Observability retention (Loki / Tempo)

| Store | Data | Retention | Configured in |
|-------|------|-----------|---------------|
| Loki | Application logs | 90 days (`2160h`) | `observability/values.yaml` → `loki.loki.limits_config.retention_period` (compactor `retention_enabled`) |
| Tempo | Distributed traces | 30 days (`720h`) | `observability/values.yaml` → `tempo.tempo.compactor.compaction.block_retention` |

Without explicit retention both Loki and Tempo keep data indefinitely, so the
filesystem-backed stores grow without bound. Loki retention requires the
compactor to run with `retention_enabled: true`; Tempo retention is enforced by
its compactor dropping trace blocks older than `block_retention`. Prometheus
metrics retention is managed by the `kube-prometheus-stack` umbrella chart.

---

## Common Patterns

### DatabaseFactory

Every Kotlin service initialises its database with a `DatabaseFactory` that:

1. Creates a `HikariDataSource` with the pool config returned by `ConnectionPoolConfig.forService("<name>")`.
2. Runs Flyway migrations against that datasource (`classpath:db/<migration-name>`).
3. Connects Exposed via `Database.connect(dataSource)`.

```kotlin
object DatabaseFactory {
    const val FLYWAY_LOCATION = "classpath:db/position"   // example for position-service

    fun init(config: DatabaseConfig, meterRegistry: MeterRegistry? = null): Database {
        val dataSource = createDataSource(config)
        runMigrations(dataSource)
        return Database.connect(dataSource)
    }
}
```

### Connection Pool Defaults

```kotlin
data class ConnectionPoolConfig(
    val maxPoolSize: Int = 10,
    val minIdle: Int = 2,
    val connectionTimeoutMs: Long = 30_000,     // 30 s
    val idleTimeoutMs: Long = 600_000,           // 10 min
    val maxLifetimeMs: Long = 1_800_000,         // 30 min
    val leakDetectionThresholdMs: Long = 60_000, // 1 min
    val transactionIsolation: String = "TRANSACTION_REPEATABLE_READ",
    val autoCommit: Boolean = false,
)
```

Service-specific overrides (from `ConnectionPoolConfig.forService()`):

| Service | maxPoolSize | minIdle |
|---------|-------------|---------|
| position-service | 15 | 3 |
| price-service | 20 | 5 |
| rates-service | 10 | 3 |
| volatility-service | 10 | 3 |
| audit-service | 8 | 2 |
| notification-service | 8 | 2 |
| regulatory-service | 8 | 2 |
| reference-data-service | 8 | 2 |
| correlation-service | 8 | 2 |
| risk-orchestrator | 8 | 2 |

In dev mode (`KINETIX_DEV_MODE=true`) `minIdle` is forced to 0 and `connectionTimeoutMs` to 5 s to reduce resource usage.

### Repository Pattern

Each service defines a repository interface in its domain layer and an `Exposed*Repository` implementation in its persistence layer. All database calls are wrapped in `newSuspendedTransaction` for coroutine compatibility.

```kotlin
// Interface — domain layer
interface TradeEventRepository {
    suspend fun save(trade: Trade)
    suspend fun findByTradeId(tradeId: TradeId): Trade?
}

// Implementation — persistence layer
class ExposedTradeEventRepository(private val db: Database) : TradeEventRepository {
    override suspend fun save(trade: Trade) = newSuspendedTransaction(db = db) {
        TradeEventsTable.insert { /* … */ }
    }
}
```

### Flyway Migration Naming

Migrations follow the standard Flyway convention: `V<number>__<description>.sql` inside `src/main/resources/db/<name>/`. Each service's `DatabaseFactory` points Flyway at `classpath:db/<name>`. The mapping of service to migration directory name is:

| Service | Migration directory |
|---------|---------------------|
| position-service | `db/position/` |
| price-service | `db/price/` |
| risk-orchestrator | `db/risk/` |
| audit-service | `db/audit/` |
| notification-service | `db/notification/` |
| regulatory-service | `db/regulatory/` |
| rates-service | `db/rates/` |
| reference-data-service | `db/referencedata/` |
| volatility-service | `db/volatility/` |
| correlation-service | `db/correlation/` |

### Database Initialisation Script

`infra/db/init/01-create-databases.sql` runs once when the PostgreSQL container starts. It creates all 10 per-service databases using idempotent `SELECT 'CREATE DATABASE …'` blocks and enables the TimescaleDB extension on `kinetix_price`. Services that use TimescaleDB features (`kinetix_audit`, `kinetix_risk`, `kinetix_notification`) enable the extension within their own V1 or V4 migrations via `CREATE EXTENSION IF NOT EXISTS timescaledb`.

---

## Type Conventions

### Exposed → PostgreSQL Mapping

| Exposed Column API | PostgreSQL Type | Used For |
|--------------------|-----------------|----------|
| `varchar(…)` | VARCHAR | IDs, enums, currencies |
| `char(…)` | CHAR | Fixed-width codes (e.g. SHA-256 hashes, currency codes) |
| `decimal(28, 12)` | DECIMAL(28,12) | Financial amounts (prices, quantities) |
| `decimal(18, 8)` | DECIMAL(18,8) | Rates, yields, spreads, implied vols |
| `decimal(24, 6)` | DECIMAL(24,6) | Capital charges, exposures, CVA |
| `double(…)` | DOUBLE PRECISION | Greeks, VaR values, statistical outputs |
| `integer(…)` | INTEGER | Day counts, window sizes, sequence numbers |
| `long(…)` / `BIGSERIAL` | BIGINT / BIGSERIAL | Auto-increment PKs |
| `bool(…)` | BOOLEAN | Flags |
| `text(…)` | TEXT | Long strings, error messages |
| `jsonb(…)` | JSONB | Structured nested data (phases, Greeks, attributions) |
| `timestampWithTimeZone(…)` | TIMESTAMPTZ | Event timestamps and as-of dates (UTC) |
| `date(…)` | DATE | Valuation dates, baseline dates, attribution dates |
| `uuid(…)` | UUID | Job and manifest identifiers |

### Timestamp Strategy

All `TIMESTAMPTZ` columns store UTC values. The Exposed repository implementations convert between `kotlinx.datetime.Instant` and `java.time.OffsetDateTime` at UTC for database operations. `DATE` columns are used for calendar-day keys (valuation date, baseline date) where time-of-day is irrelevant.

### column name `source` vs. `dataSource`

Several services use the SQL column name `source` while Kotlin uses `dataSource` as the property name. This applies to: `prices.source`, `yield_curves.source`, `risk_free_rates.source`, `forward_curves.source`, `volatility_surfaces.source`, `dividend_yields.source`, `credit_spreads.source`, `instrument_liquidity.source`, and `factor_returns.source`. The Exposed `Table` object registers these as `varchar("source", 50)`.

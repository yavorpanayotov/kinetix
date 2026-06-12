# Services

Each service owns a single responsibility and a single PostgreSQL schema ([ADR-0011](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0011-database-per-service-isolation.md)). Cross-service data flows through Kafka or HTTP — never through shared tables.

## Gateway

**Path:** [`gateway/`](https://github.com/panayotovk/kinetix/tree/main/gateway)

The single point of entry for the UI. Aggregates backend service responses, validates Keycloak JWTs, enforces role-based access (`ADMIN`, `TRADER`, `RISK_MANAGER`, `COMPLIANCE`, `VIEWER`), rate-limits per principal, and fans out WebSocket subscriptions for price ticks, intraday P&L, regime changes, and alerts.

- **Tech:** Ktor 3.1, Koin, Resilience4j, WebSocket
- **Dependencies:** Keycloak (token introspection), every backend service over HTTP
- **ADRs:** [0012](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0012-api-gateway-aggregation-pattern.md), [0013](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0013-keycloak-for-authentication-and-rbac.md), [0016](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0016-websocket-for-real-time-ui-updates.md)

## Position Service

**Path:** [`position-service/`](https://github.com/panayotovk/kinetix/tree/main/position-service)

Trade lifecycle, positions, hierarchical limits, realised P&L, prime broker reconciliation. The system of record for booked trades and current holdings.

- Trade book / amend / cancel with idempotent processing
- Six-level pre-trade limit checks: Firm → Division → Desk → Book → Trader → Counterparty ([ADR-0023](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0023-hierarchical-limit-management.md))
- Multi-currency position aggregation with FX caching
- Realised P&L computed on position reduction with full audit
- Order execution: outbound `NewOrderSingle` requests to fix-gateway via gRPC; inbound `ExecutionReport` consumed from `execution.reports` topic
- Prime-broker reconciliation: scheduled jobs detect and persist breaks
- Selective event sourcing for the trade lifecycle ([ADR-0006](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0006-selective-event-sourcing.md))

## Price Service

**Path:** [`price-service/`](https://github.com/panayotovk/kinetix/tree/main/price-service)

Market data ingestion and distribution.

- TimescaleDB hypertables ([ADR-0005](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0005-timescaledb-for-time-series.md)) with continuous aggregates and retention policies
- Redis caching ([ADR-0015](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0015-redis-with-lettuce-for-shared-caching.md)) with TTL by instrument volatility class
- Kafka publishing to `price.updates`
- Stale-price anomaly detection and `risk.anomalies` events

## Rates Service

**Path:** [`rates-service/`](https://github.com/panayotovk/kinetix/tree/main/rates-service)

Risk-free curves, forward curves, yield curve construction. Publishes to `rates.yield-curves`, `rates.forwards`, `rates.risk-free`. Curve-anomaly detection (missing nodes, non-monotonic forwards) ships to `risk.anomalies`.

## Volatility Service

**Path:** [`volatility-service/`](https://github.com/panayotovk/kinetix/tree/main/volatility-service)

Volatility surfaces with bilinear interpolation in (log K, √T) per [ADR-0033](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0033-vol-surface-diff-method.md). Surface diffs and "what changed" tooling for daily comparison.

## Correlation Service

**Path:** [`correlation-service/`](https://github.com/panayotovk/kinetix/tree/main/correlation-service)

Correlation matrices with Ledoit-Wolf shrinkage. Estimation jobs run on rolling windows; outputs published to `correlation.matrices`.

## Reference Data Service

**Path:** [`reference-data-service/`](https://github.com/panayotovk/kinetix/tree/main/reference-data-service)

Instruments, organisational hierarchy, counterparties, credit ratings, dividend yields, credit spreads.

- 11 instrument types modelled as sealed-interface subtypes ([ADR-0020](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0020-sealed-interface-instrument-type-hierarchy.md)): equities, bonds, IR swaps, FX forwards/spot, FX options, equity options, commodity futures/options, credit (CDS), inflation, structured
- Organisational hierarchy materialised for fast roll-up

## Risk Orchestrator

**Path:** [`risk-orchestrator/`](https://github.com/panayotovk/kinetix/tree/main/risk-orchestrator)

The brain. Coordinates risk calculations across the platform.

- **Five-phase pipeline** ([ADR-0021](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0021-risk-orchestration-architecture.md)): fetch positions → discover dependencies → fetch market data → valuate → publish
- **Discovery-valuation contract** ([ADR-0029](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0029-discovery-valuation-two-phase-contract.md)): risk engine is a pure function; orchestrator owns all I/O
- **Unified valuation RPC** ([ADR-0024](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0024-unified-valuation-rpc.md)): single Valuate RPC with `requested_outputs`, market-data `oneof`, and Monte Carlo seed for reproducibility
- **Cross-book aggregation:** multi-book VaR with correlation matrices; results promoted from a shared cache to avoid recomputation
- **P&L attribution:** Greek decomposition against SOD baselines, using pricing Greeks per [ADR-0032](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0032-intraday-pnl-greek-source.md)
- **What-if engine:** hypothetical trade simulation with full risk re-computation
- **EOD promotion governance** ([ADR-0019](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0019-official-eod-labeling-with-promotion-governance.md))
- **Scheduled regime detection:** publishes regime transitions to `risk.regime.changes`

## Regulatory Service

**Path:** [`regulatory-service/`](https://github.com/panayotovk/kinetix/tree/main/regulatory-service)

Regulatory capital, model governance, submissions.

- **FRTB Standardised Approach:** SBM, DRC, RRAO — see [FRTB Capital](FRTB-Capital)
- **VaR backtesting:** Kupiec POF and Christoffersen independence tests with Basel traffic-light zones
- **Model registry:** four-stage lifecycle (draft / validated / approved / retired)
- **Regulatory submissions:** four-eyes approval (preparer cannot be approver)
- **Export templates:** CSV and XBRL

## Audit Service

**Path:** [`audit-service/`](https://github.com/panayotovk/kinetix/tree/main/audit-service)

Tamper-evident, immutable record of every significant platform event.

- SHA-256 hash chain ([ADR-0017](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0017-hash-chained-audit-trail.md)): `record_hash = SHA-256(payload || previous_hash)`
- Concurrent writes serialised with `pg_advisory_xact_lock` so the chain cannot fork
- TimescaleDB hypertable with 7-year retention
- DLQ replay tooling for operations

See [Audit and Compliance](Audit-and-Compliance) for the full chain protocol and verification flow.

## Notification Service

**Path:** [`notification-service/`](https://github.com/panayotovk/kinetix/tree/main/notification-service)

Alert rule engine and multi-channel delivery.

- **13 alert types:** VAR_BREACH, PNL_THRESHOLD, RISK_LIMIT, DELTA_BREACH, VEGA_BREACH, CONCENTRATION, MARGIN_BREACH, DATA_STALENESS, LIQUIDITY_CONCENTRATION, REGIME_CHANGE, FACTOR_CONCENTRATION, LIMIT_BREACH, RISK_BUDGET_EXCEEDED
- **Four channels:** in-app, email, webhook, PagerDuty
- Deduplication, debounce, auto-resolution, suggested actions
- Escalation workflow per `alert-escalation.allium`

## Fix Gateway

**Path:** [`fix-gateway/`](https://github.com/panayotovk/kinetix/tree/main/fix-gateway)

Venue and prime-broker FIX 4.4 connectivity, extracted from position-service per [ADR-0035](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0035-fix-gateway-service-extraction.md).

- Outbound `NewOrderSingle` via synchronous gRPC from position-service
- Inbound `ExecutionReport` published to `execution.reports`
- Session reconciliation, ghost-fill handling, mass-cancel-on-disconnect
- FIX message log persisted for replay and audit

## Risk Engine (Python)

**Path:** [`risk-engine/`](https://github.com/panayotovk/kinetix/tree/main/risk-engine)

Stateless gRPC calculator. No I/O outside RPC boundaries. Inputs in, results out.

**gRPC services exposed** (9):

| Service | Methods |
|---|---|
| `RiskCalculationService` | CalculateVaR, Valuate, CalculatePricingGreeks, CrossBookVaR, factor decomposition, hedge suggestion |
| `MLPredictionService` | Volatility forecast, credit PD |
| `StressTestService` | Scenario evaluation, reverse stress |
| `AttributionService` | P&L attribution (Greek decomposition) |
| `CounterpartyRiskService` | PFE, CVA, WWR |
| `LiquidityRiskService` | LVaR with liquidity-spread shocks |
| `RegulatoryReportingService` | FRTB SBM/DRC/RRAO |
| `MarketDataDependenciesService` | Sensitivity bucketing for discovery phase |
| `SaCcrService` | Standardised Approach to Counterparty Credit Risk |

**Module structure:**

- `var_*.py` — parametric, historical, Monte Carlo VaR
- `expected_shortfall.py` — CVaR
- `black_scholes.py`, `greeks.py`, `bond_pricing.py`, `swap_pricing.py`, `valuation.py` — pricing
- `frtb/` — SBM, DRC, RRAO, GIRR risk weights and correlations
- `factor_model.py` + `factor_server.py` — factor decomposition
- `regime_detector.py` — rule-based classifier
- `reverse_stress.py` — minimum-norm SLSQP solver
- `historical_replay.py` — crisis-period replays
- `backtesting.py` — Basel traffic-light framework
- `ml/` — Isolation Forest anomaly, LSTM vol forecast, NN credit PD

See [Risk Methodology](Risk-Methodology) for the math and references.

## UI

**Path:** [`ui/`](https://github.com/panayotovk/kinetix/tree/main/ui)

React 19 + TypeScript trading and risk dashboard. 12 tabs grouped into three clusters.

**Trading cluster:** Positions · Trades · P&L
**Risk cluster:** Risk · EOD History · Scenarios · Counterparty Risk
**Ops cluster:** Regulatory · Reports · Activity · Alerts · System

- Workspaces with saved views, auto-switch on tab/book selection
- Cross-tab navigation (e.g. counterparty row → filtered Trades blotter)
- Workspace-persisted collapse state on dashboard sections
- WCAG 2.1 accessibility — keyboard shortcuts overlay, focus management in dialogs, aria-invalid/aria-describedby on forms, focus restoration, screen-reader-friendly timer announcements
- Dark mode
- CSV export everywhere
- WebSocket streaming for prices, intraday P&L, alerts, regime
- Vitest unit tests + Playwright E2E tests in `ui/e2e/`

## Module dependencies

```
common ────────────► all Kotlin services
proto  ────────────► all Kotlin services + risk-engine
build-logic ───────► all Kotlin modules
test-support ──────► acceptance/integration test suites
```

`common` contains shared DTOs, Kafka adapters, HTTP utilities. `proto` contains gRPC service contracts. `build-logic` houses Gradle convention plugins (Kotest, Testcontainers, OpenTelemetry, Micrometer, JWT).

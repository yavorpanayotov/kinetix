<!-- BEGIN: Built with Claude Code hero -->
# Kinetix — Built with Claude Code

A multi-service institutional risk management platform built almost entirely through AI-assisted development.

- **2032 commits** across **~14 weeks**
- **12 microservices** (Kotlin/Ktor + Python risk engine + React/TS UI)
- **24 Allium behavioural specifications**
- **35 architectural decision records**

### Where to look next

- [How it was built](docs/HOW_IT_WAS_BUILT.md) — the AI-assisted-dev workflow, agents, and skills behind the codebase.
- [Architectural decision records](docs/adr/README.md) — every architectural choice, indexed.
- [Allium specifications](specs/README.md) — the behavioural specs that drive code and tests.

<!-- END: Built with Claude Code hero -->

## AI features

Two LLM-powered features ship in v1, routed through the host's Claude Code subscription via the [Claude Agent SDK](https://docs.anthropic.com/en/docs/claude-code/sdk) — no per-token API spend.

- **VaR Explainer** — on the Risk tab's VaR gauge, click **Explain** for a narrative + bullets that walk through the result and call out top contributors.
- **AI Commentary** — on the Reports tab, every generated report renders an AI Commentary card below it summarising drivers and any limit breaches.

Both features run through a Python `ai-insights-service` with a deterministic canned-mode fallback (`DEMO_MODE=true`). See [`ai-insights-service/README.md`](ai-insights-service/README.md) for the host-auth model, `DEMO_MODE` flag, and the response-shape contract.

# Kinetix

**Institutional-grade portfolio risk management platform.**

Kinetix covers the full risk lifecycle for a multi-asset trading desk — trade capture, hierarchical pre-trade limits, mark-to-market, live intraday P&L with Greek attribution, VaR/ES across three methodologies, options pricing, scenario and reverse-stress testing, regime-adaptive risk parameters, counterparty exposure with PFE and CVA, FRTB Standardised Approach capital, model governance with four-eyes approval, and a SHA-256 hash-chained audit trail. Built as a polyglot microservices monorepo: 12 Kotlin/Ktor services, a Python quantitative engine, and a React trading dashboard, glued together by Kafka, gRPC, and PostgreSQL/TimescaleDB.

## At a glance

| | |
|---|---|
| **Services** | 12 Kotlin/Ktor microservices on JVM 21 + 1 Python risk engine |
| **Risk engine** | Python 3.12 — NumPy, SciPy, PyTorch — exposes 11 gRPC services |
| **Frontend** | React 19 + TypeScript dashboard, 11 trader/risk tabs |
| **Datastores** | PostgreSQL 17 / TimescaleDB (database-per-service), Redis 7 |
| **Messaging** | Apache Kafka 3.9 (KRaft) — 20 production topics with per-topic DLQs |
| **Schema** | 173 Flyway migrations across 11 service schemas |
| **Behavioural specs** | 24 [Allium v3](https://github.com/juxt/allium) specifications |
| **Architecture decisions** | 35 ADRs in [`docs/adr/`](docs/adr/) |
| **Tests** | 915 — 561 Kotlin (Kotest) · 79 Python (pytest) · 191 Vitest · 84 Playwright |
| **Observability** | Prometheus, Grafana, Loki, Tempo, OpenTelemetry |
| **Quality gates** | Coverage ratchet, mutation testing (Stryker, mutmut), property-based tests (Hypothesis), Gatling load tests |

## Architecture

```
                                +-------------+
                                |     UI      |  React 19 + TypeScript
                                +------+------+
                                       | REST / WebSocket
                                +------+------+
                                |   Gateway   |  Keycloak JWT, rate limit, WS fan-out
                                +------+------+
                                       |
        +-------------+-------------+---+------------+-------------+--------------+
        |             |             |                |             |              |
   +----+-----+  +----+-----+  +---+--------+  +-----+------+  +---+------+  +----+-----+
   | Position |  |  Price   |  |    Risk    |  |   Rates    |  |Reference |  |   Fix    |
   | Service  |  | Service  |  |Orchestrator|  | Volatility |  |   Data   |  | Gateway  |
   +----+-----+  +----+-----+  +----+--+----+  | Correlation|  +----+-----+  +----+-----+
        |             |             |  |       +------------+       |             |
        |        +----+----+        |  |  gRPC                       |        FIX 4.4
        |        |  Redis  |        |  +------+                      |   (venue / prime broker)
        |        +---------+        |         |                      |
        |                           |    +----+------+               |
        |                           |    |   Risk    |  Python       |
        |                           |    |  Engine   |  NumPy/SciPy  |
        |                           |    +-----------+  PyTorch      |
        |                           |                                |
        +--------+------------------+----------------+---------------+
                 |                                   |
        +--------+-----------------------------------+---------------+
        |                       Apache Kafka                         |
        |  trades.lifecycle · execution.reports · price.updates      |
        |  risk.results · risk.cross-book-results · risk.pnl.intraday|
        |  risk.regime.changes · risk.anomalies · risk.official-eod  |
        |  limits.breaches · governance.audit · kinetix.audit.chain  |
        +----+------------------+-----------------+------------------+
             |                  |                 |
       +-----+-----+    +-------+------+    +-----+--------+
       |   Audit   |    |  Regulatory  |    | Notification |
       |  Service  |    |   Service    |    |   Service    |
       +-----+-----+    +--------------+    +--------------+
             |
         PostgreSQL / TimescaleDB
         (hash-chained, immutable,
          7-year retention)
```

Each Kotlin service owns its own PostgreSQL schema (ADR-0011), communicates with peers via Kafka (ADR-0004) or HTTP through the gateway (ADR-0012), and crosses the language boundary to the Python risk engine via a unified valuation gRPC contract (ADR-0024, ADR-0029). The risk engine is a **pure calculator**: the orchestrator owns all market-data discovery and fetching, so risk runs are deterministic, replayable, and free of hidden I/O (ADR-0029, ADR-0018).

## Engineering hallmarks

The pieces that took the most thought are documented under [`docs/adr/`](docs/adr/). The ones worth surfacing:

- **Discovery–valuation two-phase contract** ([ADR-0029](docs/adr/0029-discovery-valuation-two-phase-contract.md), [ADR-0024](docs/adr/0024-unified-valuation-rpc.md)) — risk-engine is a stateless function `(positions, market-data, seed) → results`. All data discovery and fetching is orchestrator-side. Combined with run manifests ([ADR-0018](docs/adr/0018-run-reproducibility-via-manifests.md)) this lets us replay any VaR run bit-for-bit from the captured inputs.
- **Hash-chained, tamper-evident audit trail** ([ADR-0017](docs/adr/0017-hash-chained-audit-trail.md)) — every audit event embeds `SHA-256(payload || previous_hash)`. A row-level `pg_advisory_xact_lock` serialises chain writes so concurrent producers can never fork the chain. Seven-year retention on TimescaleDB hypertables.
- **Six-level hierarchical limits** ([ADR-0023](docs/adr/0023-hierarchical-limit-management.md)) — pre-trade checks roll up Firm → Division → Desk → Book → Trader → Counterparty in a single pass. Temporary limit increases are first-class entities with their own approval workflow.
- **EOD promotion governance** ([ADR-0019](docs/adr/0019-official-eod-labeling-with-promotion-governance.md)) — only fully completed runs can become `OFFICIAL_EOD`. Promotion is a separate, audited action with a four-eyes rule. Reports and regulatory submissions reference frozen promoted runs, not whichever scheduled run happened to finish last.
- **Regime-adaptive VaR parameters** — a rule-based classifier (NORMAL / ELEVATED_VOL / CRISIS / RECOVERY) with debounced transitions auto-selects calculation method, confidence level, and time horizon. Behaviour on degraded inputs is explicitly specified ([ADR-0034](docs/adr/0034-regime-degraded-signal-policy.md)): a transition only fires when both available signals agree.
- **FIX gateway extraction** ([ADR-0035](docs/adr/0035-fix-gateway-service-extraction.md)) — venue/FIX-protocol concerns isolated in a dedicated service so position-service can stay focused on state. Inbound execution reports flow over Kafka; outbound `NewOrderSingle` is a synchronous gRPC.
- **Backward-compatible Flyway migrations** ([ADR-0025](docs/adr/0025-flyway-backward-compatible-migrations.md), [ADR-0027](docs/adr/0027-database-migration-practices.md)) — expand-contract split across two releases, transaction-incompatible statements (e.g. `CREATE INDEX CONCURRENTLY`) caught at review, rollback files alongside every migration.
- **DLQ + circuit breaker resilience** ([ADR-0014](docs/adr/0014-resilience-patterns-dlq-circuit-breaker.md)) — every Kafka consumer wraps in a `RetryableConsumer` with bounded retries and per-topic DLQs. Inter-service HTTP calls are guarded by circuit breakers.
- **Correlation IDs end-to-end** ([ADR-0022](docs/adr/0022-correlation-id-propagation.md)) — a UUID `correlationId` flows through every Kafka header and HTTP request so a single trace links UI click → API call → Kafka event → risk run → audit row.

## Quant & risk methodology

| Capability | Method | Implementation |
|---|---|---|
| **VaR — Parametric** | Delta-Normal | `risk-engine/src/kinetix_risk/var_parametric.py` |
| **VaR — Historical** | Empirical, sqrt-of-time scaling | `var_historical.py` |
| **VaR — Monte Carlo** | 10K paths, antithetic variates | `var_monte_carlo.py` |
| **Expected Shortfall** | CVaR at 97.5% (Basel FRTB) | `expected_shortfall.py` |
| **Cross-book VaR** | Multi-book aggregation with correlation matrices, hierarchy roll-up | `cross_book_var.py` + `ScheduledCrossBookVaRCalculator.kt` |
| **Greeks — analytical** | Black-Scholes-Merton (Δ, Γ, ν, Θ, ρ) with continuous dividend yield | `black_scholes.py` |
| **Cross-Greeks** | Vanna, Volga, Charm — analytical BSM | `greeks.py` |
| **Bond pricing** | DV01, key rate durations across 4-tenor internal grid; 12-tenor FRTB GIRR extension ([ADR-0028](docs/adr/0028-key-rate-duration-tenor-buckets.md)) | `bond_pricing.py`, `key_rate_duration.py` |
| **Swap pricing** | Discount-curve based IRS valuation | `swap_pricing.py` |
| **P&L attribution** | Greek decomposition (Δ, Γ, ν, Θ, ρ, unexplained); pricing-Greek source ([ADR-0032](docs/adr/0032-intraday-pnl-greek-source.md)) | `attribution_server.py`, `PnLAttributionDeriver.kt` |
| **Brinson attribution** | Allocation vs. selection decomposition | `brinson.py` |
| **Factor risk** | Five systematic factors (equity β, rates duration, credit spread, FX delta, vol exposure) — OLS and analytical loadings | `factor_model.py`, `factor_server.py` |
| **Historical replay** | GFC 2008, COVID 2020, Taper Tantrum 2013, Euro Crisis 2011 | `historical_replay.py` |
| **Reverse stress** | Minimum-norm SLSQP solver — smallest shock producing a target loss | `reverse_stress.py` |
| **Custom scenarios** | Multi-factor parametric shocks, correlation override, liquidity stress | `stress_server.py`, scenario governance pipeline |
| **FRTB SBM** | Sensitivities-Based Method — GIRR, equity, FX, commodity, credit spread; bucket correlations per Basel | `frtb/sbm.py`, `frtb/girr_correlations.py` |
| **FRTB DRC** | Default Risk Charge — credit-rating PDs, seniority LGD, maturity weighting, sector concentration | `frtb/drc.py`, `frtb/drc_enhanced.py` |
| **FRTB RRAO** | Residual Risk Add-On for exotics | `frtb/rrao.py` |
| **SA-CCR** | Standardised Approach to Counterparty Credit Risk | `sa_ccr.py`, `sa_ccr_server.py` |
| **Counterparty PFE** | Monte Carlo, 95th/99th percentile across tenor buckets | `credit_exposure.py`, `counterparty_risk_server.py` |
| **CVA** | Discrete approximation using CDS-implied or Basel default probabilities | `credit_exposure.py` |
| **Wrong-way risk** | Sector-match taxonomy ([ADR-0031](docs/adr/0031-wrong-way-risk-sector-taxonomy.md)) | `counterparty_risk_server.py` |
| **VaR backtesting** | Kupiec POF + Christoffersen independence; Basel traffic-light zones | `backtesting.py` |
| **Vol surface diff** | Bilinear interpolation in (log K, √T) ([ADR-0033](docs/adr/0033-vol-surface-diff-method.md)) | `volatility.py` |
| **Regime detection** | Rule-based classifier with debounced transitions; degraded-input policy ([ADR-0034](docs/adr/0034-regime-degraded-signal-policy.md)) | `regime_detector.py`, `ScheduledRegimeDetector.kt` |
| **Hedge optimisation** | Constrained optimiser minimising target Greeks / VaR, with cost model | `hedge_optimizer.py` |
| **ML — anomaly detection** | Isolation Forest on price/vol streams | `ml/anomaly_detector.py` |
| **ML — vol forecasting** | LSTM (PyTorch) | `ml/vol_predictor.py` |
| **ML — credit PD** | Neural net classifier | `ml/credit_model.py` |

## Services

| Service | Language | Responsibilities |
|---|---|---|
| **Gateway** | Kotlin | REST/WebSocket aggregation, Keycloak JWT, rate limiting, role-based access (`ADMIN`, `TRADER`, `RISK_MANAGER`, `COMPLIANCE`, `VIEWER`) |
| **Position Service** | Kotlin | Trade book/amend/cancel with idempotent processing, six-level hierarchical limits, real-time positions, realised P&L, prime broker reconciliation, counterparty exposure |
| **Price Service** | Kotlin | Market-data ingestion, TimescaleDB hypertable storage with continuous aggregates, Redis caching, Kafka publishing |
| **Rates Service** | Kotlin | Risk-free curves, forward curves, yield curve anomaly detection |
| **Volatility Service** | Kotlin | Volatility surfaces with bilinear (log K, √T) interpolation |
| **Correlation Service** | Kotlin | Correlation matrices with Ledoit-Wolf shrinkage |
| **Reference Data Service** | Kotlin | Instruments (11 sealed-interface subtypes), org hierarchy, counterparties, credit ratings, dividend yields, credit spreads |
| **Risk Orchestrator** | Kotlin | Five-phase risk pipeline (positions → discover → fetch → valuate → publish), cross-book aggregation, P&L attribution, what-if engine, EOD promotion, SOD baselines, scheduled regime detection |
| **Regulatory Service** | Kotlin | FRTB Standardised Approach, VaR backtesting, model registry with four-stage lifecycle, regulatory submissions with four-eyes approval, XBRL/CSV templates |
| **Audit Service** | Kotlin | Hash-chained immutable audit trail, DLQ replay, 7-year TimescaleDB retention |
| **Notification Service** | Kotlin | Alert rule engine (13 alert types), debounced/deduplicated delivery via in-app/email/webhook/PagerDuty, escalation, anomaly subscriptions |
| **Fix Gateway** | Kotlin | FIX 4.4 venue connectivity, `NewOrderSingle`/`ExecutionReport` lifecycle, session reconciliation, mass-cancel-on-disconnect ([ADR-0035](docs/adr/0035-fix-gateway-service-extraction.md)) |
| **Risk Engine** | Python | Stateless gRPC calculator: VaR (3 methods), ES, Greeks, BSM/bond/swap pricing, FRTB SBM/DRC/RRAO, SA-CCR, factor model, regime classifier, reverse stress, ML services |
| **UI** | TypeScript | React 19 trading + risk dashboard — 11 tabs in three clusters (Trading, Risk, Ops), workspaces with saved views, WCAG 2.1 accessibility, dark mode, CSV export, WebSocket streaming |

## Behavioural specifications

The platform's intended behaviour is formally specified in 24 [Allium v3](https://github.com/juxt/allium) files under [`specs/`](specs/). Each spec declares entities with lifecycle transition graphs, state-dependent field presence, rules with pre/post-conditions, and invariants — design documentation and a verifiable contract in one.

| Spec | Domain |
|---|---|
| `trading.allium` | Trade booking, amend, cancel; event publishing |
| `positions.allium` | Position aggregation, MTM, realised P&L |
| `execution.allium` | Order lifecycle, FIX integration, fill processing |
| `limits.allium` | Six-level hierarchy, pre-trade checks, temporary increases |
| `risk.allium` | VaR/ES, Greeks, cross-book aggregation, EOD promotion |
| `risk-models.allium` | Quantitative model contracts (VaR, BSM, bond/swap, stress, FRTB) |
| `intraday-pnl.allium` | Streaming intraday P&L with Greek attribution |
| `discovery-valuation.allium` | Two-phase risk-engine contract |
| `hierarchy-risk.allium` | Multi-desk roll-up, VaR budgeting, marginal contribution |
| `factor-model.allium` | Systematic risk decomposition |
| `scenarios.allium` / `scenario-lifecycle.allium` | Historical replay, reverse stress, governance workflow |
| `regime.allium` | Market regime detection and adaptation |
| `regulatory.allium` | Model governance, backtesting, submissions, FRTB |
| `audit.allium` | Hash-chained audit invariants |
| `counterparty-risk.allium` | PFE, CVA, netting sets, wrong-way risk |
| `liquidity.allium` | LVaR, concentration, stressed liquidation |
| `hedge.allium` | Constrained hedge optimisation |
| `alerts.allium` / `alert-escalation.allium` | Alert rule engine and escalation |
| `eod-close.allium` | Automatic EOD trigger and promotion |
| `market-data.allium` | Price, rate, vol, correlation ingestion |
| `reference-data.allium` | Instruments, org hierarchy, users, benchmarks |
| `core.allium` | Shared value types (Money, TimeRange, CurvePoint) |

## Tech stack

| Layer | Technology |
|---|---|
| Languages | Kotlin 2.1 (JVM 21), Python 3.12, TypeScript 5.9 |
| Backend framework | Ktor 3.1, Koin, Kotlinx Serialization, Exposed 0.58 ORM |
| Risk engine | NumPy, SciPy, PyTorch 2.2, scikit-learn |
| Frontend | React 19, Tailwind CSS 4, Vite 7, Recharts |
| Datastores | PostgreSQL 17 / TimescaleDB (hypertables, continuous aggregates, retention policies) |
| Caching | Redis 7 (Lettuce 6.5) |
| Messaging | Apache Kafka 3.9 (KRaft) — 20 production topics with per-topic DLQs |
| Inter-service | gRPC 1.70 / Protobuf 4.29 |
| Auth | Keycloak 24 (OAuth2/OIDC, role-based access) |
| Observability | Micrometer, OpenTelemetry, Prometheus, Grafana, Loki, Tempo |
| Build | Gradle 9.3 (Kotlin DSL, convention plugins), uv, npm |
| Testing | Kotest, Testcontainers, MockK, pytest, Hypothesis, Vitest, Playwright, Gatling, Stryker, mutmut |
| CI/CD | GitHub Actions (parallel jobs per push) |
| Deployment | Docker, Helm, Kubernetes |

## Quick start

### Prerequisites

- **Java 21** (Temurin)
- **Python 3.12+** with [uv](https://docs.astral.sh/uv/)
- **Node.js 22** with npm
- **Docker** and Docker Compose

### Start

```bash
./dev-up.sh        # Infrastructure + all services + UI
```

### Try it out

Once everything is up:

1. Open the dashboard at <http://localhost:5173> and log in as `trader1` / `trader1`.
2. Go to **Trades**, place a buy order — e.g. 1,000 shares of `AAPL` at the live mid.
3. Switch to **Positions** to see the new line mark-to-market in real time.
4. Switch to **Risk** — VaR, ES, Greeks, and limit utilisation recompute within seconds.
5. The **Alerts** tab surfaces any limit warnings or breaches.

This exercises the full pipeline end-to-end: UI → gateway → position-service → Kafka (`trades.lifecycle`) → risk-orchestrator → risk-engine (gRPC) → notification-service → UI WebSocket.

### Stop

```bash
./dev-down.sh
```

### URLs

| URL | Service |
|---|---|
| <http://localhost:5173> | Trading & Risk Dashboard |
| <http://localhost:8080> | Gateway API |
| <http://localhost:3000> | Grafana (admin/admin) |
| <http://localhost:9090> | Prometheus |
| <http://localhost:8180> | Keycloak (admin/admin) |

### Default users

| Username | Password | Role |
|---|---|---|
| trader1 | trader1 | TRADER |
| risk_mgr | risk_mgr | RISK_MANAGER |
| compliance1 | compliance1 | COMPLIANCE |
| admin | admin | ADMIN |

## Testing & quality gates

Every push runs the full suite as parallel CI jobs. Acceptance tests use real Postgres and Kafka via Testcontainers and a real in-JVM gRPC server bound on a random port — interceptors, serialisation, and channel wiring are all exercised. Transport is never mocked.

```bash
# Kotlin
./gradlew test                                    # Unit tests (all modules)
./gradlew acceptanceTest                          # Acceptance tests (route + contract)
./gradlew integrationTest                         # Integration tests (Testcontainers)
./gradlew :end2end-tests:end2EndTest              # End-to-end across services

# Python risk engine
cd risk-engine && uv run pytest                   # Unit + integration
cd risk-engine && uv run pytest -m unit           # Unit only

# UI
cd ui && npm run test                             # Vitest unit tests
cd ui && npx playwright test                      # Playwright browser tests

# Load
./gradlew :load-tests:gatlingRun                  # Gatling performance tests
```

A coverage ratchet gates merges, mutation testing (Stryker for UI, mutmut for Python) keeps assertion quality honest, and Hypothesis property-based tests sit alongside example-based pytest for the risk engine. See [`CLAUDE.md`](CLAUDE.md) for the testing philosophy (TDD/BDD, naming, coverage expectations).

## Architecture Decision Records

35 ADRs — 32 Accepted, 3 Proposed. Every ADR has an **Applies when** trigger list and an imperative **Rules** section, so the decision *and* the resulting code contract are both explicit. Full index and by-task lookup table in [`docs/adr/`](docs/adr/README.md).

Highlights:

| # | Decision |
|---|---|
| [0011](docs/adr/0011-database-per-service-isolation.md) | Database-per-service isolation |
| [0017](docs/adr/0017-hash-chained-audit-trail.md) | Hash-chained audit trail (SHA-256 + advisory lock) |
| [0018](docs/adr/0018-run-reproducibility-via-manifests.md) | Run reproducibility via manifests |
| [0019](docs/adr/0019-official-eod-labeling-with-promotion-governance.md) | Official EOD labeling with promotion governance |
| [0020](docs/adr/0020-sealed-interface-instrument-type-hierarchy.md) | Sealed-interface instrument hierarchy |
| [0021](docs/adr/0021-risk-orchestration-architecture.md) | Risk orchestration architecture (five phases) |
| [0023](docs/adr/0023-hierarchical-limit-management.md) | Six-level hierarchical limit management |
| [0024](docs/adr/0024-unified-valuation-rpc.md) | Unified valuation RPC |
| [0028](docs/adr/0028-key-rate-duration-tenor-buckets.md) | KRD tenor buckets — 4 internal, 12 for FRTB GIRR |
| [0029](docs/adr/0029-discovery-valuation-two-phase-contract.md) | Discovery–valuation two-phase contract |
| [0031](docs/adr/0031-wrong-way-risk-sector-taxonomy.md) | Wrong-way risk sector taxonomy |
| [0032](docs/adr/0032-intraday-pnl-greek-source.md) | Greek source for intraday P&L |
| [0033](docs/adr/0033-vol-surface-diff-method.md) | Vol-surface diff method |
| [0034](docs/adr/0034-regime-degraded-signal-policy.md) | Regime classifier behaviour on degraded inputs |
| [0035](docs/adr/0035-fix-gateway-service-extraction.md) | Fix-gateway service extraction |

## Project structure

```
kinetix/
├── gateway/                 API gateway (auth, routing, rate limiting, WS fan-out)
├── position-service/        Trades, positions, limits, execution, reconciliation
├── price-service/           Price ingestion + TimescaleDB hypertables
├── rates-service/           Risk-free and forward curves
├── volatility-service/      Volatility surfaces
├── correlation-service/     Correlation matrices (Ledoit-Wolf)
├── reference-data-service/  Instruments, org hierarchy, counterparties
├── risk-orchestrator/       Risk pipeline coordinator (5 phases)
├── audit-service/           Hash-chained immutable audit trail
├── regulatory-service/      FRTB, model governance, scenarios, submissions
├── notification-service/    Alert rules and multi-channel delivery
├── fix-gateway/             FIX 4.4 venue connectivity (ADR-0035)
├── risk-engine/             Python quantitative engine (gRPC)
├── ui/                      React 19 trading and risk dashboard
├── proto/                   Protobuf / gRPC service contracts
├── common/                  Shared Kotlin library
├── specs/                   24 Allium v3 behavioural specifications
├── end2end-tests/           End-to-end API tests
├── schema-tests/            Kafka event schema compatibility tests
├── smoke-tests/             Post-deploy smoke checks
├── load-tests/              Gatling performance tests
├── deploy/                  Docker, Helm, Kubernetes configs
└── docs/                    ADRs, glossary, runbooks, plans
```

## Documentation map

Recommended reading order for new contributors:

1. **[`CLAUDE.md`](CLAUDE.md)** — project conventions, testing philosophy, design principles, guardrails. Start here.
2. **[`docs/adr/README.md`](docs/adr/README.md)** — by-task lookup table maps "I am about to add a Kafka topic" / "I am about to write a Flyway migration" to the ADRs you must read first.
3. **[`docs/glossary/`](docs/glossary/)** — `kinetix.md` for platform-specific terms (limit hierarchy, Kafka topics, audit chain); `generic.md` for finance terminology (VaR, Greeks, FRTB).
4. **[`specs/`](specs/)** — Allium behavioural contracts. Start with `core.allium`, `trading.allium`, `risk.allium`.
5. **[`docs/runbooks/`](docs/runbooks/)** — operational procedures (zero-downtime deploy, etc.).
6. **`*/README.md`** — service-level READMEs where they exist (`ui/README.md`, `risk-engine/README.md`).

For contributors:

- Follow strict TDD — write a failing test first, then make it pass.
- Every backend feature needs unit + acceptance tests; every UI feature needs Vitest + Playwright coverage.
- Don't add libraries, modify CI files, or change architecture without explicit approval — see the **Guardrails** section of [`CLAUDE.md`](CLAUDE.md).

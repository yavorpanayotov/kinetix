# Audit: risk-orchestrator split-boundary analysis

**Issue:** kx-om4x  
**Auditor:** Elena (principal engineer)  
**Date:** 2026-05-29  
**Total source lines:** 26,704 across 532 files  

---

## Top 20 files by LoC

| # | File (relative to `com/kinetix/risk/`) | LoC | One-line purpose |
|---|----------------------------------------|-----|------------------|
| 1 | `Application.kt` | 951 | Service entry point — wires every collaborator, registers routes, launches scheduled jobs |
| 2 | `routes/RiskRoutes.kt` | 746 | HTTP routes for VaR, Greeks, stress tests, what-if, FRTB, regulatory reports |
| 3 | `service/VaRCalculationService.kt` | 701 | Core orchestration pipeline: fetch positions → discover deps → fetch market data → valuate → publish result |
| 4 | `persistence/ExposedValuationJobRecorder.kt` | 689 | Postgres persistence of valuation job lifecycle (5-phase tracking, promoted EOD flag) |
| 5 | `service/HedgeRecommendationService.kt` | 399 | ML-guided hedge suggestions — reads VaR cache, screens candidate instruments, sizes hedges analytically |
| 6 | `service/MarketDataFetcher.kt` | 392 | Parallel, circuit-breaker-wrapped fan-out to price/rates/vol/corr/refdata services |
| 7 | `routes/RiskMappers.kt` | 371 | Mapping helpers shared across route files (proto ↔ DTO ↔ domain) |
| 8 | `service/IntradayPnlService.kt` | 370 | Intraday P&L via Greek-Taylor expansion on live position moves; publishes to Kafka |
| 9 | `service/CounterpartyRiskOrchestrationService.kt` | 336 | PFE/CVA Monte Carlo orchestration per netting set; persists exposure snapshots |
| 10 | `service/SodSnapshotService.kt` | 310 | Start-of-day VaR + pricing-Greek snapshot creation and baseline status management |
| 11 | `seed/DevDataSeeder.kt` | 308 | Seed demo data at startup (dev/demo environments only) |
| 12 | `service/DefaultRunManifestCapture.kt` | 265 | Captures and persists reproducibility manifests (position snapshots + market data blobs + content hashes) |
| 13 | `routes/RunComparisonRoutes.kt` | 260 | HTTP routes for run-comparison, model-comparison, VaR attribution, and quant diff |
| 14 | `service/CrossBookVaRCalculationService.kt` | 257 | Aggregates positions across multiple books and runs a combined VaR through the risk engine |
| 15 | `service/ReplayService.kt` | 245 | Replays a historical run from its manifest (frozen positions + market data blobs) |
| 16 | `service/MarketDataQuantDiffer.kt` | 240 | Classifies the quantitative magnitude of market-data changes across run comparisons |
| 17 | `service/HierarchyRiskService.kt` | 234 | Aggregates VaR up the book → desk → division → firm hierarchy; tracks budget utilisation |
| 18 | `persistence/ExposedHedgeRecommendationRepository.kt` | 229 | Postgres persistence for hedge recommendations with TTL expiry |
| 19 | `service/EodPromotionService.kt` | 226 | Promotes a completed VaR job to official EOD status; enforces four-eyes check |
| 20 | `cache/RedisVaRCache.kt` | 224 | Redis-backed VaR result cache with JSON serialisation and per-key TTL |

---

## Bounded contexts detected

Five coherent bounded contexts emerge from the code. Each has its own domain language, lifecycle, and persistence footprint.

### 1. VaR Pipeline (`var-pipeline`)

The core risk calculation loop. Owns:

- `VaRCalculationService` — the 5-phase pipeline (fetch positions, discover deps, fetch market data, valuate, publish)
- `MarketDataFetcher` — circuit-breaker fan-out to upstream market data services
- `DependenciesDiscoverer` — gRPC call to the risk engine to resolve which market data is needed
- `CrossBookVaRCalculationService` — multi-book aggregation variant of the same pipeline
- `GrpcRiskEngineClient` / `ResilientRiskEngineClient` — transport layer to the Python engine
- `ExposedValuationJobRecorder` — five-phase job lifecycle in Postgres (`valuation_jobs` table)
- `RedisVaRCache`, `RedisQuantDiffCache` — hot-path read cache
- `DefaultRunManifestCapture` + `ReplayService` — reproducibility and replay
- Scheduled triggers: `ScheduledVaRCalculator`, `ScheduledCrossBookVaRCalculator`, `ScheduledAutoCloseJob`
- Kafka consumers: `TradeEventConsumer`, `PriceEventConsumer`
- EOD workflow: `EodPromotionService`, `ScheduledAutoCloseJob`

### 2. P&L / SOD Analytics (`pnl-analytics`)

Daily P&L decomposition and start-of-day baseline management. Owns:

- `SodSnapshotService` — creates SOD VaR + pricing-Greek snapshots
- `IntradayPnlService` — Greek-Taylor intraday P&L with per-instrument attribution
- `PnlComputationService` — batch EOD P&L reconciliation
- `PnlAttributionService` — pure attribution calculation logic
- `SodBaselineRepository`, `DailyRiskSnapshotRepository`, `SodGreekSnapshotRepository`, `IntradayPnlRepository`

### 3. Firm-Level Risk / Hierarchy (`hierarchy-risk`)

Aggregation above the individual book. Owns:

- `HierarchyRiskService` — book → desk → division → firm roll-up via cross-book VaR
- `BudgetUtilisationService` — risk budget tracking and breach alerts
- `ExposedRiskHierarchySnapshotRepository`, `ExposedRiskBudgetAllocationRepository`
- Routes: `hierarchyRiskRoutes`, `riskBudgetRoutes`, `croReportRoutes`

This context depends on the VaR Pipeline for per-book VaR values (via the cache) but has its own domain language (hierarchy nodes, budget allocations, CRO reports).

### 4. Counterparty Credit Risk (`counterparty-risk`)

CCR calculations — PFE and SA-CCR. Owns:

- `CounterpartyRiskOrchestrationService` — netting-set-aware Monte Carlo PFE via gRPC
- `SaCcrService` — deterministic SA-CCR regulatory capital (BCBS 279)
- `ExposedCounterpartyExposureRepository`, `SaCcrResultRepository`
- Routes: `counterpartyRiskRoutes`, `saCcrRoutes`
- `ScheduledCounterpartyRiskCalculator`

This context is almost entirely decoupled from the VaR Pipeline: it reads positions directly from position-service, sends them to the risk engine's CCR endpoints, and writes its own tables.

### 5. ML Regime Detection + Hedge Recommendations (`market-intelligence`)

Forward-looking advisory features. Owns:

- `ScheduledRegimeDetector` — classifies market conditions via gRPC ML endpoint; debounces transitions
- `RegimeSignalProvider` — gathers vol + correlation signals for the regime classifier
- `HedgeRecommendationService` — suggests hedges by reading the VaR cache for Greeks and screening liquid instruments
- `AnalyticalHedgeCalculator` — analytical hedge sizing
- `ExposedMarketRegimeRepository`, `ExposedHedgeRecommendationRepository`
- `KafkaRegimeEventPublisher`
- Routes: `marketRegimeRoutes`, `hedgeRecommendationRoutes`

---

## Coupling analysis

The table below maps inter-context dependencies by type.

| Consumer → Provider | Mechanism | What crosses the boundary |
|---|---|---|
| VaR Pipeline → upstream services | HTTP + gRPC | Market data, positions, instruments |
| P&L Analytics → VaR Pipeline | **Direct Kotlin call** (`SodSnapshotService` injects `VaRCalculationService`) | `ValuationResult`, `VaRCache` |
| P&L Analytics → VaR Pipeline | **Shared Postgres DB** | `valuation_jobs`, `daily_risk_snapshots` tables read by both |
| Hierarchy Risk → VaR Pipeline | **Shared cache** (`VaRCache`) | Per-book VaR results |
| Hierarchy Risk → VaR Pipeline | **Direct Kotlin call** (`CrossBookVaRCalculationService` injected) | Cross-book calculations |
| ML / Hedge → VaR Pipeline | **Shared cache** (`VaRCache`) | Greeks read for hedge sizing |
| ML / Hedge → VaR Pipeline | **Regime state** injected into `VaRCalculationService` via lambda | `RegimeState` affects VaR parameters |
| Counterparty Risk → VaR Pipeline | None | Counterparty risk reads positions directly; no VaR dependency |
| All contexts | **Shared Postgres schema** | Single `RiskDatabaseFactory`; all repos write to the same database |
| All contexts | **Shared gRPC channel** | Single `ManagedChannel` to the risk engine; all gRPC stubs share it |
| All contexts | **Shared Kafka producer** | Single `KafkaProducer` passed to all publishers |

**Key coupling observations:**

1. `SodSnapshotService` calls `VaRCalculationService.calculateVaR()` directly — this is the tightest cross-context coupling. Extracting P&L Analytics would require exposing a VaR endpoint or event stream for it to consume.
2. `HierarchyRiskService` injects `CrossBookVaRCalculationService` and calls it synchronously. Hierarchy Risk cannot function without the VaR Pipeline being co-located or exposed over the wire.
3. `ScheduledRegimeDetector` feeds its `currentState` via a lambda into `VaRCalculationService` constructor — tight in-process coupling between ML Regime and VaR Pipeline.
4. All contexts share the same Postgres instance and are Flyway-migrated as one schema. No logical schema isolation exists today.
5. `Application.kt` at 951 lines is a super-constructor that hand-wires every service. It is the most visible symptom of the service boundaries not yet being enforced at the process level.

---

## Recommendation

**Stay as one service for now, but enforce internal module boundaries first.**

Here is my reasoning:

### Against splitting today

The four inter-context coupling paths (2, 3, 5 above) are all synchronous Kotlin calls within the same JVM. Extracting P&L Analytics or Hierarchy Risk into separate services would require:

- Exposing `VaRCalculationService.calculateVaR()` as an HTTP or gRPC endpoint (it is already an HTTP endpoint, but `SodSnapshotService` bypasses it with a direct call)
- Replacing the `VaRCache` shared-memory read with a remote cache or event subscription
- Replacing the `CrossBookVaRCalculationService` injection with a gRPC or HTTP call
- Replacing the regime-state lambda with a Kafka event or REST poll

None of these are technically hard, but each introduces a network hop and a new failure mode in the hot path. The VaR calculation is already under latency pressure (it is Kafka-triggered on trade events). Adding remote calls in the critical path before you have measured whether the current latency budget allows it is speculative.

The total codebase is 26,704 lines. By comparison, `position-service` or `price-service` are likely half this size. Large is not the same as wrong: the service has grown because Kinetix has deliberately accumulated all market-risk-adjacent features here, and the features are genuinely related.

### What extraction is worth considering — in order of readiness

**1. Counterparty Risk → `counterparty-risk-service` (high confidence, extract when the feature matures)**

This is the cleanest boundary in the codebase. `CounterpartyRiskOrchestrationService` and `SaCcrService` share no Kotlin types with the VaR Pipeline. They read positions from `position-service` independently. Their only shared infrastructure is Postgres (separate tables) and the gRPC channel (separate stubs). Extracting them would require:

- Moving `CounterpartyRiskOrchestrationService`, `SaCcrService`, their repositories, routes, and Kafka consumers
- Creating a new `counterparty-risk-service` module with its own Flyway schema
- Pointing the gateway at the new service

Migration order: (a) add an anti-corruption boundary by extracting an interface; (b) move the code; (c) update gateway routing; (d) delete the old code. The `counterparty-risk-service` boundary was clearly intended — it even references a dedicated gRPC stub (`CounterpartyRiskServiceGrpcKt`) that already exists in the proto module.

**2. ML Regime / Hedge Recommendations → `market-intelligence-service` (medium confidence, longer runway)**

The regime detector and hedge recommender are advisory, not on the critical VaR path. The coupling to VaR (shared cache read + regime-state lambda) is real but manageable: publish regime transitions on a Kafka topic; have `VaRCalculationService` consume it and maintain its own local copy. Hedge sizing reads from the cache, which is Redis-backed — the hedge service could read the same Redis namespace.

This extraction makes sense once the ML and advisory features grow further. It is not urgent today.

**3. P&L Analytics and Hierarchy Risk — do not extract yet**

`SodSnapshotService` calling `VaRCalculationService` directly is a real coupling, but it is correct coupling: SOD snapshots exist to freeze the result of a VaR run. If you separate them you either introduce a synchronous remote call (latency hit, failure surface) or you decouple via events and accept eventual consistency for SOD state — which has correctness implications for intraday P&L attribution. Hierarchy Risk's dependency on `CrossBookVaRCalculationService` is similarly structural. Do not extract these until you have profiled the latency budget and have a clear event-driven model for the inter-service contract.

### Immediate action: enforce internal boundaries without splitting the process

The payoff before any extraction is to eliminate `Application.kt` as a 951-line super-constructor. The pattern to follow is module-level `fun configure()` extension functions — one per bounded context — that each accept only the config and infrastructure handles they need, wire their own collaborators, and return the service objects they own. This makes the boundaries visible, testable, and refactorable without any deployment change.

Concretely:

```
Application.kt
  → varPipelineModule(config, db, grpcChannel, kafka, redis): VarPipelineServices
  → pnlAnalyticsModule(config, db, varPipelineServices): PnlServices
  → counterpartyRiskModule(config, db, grpcChannel): CounterpartyServices
  → marketIntelligenceModule(config, db, grpcChannel, redis, varPipelineServices): IntelServices
  → hierarchyRiskModule(config, db, varPipelineServices): HierarchyServices
```

Each module function lives in its own file and owns its wiring. `Application.kt` becomes a 50-line orchestrator. When you do extract Counterparty Risk, you copy that module file into the new service verbatim.

### Summary

| Context | Extract? | When | Why |
|---|---|---|---|
| VaR Pipeline | No | — | Core; everything depends on it |
| P&L Analytics | No | Not until event-driven SOD is designed | Direct VaR call, shared tables |
| Hierarchy Risk | No | Not until VaR cache API is stable | CrossBook VaR injection |
| Counterparty Risk | Yes | When feature matures to justify ops overhead | Near-zero coupling; clean boundary |
| ML Regime / Hedge | Eventually | When advisory features outgrow this service | Kafka-publishable regime events; Redis-readable cache |

---

*This document is an investigation artefact. No code was moved.*

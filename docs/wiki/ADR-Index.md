# ADR Index

37 Architecture Decision Records, all Accepted.

ADRs are **guidelines and guardrails** — every ADR has two sections beyond Context / Decision / Consequences / Alternatives:

- **Applies when** — concrete triggers ("adding a Kafka topic", "writing a Flyway migration"). If the task at hand matches a trigger, read the ADR before making changes.
- **Rules** — imperative DO / DON'T statements. The contract the implementing code must satisfy.

The canonical index, with a by-task lookup table, lives in [`docs/adr/README.md`](https://github.com/panayotovk/kinetix/blob/main/docs/adr/README.md).

## By task

| If you are… | Read |
|---|---|
| Adding a new Kotlin service or HTTP routes | 0001, 0002, 0008, 0011, 0012, 0013 |
| Adding or changing a Kotlin↔Python gRPC contract | 0003, 0024, 0029 |
| Producing or consuming a Kafka event | 0004, 0014, 0022 |
| Writing a Flyway migration | 0025, 0027, 0011, 0005 |
| Designing persistence for a new entity | 0009, 0006, 0011, 0017 |
| Writing or modifying Kotlin tests | 0007 |
| Adding a UI feature, panel, or dialog | 0010, 0012, 0016, 0030 |
| Adding caching | 0015 |
| Adding real-time UI updates | 0016 |
| Touching auth, roles, or permissions | 0013 |
| Adding a new instrument type or asset class | 0020, 0029, 0024 |
| Changing the VaR / valuation workflow | 0021, 0024, 0029, 0018 |
| Touching the audit trail | 0017, 0006 |
| Promoting / demoting EOD runs | 0019, 0018 |
| Working on pre-trade limits | 0023 |
| Touching KRD / DV01 / rates risk | 0028 |
| Touching counterparty / WWR / CVA | 0031 |
| Touching P&L attribution or Greeks | 0032, 0024 |
| Touching vol surfaces | 0033 |
| Touching the regime classifier | 0034 |
| Touching FIX / order placement / venue connectivity | 0035 |
| Tuning HPAs, memory limits, or Kafka consumer scaling | 0026 |
| Touching the AI copilot, MCP tools, citations, or insight narratives | 0036 |
| Touching service-to-service auth, TLS, or network trust boundaries | 0037 |

## Full list

| # | Decision | Status | Summary |
|---|---|---|---|
| [0001](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0001-use-monorepo-structure.md) | Use monorepo structure | Accepted | Single repository for Kotlin services, Python risk engine, React UI, and shared protobufs |
| [0002](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0002-ktor-over-spring-boot.md) | Use Ktor over Spring Boot | Accepted | Ktor 3.1 + Koin for DI as the web framework for every Kotlin service |
| [0003](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0003-grpc-for-python-integration.md) | gRPC for Kotlin↔Python | Accepted | gRPC + Protobuf for synchronous risk-engine calls |
| [0004](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0004-kafka-for-async-messaging.md) | Apache Kafka for async messaging | Accepted | Kafka 3.9 KRaft for event distribution; 20 production topics |
| [0005](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0005-timescaledb-for-time-series.md) | TimescaleDB for time-series | Accepted | Hypertables + continuous aggregates + retention policies for time-series tables |
| [0006](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0006-selective-event-sourcing.md) | Selective event sourcing | Accepted | Event sourcing only in position-service and audit-service; CRUD elsewhere |
| [0007](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0007-kotest-for-testing.md) | Kotest over JUnit 5 | Accepted | Kotest 5.9 with MockK and Testcontainers for all Kotlin test layers |
| [0008](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0008-grafana-stack-for-observability.md) | Grafana stack for observability | Accepted | Prometheus + Loki + Tempo + Grafana via OpenTelemetry |
| [0009](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0009-exposed-for-database-access.md) | Exposed for DB access | Accepted | Exposed 0.58 DSL API for type-safe database access |
| [0010](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0010-react-vite-for-frontend.md) | React + Vite for frontend | Accepted | React 19, Vite 7, TypeScript 5.9, Tailwind CSS 4 |
| [0011](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0011-database-per-service-isolation.md) | Database-per-service isolation | Accepted | Each service owns its own Postgres schema; no cross-service DB access |
| [0012](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0012-api-gateway-aggregation-pattern.md) | API gateway aggregation | Accepted | Gateway aggregates backend responses for the UI |
| [0013](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0013-keycloak-for-authentication-and-rbac.md) | Keycloak for auth + RBAC | Accepted | Keycloak issues JWTs; gateway validates; 5 roles |
| [0014](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0014-resilience-patterns-dlq-circuit-breaker.md) | DLQ + circuit breaker | Accepted | RetryableConsumer for Kafka, Resilience4j circuit breakers for HTTP |
| [0015](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0015-redis-with-lettuce-for-shared-caching.md) | Redis + Lettuce | Accepted | Lettuce 6.5 for VaR result and quant diff caching with TTL |
| [0016](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0016-websocket-for-real-time-ui-updates.md) | WebSocket for real-time UI | Accepted | Ktor WebSocket via PriceBroadcaster |
| [0017](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0017-hash-chained-audit-trail.md) | Hash-chained audit trail | Accepted | SHA-256 chain serialised by `pg_advisory_xact_lock` |
| [0018](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0018-run-reproducibility-via-manifests.md) | Run reproducibility via manifests | Accepted | RunManifest captures inputs/outputs for replay |
| [0019](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0019-official-eod-labeling-with-promotion-governance.md) | EOD/SOD labeling with promotion | Accepted | OFFICIAL_EOD promotion is a separate four-eyes action |
| [0020](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0020-sealed-interface-instrument-type-hierarchy.md) | Sealed-interface instruments | Accepted | 11 instrument types as sealed-interface subtypes |
| [0021](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0021-risk-orchestration-architecture.md) | Risk orchestration architecture | Accepted | Five-phase pipeline: fetch → discover → fetch market data → valuate → publish |
| [0022](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0022-correlation-id-propagation.md) | Correlation ID propagation | Accepted | UUID through Kafka headers, HTTP, gRPC, audit rows |
| [0023](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0023-hierarchical-limit-management.md) | Hierarchical limit management | Accepted | Six-level limits: Firm → Division → Desk → Book → Trader → Counterparty |
| [0024](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0024-unified-valuation-rpc.md) | Unified valuation RPC | Accepted | Single Valuate RPC with requested_outputs, market_data oneof, MC seed |
| [0025](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0025-flyway-backward-compatible-migrations.md) | Backward-compatible migrations | Accepted | Expand-contract split across two releases |
| [0026](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0026-hpa-scaling-metrics.md) | HPA scaling metrics | Accepted | CPU + memory baseline; Kafka consumer lag on consumer services |
| [0027](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0027-database-migration-practices.md) | DB migration practices | Accepted | Naming, transaction constraints, hypertable rules, rollback files |
| [0028](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0028-key-rate-duration-tenor-buckets.md) | KRD tenor buckets | Accepted | 4-tenor internal (2/5/10/30Y); 12-tenor FRTB GIRR |
| [0029](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0029-discovery-valuation-two-phase-contract.md) | Discovery-valuation contract | Accepted | Risk-engine is a pure calculator; orchestrator owns all data fetching |
| [0030](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0030-contextual-filter-dropdowns.md) | Contextual filter dropdowns | Accepted | Data-driven filter options derived from the live dataset with counts |
| [0031](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0031-wrong-way-risk-sector-taxonomy.md) | Wrong-way risk sector taxonomy | Accepted | Strict sector-match WWR per spec; replaces counterparty-only heuristic |
| [0032](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0032-intraday-pnl-greek-source.md) | Greek source for intraday P&L | Accepted | Use pricing Greeks from SodGreekSnapshot, not VaR Greeks, for attribution |
| [0033](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0033-vol-surface-diff-method.md) | Vol-surface diff method | Accepted | Bilinear in (log K, √T) for surfaces on differing grids |
| [0034](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0034-regime-degraded-signal-policy.md) | Regime classifier on degraded inputs | Accepted | Transition only fires when all available signals agree |
| [0035](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0035-fix-gateway-service-extraction.md) | Fix-gateway service extraction | Accepted | Venue/FIX-protocol concerns isolated in a dedicated service |
| [0036](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0036-ai-copilot-architecture.md) | AI Copilot architecture (v2) | Accepted | In-process MCP server with read-only tools, citation contract + policy guard, SSE chat, demo-mode canned clients |
| [0037](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0037-inter-service-trust-model.md) | Inter-service trust model | Accepted | mTLS for service-to-service gRPC in the prod profile; docker bridge network as the demo trust boundary |

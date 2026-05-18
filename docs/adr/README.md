# Architecture Decision Records

ADRs are guidelines **and** guardrails — they capture both *why* a decision was made and *what rules* code must follow because of it. Every ADR has two LLM-targeted sections in addition to the usual Context/Decision/Consequences/Alternatives:

- **Applies when** — concrete triggers ("adding a Kafka topic", "writing a Flyway migration", "adding an instrument type"). If the task at hand matches a trigger, read the ADR before making changes.
- **Rules** — imperative DO / DON'T statements. The contract the implementing code must satisfy.

## By task — which ADRs to consult

| If you are… | Read |
|---|---|
| Adding a new Kotlin service or HTTP routes | [0001](0001-use-monorepo-structure.md), [0002](0002-ktor-over-spring-boot.md), [0008](0008-grafana-stack-for-observability.md), [0011](0011-database-per-service-isolation.md), [0012](0012-api-gateway-aggregation-pattern.md), [0013](0013-keycloak-for-authentication-and-rbac.md) |
| Adding or changing a Kotlin↔Python gRPC contract | [0003](0003-grpc-for-python-integration.md), [0024](0024-unified-valuation-rpc.md), [0029](0029-discovery-valuation-two-phase-contract.md) |
| Producing or consuming a Kafka event | [0004](0004-kafka-for-async-messaging.md), [0014](0014-resilience-patterns-dlq-circuit-breaker.md), [0022](0022-correlation-id-propagation.md) |
| Writing a Flyway migration | [0025](0025-flyway-backward-compatible-migrations.md), [0027](0027-database-migration-practices.md), [0011](0011-database-per-service-isolation.md), [0005](0005-timescaledb-for-time-series.md) |
| Designing persistence for a new entity | [0009](0009-exposed-for-database-access.md), [0006](0006-selective-event-sourcing.md), [0011](0011-database-per-service-isolation.md), [0017](0017-hash-chained-audit-trail.md) |
| Writing or modifying Kotlin tests | [0007](0007-kotest-for-testing.md) |
| Adding a UI feature, panel, or dialog | [0010](0010-react-vite-for-frontend.md), [0012](0012-api-gateway-aggregation-pattern.md), [0016](0016-websocket-for-real-time-ui-updates.md), [0030](0030-contextual-filter-dropdowns.md) |
| Adding caching | [0015](0015-redis-with-lettuce-for-shared-caching.md) |
| Adding real-time UI updates | [0016](0016-websocket-for-real-time-ui-updates.md) |
| Touching auth, roles, or permissions | [0013](0013-keycloak-for-authentication-and-rbac.md) |
| Adding a new instrument type or asset class | [0020](0020-sealed-interface-instrument-type-hierarchy.md), [0029](0029-discovery-valuation-two-phase-contract.md), [0024](0024-unified-valuation-rpc.md) |
| Changing the VaR / valuation workflow | [0021](0021-risk-orchestration-architecture.md), [0024](0024-unified-valuation-rpc.md), [0029](0029-discovery-valuation-two-phase-contract.md), [0018](0018-run-reproducibility-via-manifests.md) |
| Touching the audit trail | [0017](0017-hash-chained-audit-trail.md), [0006](0006-selective-event-sourcing.md) |
| Promoting / demoting EOD runs | [0019](0019-official-eod-labeling-with-promotion-governance.md), [0018](0018-run-reproducibility-via-manifests.md) |
| Working on pre-trade limits | [0023](0023-hierarchical-limit-management.md) |
| Touching KRD / DV01 / rates risk | [0028](0028-key-rate-duration-tenor-buckets.md) |
| Touching counterparty / WWR / CVA | [0031](0031-wrong-way-risk-sector-taxonomy.md) |
| Touching P&L attribution or Greeks | [0032](0032-intraday-pnl-greek-source.md), [0024](0024-unified-valuation-rpc.md) |
| Touching vol surfaces | [0033](0033-vol-surface-diff-method.md) |
| Touching the regime classifier | [0034](0034-regime-degraded-signal-policy.md) |
| Touching FIX / order placement / venue connectivity | [0035](0035-fix-gateway-service-extraction.md) |
| Tuning HPAs, memory limits, or Kafka consumer scaling | [0026](0026-hpa-scaling-metrics.md) |

## Full list

| # | Decision | Status |
|---|---|---|
| [0001](0001-use-monorepo-structure.md) | Use monorepo structure | Accepted |
| [0002](0002-ktor-over-spring-boot.md) | Use Ktor over Spring Boot for Kotlin services | Accepted |
| [0003](0003-grpc-for-python-integration.md) | Use gRPC for Kotlin-Python integration | Accepted |
| [0004](0004-kafka-for-async-messaging.md) | Use Apache Kafka for asynchronous messaging | Accepted |
| [0005](0005-timescaledb-for-time-series.md) | Use TimescaleDB for time-series data | Accepted |
| [0006](0006-selective-event-sourcing.md) | Selective event sourcing for trade lifecycle | Accepted |
| [0007](0007-kotest-for-testing.md) | Use Kotest over JUnit 5 for Kotlin testing | Accepted |
| [0008](0008-grafana-stack-for-observability.md) | Use Grafana stack for observability | Accepted |
| [0009](0009-exposed-for-database-access.md) | Use Exposed for database access | Accepted |
| [0010](0010-react-vite-for-frontend.md) | Use React + Vite for frontend | Accepted |
| [0011](0011-database-per-service-isolation.md) | Database-per-service isolation | Accepted |
| [0012](0012-api-gateway-aggregation-pattern.md) | API gateway aggregation pattern | Accepted |
| [0013](0013-keycloak-for-authentication-and-rbac.md) | Keycloak for authentication and RBAC | Accepted |
| [0014](0014-resilience-patterns-dlq-circuit-breaker.md) | Resilience patterns — DLQ and circuit breaker | Accepted |
| [0015](0015-redis-with-lettuce-for-shared-caching.md) | Redis with Lettuce for shared caching | Accepted |
| [0016](0016-websocket-for-real-time-ui-updates.md) | WebSocket for real-time UI updates | Accepted |
| [0017](0017-hash-chained-audit-trail.md) | Hash-chained audit trail | Accepted |
| [0018](0018-run-reproducibility-via-manifests.md) | Run reproducibility via manifests | Accepted |
| [0019](0019-official-eod-labeling-with-promotion-governance.md) | Official EOD/SOD labeling with promotion governance | Accepted |
| [0020](0020-sealed-interface-instrument-type-hierarchy.md) | Sealed interface instrument type hierarchy | Accepted |
| [0021](0021-risk-orchestration-architecture.md) | Risk orchestration architecture | Accepted |
| [0022](0022-correlation-id-propagation.md) | Correlation ID propagation | Accepted |
| [0023](0023-hierarchical-limit-management.md) | Hierarchical limit management | Accepted |
| [0024](0024-unified-valuation-rpc.md) | Unified valuation RPC | Accepted |
| [0025](0025-flyway-backward-compatible-migrations.md) | Flyway backward-compatible migration convention | Accepted |
| [0026](0026-hpa-scaling-metrics.md) | HPA scaling metrics strategy | Proposed |
| [0027](0027-database-migration-practices.md) | Database migration practices and constraints | Accepted |
| [0028](0028-key-rate-duration-tenor-buckets.md) | Key rate duration — 4-tenor internal vs 12-tenor FRTB GIRR | Accepted |
| [0029](0029-discovery-valuation-two-phase-contract.md) | Discovery-valuation two-phase contract | Accepted |
| [0030](0030-contextual-filter-dropdowns.md) | Contextual filter dropdowns for data-driven types | Accepted |
| [0031](0031-wrong-way-risk-sector-taxonomy.md) | Wrong-way risk sector taxonomy | Accepted |
| [0032](0032-intraday-pnl-greek-source.md) | Greek source for intraday P&L attribution | Accepted |
| [0033](0033-vol-surface-diff-method.md) | Vol-surface diff method — interpolation vs nearest-neighbour | Accepted |
| [0034](0034-regime-degraded-signal-policy.md) | Regime classifier behaviour on degraded inputs | Accepted |
| [0035](0035-fix-gateway-service-extraction.md) | Fix-gateway service extraction | Proposed |

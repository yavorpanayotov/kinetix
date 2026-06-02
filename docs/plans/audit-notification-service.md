# Audit: notification-service bounded-context analysis (kx-2079)

**Date:** 2026-05-29
**Issue:** kx-2079
**Analyst:** Elena (principal-engineer agent)

---

## Clarification: the "725 files" figure

The issue title reads "725 files". The actual source tree has **42 Kotlin files totalling 3 100 LoC**. The 725 is the line count of `Application.kt`. Still worth auditing — 42 files and a 725-line Application are themselves diagnostic signals.

---

## Top 30 files by LoC

All 42 files are listed; only the top 30 by size are relevant to the bounded-context question.

| Rank | LoC | File | One-line purpose |
|------|-----|------|-----------------|
| 1 | 726 | `Application.kt` | Wiring: DI, Kafka consumer bootstrapping, all HTTP routes, two inline DTO clusters, and mapper functions — a god-file. |
| 2 | 497 | `seed/DevDataSeeder.kt` | Seeds demo alert rules and synthetic triggered events for local dev. |
| 3 | 189 | `persistence/ExposedAlertEventRepository.kt` | Postgres-backed CRUD + lifecycle transitions for `AlertEvent` rows. |
| 4 | 162 | `engine/RulesEngine.kt` | Evaluates enabled rules against a `RiskResultEvent`; fires, deduplicates, snooze-checks, and auto-resolves `AlertEvent`s. |
| 5 | 140 | `engine/extractors/MetricExtractor.kt` | 11 concrete extractors mapping `RiskResultEvent` fields to numeric values per `AlertType`. |
| 6 | 81 | `engine/AlertEscalationService.kt` | Time-based escalation: promotes severity, picks channels, re-routes, publishes audit events. |
| 7 | 77 | `persistence/InMemoryAlertEventRepository.kt` | In-memory `AlertEventRepository` for tests and the thin `module()` overload. |
| 8 | 76 | `kafka/RiskResultConsumer.kt` | Kafka consumer for `risk.results`; feeds events into `RulesEngine` then `DeliveryRouter`. |
| 9 | 75 | `kafka/MarketRegimeEventConsumer.kt` | Consumes `risk.regime.changes`; delegates alert creation to `RegimeChangeRule`. |
| 10 | 72 | `kafka/LimitBreachEventConsumer.kt` | Consumes `limits.breaches`; delegates alert creation to `LimitBreachRule`. |
| 11 | 69 | `model/AlertModels.kt` | Core domain types: `AlertRule`, `AlertEvent`, all lifecycle enums, state-machine `canTransitionTo`. |
| 12 | 67 | `engine/SuggestedActionGenerator.kt` | Generates human-readable reduction suggestion for VaR/risk-limit breaches using position breakdown. |
| 13 | 58 | `kafka/AnomalyEventConsumer.kt` | Consumes `risk.anomalies`; logs anomaly detections but does not (yet) produce alert events. |
| 14 | 57 | `persistence/DatabaseFactory.kt` | Flyway + HikariCP setup; exposes `dataSource` for the readiness checker. |
| 15 | 55 | `persistence/ExposedAlertRuleRepository.kt` | Postgres-backed `AlertRuleRepository`; upsert/findAll/delete. |
| 16 | 55 | `engine/RegimeChangeRule.kt` | Maps `MarketRegimeEvent` regime transitions to `AlertEvent` with severity per spec `REG_D-10`. |
| 17 | 50 | `model/AlertSeverity.kt` | Operator-tier routing enum (`LOW/MEDIUM/HIGH/CRITICAL`) with channel lists; distinct from event-level `Severity`. |
| 18 | 47 | `delivery/InAppDeliveryMetrics.kt` | Micrometer counters for in-app delivery success/failure, tagged by severity. |
| 19 | 44 | `engine/LimitBreachRule.kt` | Maps `LimitBreachEvent` HARD/SOFT severity to `AlertEvent`. |
| 20 | 42 | `engine/DuplicateAlertSuppressionWindow.kt` | In-memory sliding-window deduplication keyed on `(ruleId, entityId)`. |
| 21 | 38 | `persistence/ExposedAlertAcknowledgementRepository.kt` | Stores `AlertAcknowledgement` rows (who acked, when, notes). |
| 22 | 38 | `engine/AlertSnoozeWindow.kt` | In-memory per-`(ruleId, traderId)` snooze state. |
| 23 | 37 | `persistence/AlertEventRepository.kt` | Interface: save, findRecent, findActive, status transitions, snooze. |
| 24 | 37 | `delivery/InAppDeliveryService.kt` | Persists an `AlertEvent` to the repository; exposes `getRecentAlerts` for routes. |
| 25 | 33 | `audit/KafkaGovernanceAuditPublisher.kt` | Publishes `GovernanceAuditEvent` to `governance.audit` Kafka topic. |
| 26 | 29 | `persistence/AlertEventsTable.kt` | Exposed `Table` DDL for alert events. |
| 27 | 22 | `delivery/PagerDutyDeliveryService.kt` | Stub PagerDuty delivery (logs only; no HTTP call yet). |
| 28 | 18 | `routes/dtos/SnoozeAlertRequest.kt` | Request DTO for the snooze endpoint. |
| 29 | 18 | `delivery/WebhookDeliveryService.kt` | Stub webhook delivery (logs only). |
| 30 | (remaining 12 files < 18 LoC each) | Interfaces, table DDL, in-memory fakes, additional DTOs | Support/infrastructure |

---

## Bounded contexts detected

Reading the code through a domain lens reveals **two distinct bounded contexts** that happen to share a deployment unit:

### Context A — Alert Lifecycle (rules + events)

**What it owns:**
- `AlertRule`: the user-defined threshold condition (`type / operator / threshold / severity / channels`)
- `AlertEvent`: the event raised when a rule fires, with its full state machine (`TRIGGERED → ACKNOWLEDGED → ESCALATED → RESOLVED`)
- Lifecycle operations: acknowledge, escalate, resolve, snooze
- Deduplication and suppression (`DuplicateAlertSuppressionWindow`, `AlertSnoozeWindow`, auto-resolve)
- Rule evaluation (`RulesEngine`, `MetricExtractor`s, `LimitBreachRule`, `RegimeChangeRule`)
- Escalation policy (`AlertEscalationService`, `ScheduledAlertEscalation`, `SuggestedActionGenerator`)
- Audit trail for lifecycle transitions (publishes to `governance.audit`)
- The `AlertAcknowledgement` record (who/when/notes)

**Kafka inputs it consumes:** `risk.results`, `limits.breaches`, `risk.regime.changes`, `risk.anomalies`

**Questions it answers:** "Does this event breach a rule?", "Who should be notified?", "Has this alert been acted on?", "Should it escalate?"

### Context B — Delivery

**What it owns:**
- `DeliveryService` / `DeliveryRouter`: channel abstraction
- `InAppDeliveryService`, `EmailDeliveryService`, `WebhookDeliveryService`, `PagerDutyDeliveryService`
- `InAppDeliveryMetrics`
- `DeliveryChannel` enum

**Questions it answers:** "Given an alert and a set of channels, deliver it."

---

## Coupling analysis

### Where the contexts are entangled

1. **`InAppDeliveryService` owns the alert query path.** Context B's `InAppDeliveryService` holds the `AlertEventRepository` reference and exposes `getRecentAlerts`. The `notificationRoutes` function calls `inAppDelivery.repository` directly nine times. This means the delivery service is also the read model for the alert lifecycle — a responsibility that belongs to Context A.

2. **`Application.kt` is the only DI container, and it is enormous.** At 726 lines it contains: Kafka consumer wiring for four topics, all HTTP route definitions, lifecycle-operation logic (acknowledge/escalate/resolve/snooze with state-machine checks), two DTO clusters, and mapper functions. Nothing is delegated to a dedicated route file. Every concern leaks into the same file.

3. **`AlertEscalationService` calls `DeliveryRouter`.** This is a deliberate cross-context call: the lifecycle context needs delivery to re-page on escalation. This coupling is intentional and legitimate — the question is whether it warrants a service boundary or just a clean interface.

4. **`AnomalyEventConsumer` is a dead end.** It consumes `risk.anomalies` and logs but produces no `AlertEvent`. It belongs to lifecycle but delivers nothing — its purpose is unfinished.

5. **`AlertSeverity` vs `Severity`.** Two severity enums exist: `Severity` (event-level, INFO/WARNING/CRITICAL) and `AlertSeverity` (routing-tier, LOW/MEDIUM/HIGH/CRITICAL). `AlertSeverity` is defined but its `escalationChannels()` method is not called anywhere in production code. It is dead code today.

6. **Single Postgres schema.** `alert_rules`, `alert_events`, and `alert_acknowledgements` share one database. Splitting to two services would require either a shared schema (which defeats service isolation) or replication.

### Coupling severity

The lifecycle–delivery coupling is **loose at the domain level** but **tight at the infrastructure level** (shared DB, shared Application.kt, `InAppDeliveryService` leaking repository access). The two contexts do not need each other's types — `AlertEvent` flows one way from lifecycle to delivery, never back. No circular dependencies exist between the `engine` and `delivery` packages.

---

## Recommendation: **stay — refactor the internal structure, do not split**

**Verdict: one deployment unit, two well-separated internal modules.**

The two contexts are real and worth naming, but they do not meet the bar for a service split. Here is why:

1. **Volume does not justify the overhead.** 42 files, 3 100 LoC. That is a healthy single service, not a monolith. The "725 files" hypothesis was based on a misread of the line count.

2. **The coupling is architectural bad hygiene, not a bounded-context violation.** `InAppDeliveryService` owning the query path, and `Application.kt` containing route logic, are internal design problems. They can be fixed with a two-hour refactor: extract a `AlertQueryService` (or promote the repository directly to the routes), move route functions to a dedicated `routes/NotificationRoutes.kt`, and split the two DTO clusters to `routes/dtos/`. No service boundary change required.

3. **The lifecycle–delivery call is thin and unidirectional.** `AlertEscalationService` pushes an `AlertEvent` through `DeliveryRouter`. If these were two services, that call would become an HTTP or Kafka hop for a 20-line use case. The operational cost (latency, deployment coordination, separate test harnesses) exceeds any modularity benefit.

4. **Shared schema is the decisive constraint.** Alert lifecycle and in-app delivery share one `alert_events` table — delivery *is* writing to the same row that lifecycle reads. Separating services without splitting the table gives you a distributed monolith. Splitting the table requires a migration plan and dual-write period that far outweighs the benefit at current scale.

5. **The dead code (`AlertSeverity`, incomplete `AnomalyEventConsumer`) is clutter, not a context explosion.** It should be cleaned up in place.

### What to do instead

These are internal improvements, not a service split:

1. **Extract route logic from `Application.kt`** into `notification-service/.../routes/NotificationRoutes.kt`. Move the acknowledge/escalate/resolve/snooze handlers and their inline DTO types there. Target: `Application.kt` under 100 lines.

2. **Move `AlertEventRepository` access out of `InAppDeliveryService`.** The lifecycle routes should depend on `AlertEventRepository` directly (or on a new `AlertQueryService`). `InAppDeliveryService` should own only the write path. The nine `.repository` calls in the routes should be replaced.

3. **Promote route DTOs to `routes/dtos/` per the CLAUDE.md convention.** `AlertRuleResponse`, `AlertEventResponse`, `AcknowledgeAlertRequest`, `CreateAlertRuleRequest`, `ErrorResponse` all live in `Application.kt` today. Each should be its own file.

4. **Delete or complete `AnomalyEventConsumer`.** It consumes a topic but produces nothing. Either wire it through to an `AlertEvent` (completing the pattern the other consumers follow) or remove the subscription until the feature is ready.

5. **Delete `AlertSeverity` or use it.** It is a well-designed type that is never called. Either wire it into `DeliveryRouter` (replacing the ad-hoc channel selection in `AlertEscalationService`) or delete it before it confuses the next engineer.

---

## If a split were ever warranted (future reference)

If alert volume grows to the point where lifecycle processing and delivery latency are in tension, the split boundary would be:

**New service: `alert-service`** — owns Context A (rules, events, lifecycle state machine, evaluation, escalation policy, acknowledgements, audit trail).

**Retained: `notification-service`** — owns Context B (delivery channels only; becomes a dumb dispatcher).

**Contract between them:** a Kafka topic `alerts.triggered` (and `alerts.lifecycle`), published by `alert-service` after each state transition. `notification-service` consumes and routes to channels. This eliminates the current `AlertEscalationService → DeliveryRouter` direct call while keeping delivery independent of lifecycle schema.

**Migration order:** (1) introduce `alerts.triggered` topic and have `notification-service` produce it from the existing code path, (2) wire a new `alert-service` as the consumer of that topic with its own DB, (3) drain and decommission the lifecycle logic from `notification-service`.

This migration is straightforward precisely *because* the contexts are already well-separated at the code level — the domain model is clean even if the wiring is not.

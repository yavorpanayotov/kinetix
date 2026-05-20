# Event Observability — Audit v2

## Context

A 5-person review team (trader, architect, QA, UX, data-analyst) audited how easily a **user or support person can track events, results and risk activity** on the deployed platform (`kinetixrisk.ai`). The user's goal: *"make it very easy for users and support to track events, results, etc."*

The verdict: the foundations exist (structured JSON logging via `LogstashEncoder`, OTel → Loki/Tempo per ADR-0008, a hash-chained audit trail, a `correlationId` design in ADR-0022) but the **last mile is missing**. Concretely, all verified against the codebase:

- **No version-controlled Grafana dashboards.** `deploy/observability/` has only `prometheus.yml` + `alertmanager.yml`. All five reviewers flagged this as the #1 gap — Grafana is where "easy to track" lives, and there is nothing in it.
- **`prometheus.yml` references `alert-rules.yml` which does not exist** — local dev alerting is dead (dangling `rule_files` entry).
- **Alert rules use wrong metric names.** Helm `RiskCalculationSlow` queries `var_calculation_duration_seconds_bucket`; the risk-engine actually emits `risk_var_calculation_duration_seconds`. `KafkaConsumerLag` queries `kafka_consumer_group_lag`; kafka-exporter emits `kafka_consumergroup_lag`. `VaRBreached` annotations reference `{{ $labels.portfolio_id }}` but the label is `book_id`.
- **`correlationId` is not persisted on the audit trail.** `GovernanceAuditEvent` (common) and `AuditEvent` (audit-service model) have no `correlationId` field — you cannot join a Loki log line or a Tempo trace to the hash-chained audit record.
- **Failed risk runs are invisible in the audit trail.** `VaRCalculationService` publishes `RISK_CALCULATION_COMPLETED` on success but the failure `catch` block (sets `RunStatus.FAILED`) publishes nothing. `AuditEventType` has no `RISK_CALCULATION_FAILED`.
- **The audit query API is too narrow for support.** `AuditRoutes` `/events` filters by `bookId` only — no `tradeId`, `eventType`, or time-window filter. Answering "show all events for trade T" is impossible without a full scan.
- **No UI surface for the audit trail.** The audit API *is* reachable (gateway `auditProxyRoutes`), but no UI tab consumes it. Support must use raw Loki/LogQL.
- **`NotificationStrip` is mounted but starved.** `App.tsx:726` renders `<NotificationStrip items={[]} …>` — `morningBrief` is wired, live alerts/notifications are not.
- **Risk-engine logs are unstructured.** `server.py:441` uses `logging.basicConfig` with a plain-text format — `book_id` / `correlation_id` are not queryable fields in Loki.
- **No trace stitching across the gRPC boundary** — risk-orchestrator → risk-engine traces terminate at the Kotlin boundary; the Python engine is a black box in Tempo.
- **No Loki/Tempo retention configured** — defaults to indefinite filesystem growth.

This plan closes the last mile so that a trader, a risk manager, and a support engineer can each answer *"what happened, what did it produce, and why"* without writing LogQL.

## Status

This plan is **loop-ready** for `/work-plan`. Each `- [ ]` checkbox is one independently-committable change, ordered top-to-bottom by dependency, with an `Acceptance:` command on the line directly after it. Advance end-to-end with `/loop /work-plan plans/audit-v2.md`.

## Decisions applied

- **`correlationId` is operational metadata, not an audited fact.** It is stored as a column on `audit_events` and surfaced in the API/UI, but is **excluded from the `AuditHasher` hash-chain input**. Rationale: the hash-chain protects regulatory facts (who/what/when/trade detail); `correlationId` is a cross-reference pointer for debugging. Including it would (a) break every existing chain and (b) protect data that has no integrity requirement.
- **`correlationId` is nullable everywhere.** Additive optional field on `GovernanceAuditEvent` and the `audit_events` column — backward compatible, no schema-test break. Events published before this change simply have `null`.
- **Risk-engine structured logging uses the Python stdlib only.** A custom `logging.Formatter` emitting `json.dumps` — **no new dependency**. (`structlog` / `python-json-logger` are explicitly avoided to stay within guardrails.)
- **Dashboards are code.** Grafana dashboards live as JSON under `deploy/observability/dashboards/`, provisioned via a Grafana provider config, wired into both `docker-compose` (local) and the Helm observability chart (prod). No hand-built dashboards.
- **Three dashboards, business-event focused:** Trade Lifecycle, Risk Run Health, Business Alerts & Events. Each carries Loki/Tempo drill-through links keyed on `correlationId`, `tradeId`, and `bookId`.
- **The UI gets one new top-level "Activity" tab** consuming the existing gateway-proxied audit API. No new gateway route — the audit proxy already exists.
- **`RISK_CALCULATION_FAILED` only** (not a `STARTED` event). The failure-visibility gap is the reviewed need; a STARTED event is deferred (see Out of scope).
- **TDD discipline:** failing test + minimal implementation land in the same commit. Every commit builds green.

## CI/CD & guardrail approvals

Per CLAUDE.md, the following cross guardrail lines and are addressed up front:

- **Additive event-schema change** — `correlationId: String? = null` on `GovernanceAuditEvent` (topic `governance.audit`). Optional + nullable = backward compatible. Pre-approved; `schema-tests` must stay green (checkbox 2.5).
- **New `AuditEventType` enum value** `RISK_CALCULATION_FAILED` — additive. Pre-approved.
- **New Flyway migrations on `audit_events`** — `V13` (add `correlation_id` column), `V14` (add `trade_id` / `event_type` indexes). Plain `CREATE INDEX` (no `CONCURRENTLY` — runs inside Flyway's transaction). Pre-approved.
- **New config / dashboard files** under `deploy/observability/` and Helm `charts/observability` value edits — not CI/CD pipeline files. Pre-approved.
- **PR 6 (distributed gRPC tracing) new dependencies — APPROVED by the user (2026-05-20).** `opentelemetry-instrumentation-grpc` (Python, `risk-engine/pyproject.toml`) and the OpenTelemetry gRPC instrumentation library for the Kotlin gRPC client (`risk-orchestrator` — `io.opentelemetry.instrumentation:opentelemetry-grpc-1.6`, pulled via the existing OTel BOM where possible). Pre-approved; PR 6 may run in the loop.
- **No `.github/workflows/*` changes anticipated.** If a subagent finds it must touch one, STOP and flag.

## Out of scope

- **`RISK_CALCULATION_STARTED` audit event.** Useful for "a run began but never finished" but not the reviewed gap; revisit after `RISK_CALCULATION_FAILED` ships.
- **Per-book / per-currency dynamic VaR alert thresholds.** The `VaRBreached` rule keeps its single hardcoded threshold; making it data-driven is a separate piece of work.
- **Changing Kafka event timestamps from `String` to `Instant`.** Data-analyst flagged it; it is a cross-service wire-format change touching every consumer — defer to a dedicated schema migration.
- **High-cardinality Prometheus label review** (`book_id` on gauges). Note it; do not re-architect metrics here.
- **Replacing raw service logs with the audit trail as system-of-record.** Both coexist.
- **Alert-WebSocket server-side event replay/buffering.** PR 11 adds a *test* exposing the gap; building the buffer is follow-up work.

---

## Execution plan

### PR 0 — Baseline gate

- [x] 0.1 Confirm the modules this plan touches build green before any change lands — no new code, this is the gate.
      Acceptance: `./gradlew :common:test :audit-service:test :risk-orchestrator:test && cd risk-engine && uv run pytest -m unit && cd ../ui && npm run test`

### PR 1 — Fix the broken observability config

The existing alerting is silently dead. Make it actually fire before adding anything new.

- [x] 1.1 Create `deploy/observability/alert-rules.yml` (the file `prometheus.yml:11` already references but which does not exist). Port the four rule groups from the Helm chart, with **corrected metric names**: `var_calculation_duration_seconds_bucket` → `risk_var_calculation_duration_seconds_bucket`, `kafka_consumer_group_lag` → `kafka_consumergroup_lag`. Verify `price_staleness_seconds` against the price-service `/metrics` output and correct if needed.
      Acceptance: `test -f deploy/observability/alert-rules.yml && python3 -c "import yaml; g=yaml.safe_load(open('deploy/observability/alert-rules.yml')); assert g['groups']; print('ok')"`
- [x] 1.2 Fix the Helm observability chart (`deploy/helm/kinetix/charts/observability/values.yaml` `additionalPrometheusRulesMap`): correct the same metric names as 1.1, and fix the `VaRBreached` `summary`/`description` annotations to use `{{ $labels.book_id }}` instead of `{{ $labels.portfolio_id }}`.
      Acceptance: `python3 -c "import yaml; yaml.safe_load(open('deploy/helm/kinetix/charts/observability/values.yaml')); print('ok')" && ! grep -n 'portfolio_id' deploy/helm/kinetix/charts/observability/values.yaml && grep -q 'risk_var_calculation_duration_seconds' deploy/helm/kinetix/charts/observability/values.yaml`

### PR 2 — Correlation ID end-to-end

Make `correlationId` a reliable join key across Loki logs, Tempo traces and the hash-chained audit record.

- [ ] 2.1 Add `correlationId: String? = null` to `GovernanceAuditEvent` (`common/src/main/kotlin/com/kinetix/common/audit/GovernanceAuditEvent.kt`).
      Acceptance: `./gradlew :common:test`
- [ ] 2.2 Add Flyway migration `V13__add_correlation_id.sql` under `audit-service/src/main/resources/db/audit/` adding a nullable `correlation_id` column to `audit_events`; add the `correlationId` field to the `AuditEvent` model and its repository row mapper. **Do not** add it to `AuditHasher`'s hashed-field set (see Decisions).
      Acceptance: `./gradlew :audit-service:test :audit-service:integrationTest`
- [ ] 2.3 Populate `correlationId` from the SLF4J MDC when constructing `GovernanceAuditEvent` in every publisher — `risk-orchestrator` (`VaRCalculationService`, `EodPromotionService`, `CrossBookLimitCheckService`), `regulatory-service`, `notification-service`, `gateway` (`RouteAuthorization`).
      Acceptance: `./gradlew :risk-orchestrator:test :regulatory-service:test :notification-service:test :gateway:test`
- [ ] 2.4 In the audit-service Kafka consumers, inject the inbound event's `correlationId` into the MDC for the duration of processing **and** persist it onto the `AuditEvent`. Cover both the governance consumer and the trade-event consumer; add a test asserting a consumed event's `correlationId` reaches the stored record.
      Acceptance: `./gradlew :audit-service:test :audit-service:acceptanceTest`
- [ ] 2.5 Confirm the additive `GovernanceAuditEvent` field has not broken Kafka schema compatibility.
      Acceptance: `./gradlew :schema-tests:test`

### PR 3 — Failed risk-run audit events

A risk run that fails must leave a trace support can find.

- [ ] 3.1 Add `RISK_CALCULATION_FAILED` to the `AuditEventType` enum (`common/.../audit/AuditEventType.kt`).
      Acceptance: `./gradlew :common:test`
- [ ] 3.2 In `VaRCalculationService` (`risk-orchestrator`), publish a `RISK_CALCULATION_FAILED` `GovernanceAuditEvent` from the failure `catch` block (the one that sets `RunStatus.FAILED`), carrying `bookId`, `correlationId` and the error message in `details`. Write the failing test first: *"when the risk engine throws, a RISK_CALCULATION_FAILED governance event is published"*.
      Acceptance: `./gradlew :risk-orchestrator:test --tests "*VaRCalculationAuditTest"`
- [ ] 3.3 Confirm the audit-service governance consumer persists the new event type end-to-end (consumer → Kafka → DB). Add an acceptance test if the existing one does not cover `RISK_CALCULATION_FAILED`.
      Acceptance: `./gradlew :audit-service:acceptanceTest`

### PR 4 — Audit query API for support

Let support self-serve "show all events for trade X / type Y / last hour".

- [ ] 4.1 Add Flyway migration `V14__add_audit_query_indexes.sql` adding indexes on `audit_events(trade_id)` and `audit_events(event_type, received_at DESC)`. Plain `CREATE INDEX` (no `CONCURRENTLY`).
      Acceptance: `./gradlew :audit-service:integrationTest`
- [ ] 4.2 Extend `AuditRoutes` `/api/v1/audit/events` with optional `tradeId`, `eventType`, `from` and `to` (ISO-8601) query parameters, and the corresponding `AuditEventRepository` query methods. Keep cursor pagination. Write the failing acceptance test first.
      Acceptance: `./gradlew :audit-service:acceptanceTest --tests "*AuditRoutesAcceptanceTest"`
- [ ] 4.3 Verify the gateway audit proxy (`auditProxyRoutes`) forwards the new query parameters unchanged.
      Acceptance: `./gradlew :gateway:acceptanceTest`

### PR 5 — Structured JSON logging for the risk-engine

Make `book_id` / `correlation_id` / `calculation_type` queryable fields in Loki instead of printf text.

- [ ] 5.1 Add a stdlib-only JSON log formatter to the risk-engine (a `logging.Formatter` subclass emitting `json.dumps` with `timestamp`, `level`, `logger`, `message`, and any `extra` fields). Replace the plain-text `basicConfig` format in `server.py`. No new dependency. Write the test first asserting a log record serialises to parseable JSON with the expected keys.
      Acceptance: `cd risk-engine && uv run pytest tests/test_logging.py -m unit`
- [ ] 5.2 Thread `correlation_id`, `book_id` and `calculation_type` through as structured `extra` fields on the log calls in the VaR / Greeks / stress calculation entry points.
      Acceptance: `cd risk-engine && uv run pytest -m unit`

### PR 6 — Distributed tracing across the gRPC boundary

The two new dependencies are **approved** (see CI/CD & guardrail approvals). This PR runs as normal.

- [ ] 6.1 Add the OpenTelemetry gRPC client interceptor to `GrpcRiskEngineClient` (`risk-orchestrator`) so the W3C `traceparent` header propagates on every call to the risk-engine.
      Acceptance: `./gradlew :risk-orchestrator:test`
- [ ] 6.2 Add `opentelemetry-instrumentation-grpc` server instrumentation to the risk-engine gRPC server (`server.py`) so it continues the inbound trace context.
      Acceptance: `cd risk-engine && uv run pytest -m unit`
- [ ] 6.3 Add a test asserting an inbound `traceparent` is read into the active span context on the Python side.
      Acceptance: `cd risk-engine && uv run pytest tests/test_tracing.py -m unit`

### PR 7 — Grafana dashboards as code

The single biggest "easy to track" win — version-controlled, business-event dashboards.

- [ ] 7.1 Add `deploy/observability/dashboards/` and a Grafana provisioning config (`deploy/observability/grafana/provisioning/dashboards/dashboards.yml` + datasources for Prometheus, Loki, Tempo).
      Acceptance: `test -d deploy/observability/dashboards && python3 -c "import yaml; yaml.safe_load(open('deploy/observability/grafana/provisioning/dashboards/dashboards.yml')); print('ok')"`
- [ ] 7.2 Add `deploy/observability/dashboards/trade-lifecycle.json` — trades booked/amended/cancelled over time (by book, by user), sourced from `audit_events` via Loki/Postgres, with a `correlationId` drill-through link to Loki + Tempo.
      Acceptance: `python3 -c "import json; d=json.load(open('deploy/observability/dashboards/trade-lifecycle.json')); assert d['panels']; print('ok')"`
- [ ] 7.3 Add `deploy/observability/dashboards/risk-run-health.json` — VaR run count, `risk_var_calculation_duration_seconds` p95 histogram, failed-run count (from `RISK_CALCULATION_FAILED`), DLQ depth, with a drill-through to the Loki logs for a selected run.
      Acceptance: `python3 -c "import json; d=json.load(open('deploy/observability/dashboards/risk-run-health.json')); assert d['panels']; print('ok')"`
- [ ] 7.4 Add `deploy/observability/dashboards/business-alerts.json` — limit breaches, anomalies and regime changes over time, with `bookId` and `tradeId` template variables for support search.
      Acceptance: `python3 -c "import json; d=json.load(open('deploy/observability/dashboards/business-alerts.json')); assert d['templating']; print('ok')"`
- [ ] 7.5 Wire dashboard + datasource provisioning into the local stack (`docker-compose` observability service volume mounts) and the Helm observability chart (`charts/observability` — `dashboardProviders` / `dashboardsConfigMaps` or equivalent).
      Acceptance: `python3 -c "import yaml; yaml.safe_load(open('deploy/helm/kinetix/charts/observability/values.yaml')); print('ok')" && grep -rq dashboards deploy/observability/grafana`

### PR 8 — UI: Activity & Audit view

Give users and support a screen — no LogQL required.

- [ ] 8.1 Add `ui/src/api/audit.ts` — a typed client for `/api/v1/audit/events` (incl. the new `tradeId` / `eventType` / `from` / `to` filters) and `/api/v1/audit/verify`.
      Acceptance: `cd ui && npm run test -- audit`
- [ ] 8.2 Add `ui/src/components/AuditLogPanel.tsx` — a paginated, filterable table (book / trade / event-type / time-window), event-type badges, a chain-integrity indicator, and an empty state. Add the Vitest unit test.
      Acceptance: `cd ui && npm run test -- AuditLogPanel`
- [ ] 8.3 Add an "Activity" top-level tab to `App.tsx` rendering `<AuditLogPanel>`.
      Acceptance: `cd ui && npm run lint && npm run test -- App`
- [ ] 8.4 Add a Playwright E2E spec `ui/e2e/audit-log.spec.ts` covering empty state, data rendering, filtering by trade ID, and the API error path (mock routes per `ui/e2e/fixtures.ts`).
      Acceptance: `cd ui && npx playwright test e2e/audit-log.spec.ts`

### PR 9 — Wire NotificationStrip to live events

`App.tsx:726` renders `<NotificationStrip items={[]} …>` — connect it to real data.

- [ ] 9.1 Feed live limit-breach / risk-result / alert notifications into the `NotificationStrip` `items` prop in `App.tsx` (reuse the existing alert/notification data source that `NotificationCenter` consumes). Update the Vitest test for the non-empty case.
      Acceptance: `cd ui && npm run lint && npm run test -- NotificationStrip App`
- [ ] 9.2 Add a Playwright E2E spec asserting a limit-breach notification appears in the strip without navigating to the Alerts tab.
      Acceptance: `cd ui && npx playwright test e2e/notification-strip.spec.ts`

### PR 10 — Retention & business-event alerts

- [ ] 10.1 Set explicit retention in the Helm observability chart: Loki 90 days, Tempo 30 days. Document the values alongside the existing DB retention policies.
      Acceptance: `python3 -c "import yaml; v=yaml.safe_load(open('deploy/helm/kinetix/charts/observability/values.yaml')); print('ok')" && grep -Eq 'retention|retention_period' deploy/helm/kinetix/charts/observability/values.yaml`
- [ ] 10.2 Add business-event alert rules to `alert-rules.yml` and the Helm chart: DLQ depth (`kafka_consumergroup_lag` on `*.dlq` topics) and a `RISK_CALCULATION_FAILED`-rate alert. Only include a rule if its metric is confirmed emitted; otherwise note the missing metric in the rule comment.
      Acceptance: `python3 -c "import yaml; g=yaml.safe_load(open('deploy/observability/alert-rules.yml')); assert any('Dlq' in r.get('alert','') or 'DLQ' in r.get('alert','') for grp in g['groups'] for r in grp['rules']); print('ok')"`

### PR 11 — Failure-mode test coverage

Prove the event trail is trustworthy under partial failure.

- [ ] 11.1 Add an audit-service acceptance test that inserts a chain with a deliberate sequence-number gap and asserts `/api/v1/audit/gaps` returns the exact missing range.
      Acceptance: `./gradlew :audit-service:acceptanceTest --tests "*AuditGapDetection*"`
- [ ] 11.2 Add a test asserting duplicate Kafka delivery of the same trade event to the audit consumer does not create two persisted records (idempotency contract).
      Acceptance: `./gradlew :audit-service:integrationTest`
- [ ] 11.3 Add a Playwright spec covering the alert WebSocket: a limit breach dispatched during a reconnect window is displayed once the connection recovers (this test documents the current behaviour; if it exposes dropped events, record the gap in the spec).
      Acceptance: `cd ui && npx playwright test e2e/alert-websocket-reconnect.spec.ts`

### PR 12 — Documentation

- [ ] 12.1 Update `docs/adr/ADR-0008` (or append an observability addendum) and the wiki to document: `correlationId` as the cross-system join key, dashboards-as-code, the three Grafana dashboards and their URLs, and a short "how support tracks an event" runbook.
      Acceptance: `grep -rqi 'correlationId' docs/ && grep -rqi 'trade-lifecycle\|risk-run-health' docs/`

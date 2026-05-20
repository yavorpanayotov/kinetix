# ADR-0008: Use Grafana Stack (Prometheus + Loki + Tempo) for Observability

## Status
Accepted

## Context
Observability is a core requirement. We need metrics, logs, and distributed traces across Kotlin and Python services, queryable from a single UI. Options: Grafana stack (Prometheus + Loki + Tempo), ELK stack (Elasticsearch + Logstash + Kibana), Datadog/cloud-native (not viable for local docker-compose).

## Decision
Use the Grafana observability stack:
- **Prometheus** for metrics storage and alerting rules
- **Grafana Loki** for log aggregation
- **Grafana Tempo** for distributed trace storage
- **Grafana** as the single-pane-of-glass UI
- **OpenTelemetry Collector** as the vendor-neutral telemetry pipeline

## Applies when
- Adding a new metric, log line, span, or alert rule.
- Wiring a new service into observability (instrumentation, scrape config, dashboard).
- Considering a SaaS observability vendor (Datadog, New Relic, Honeycomb).

## Rules
- **DO** export metrics via Micrometer with the Prometheus registry (Kotlin) or `opentelemetry-exporter-otlp` (Python). The OTel Collector fans out to Prometheus/Loki/Tempo.
- **DO** emit structured logs with `correlationId`, `userId`, and `bookId` in the MDC where applicable (ADR-0022). Loki indexes by labels, not full text — keep cardinality on labels low.
- **DO** name metrics with the standard prefix `kinetix_<service>_<measurement>_<unit>` (e.g. `kinetix_risk_orchestrator_valuation_duration_seconds`).
- **DO** add Prometheus alert rules under `deploy/observability/prometheus/alerts/` for any new SLO. Reference the alert in the Grafana dashboard for that service.
- **DO** instrument spans across the gRPC boundary so Tempo can stitch Kotlin↔Python traces.
- **DON'T** add a Datadog/New Relic/Honeycomb SDK. SaaS observability is out of scope.
- **DON'T** log high-cardinality fields (UUIDs, instrumentIds, full payloads) as Loki labels. Put them in the log body.
- **DON'T** rely on logs as a primary signal where a metric or trace would do — logs are search-only, not aggregatable beyond `count_over_time`.

## Consequences

### Positive
- Dramatically lower resource footprint than ELK — Loki and Tempo are lightweight (Elasticsearch alone needs 2GB+ heap)
- Practical for local docker-compose development on a developer laptop
- Single UI (Grafana) for all three pillars — jump from metrics to traces to logs seamlessly
- OpenTelemetry Collector decouples instrumentation from backends — can swap backends later without changing application code
- Prometheus alerting rules are widely understood and well-documented

### Negative
- Loki's log querying (LogQL) is less powerful than Elasticsearch's full-text search
- Tempo has no built-in indexing — trace lookup requires trace IDs (mitigated by linking from metrics/logs)
- Self-managed: no SaaS convenience (acceptable for local dev; production would use hosted Grafana Cloud)

### Telemetry Flow
```
App (Kotlin/Python) --OTLP--> OTel Collector ---> Prometheus (metrics)
                                              ---> Loki (logs)
                                              ---> Tempo (traces)
                                                        |
                                                    Grafana
```

### Alternatives Considered
- **ELK (Elasticsearch + Logstash + Kibana)**: Powerful full-text log search, mature ecosystem. But Elasticsearch's memory requirements (minimum 2GB heap, 4GB+ recommended) make it impractical for local docker-compose alongside 8 application services, Kafka, PostgreSQL, and Redis. Total memory exceeds what a developer laptop can comfortably provide.

## Addendum (2026-05) — Event Observability (Audit v2)

The original decision stood up the three pillars (metrics, logs, traces). The
*Event Observability* work closed the "last mile": making a single business
event traceable end-to-end across all three pillars plus the audit trail, and
codifying the dashboards that support and engineering use to do it. This
addendum records what shipped; the rules above still apply, with the additions
noted here.

### `correlationId` as the cross-system join key

`correlationId` (ADR-0022) is now the single identifier that stitches every
observability surface together:

- **Loki logs** — every service puts `correlationId` into the SLF4J MDC (key
  `"correlationId"`); the risk-engine puts it on every structured log record.
  It appears in the log body, queryable in LogQL.
- **Tempo traces** — `correlationId` is set as a span attribute, so a trace can
  be located from a log line and vice versa.
- **`audit_events` table** — Flyway migration `V13__add_correlation_id.sql`
  adds a nullable `correlation_id VARCHAR(255)` column to `audit_events`. Each
  audit-service consumer persists the `correlationId` carried on the inbound
  Kafka event.

`correlationId` is **deliberately excluded from the `AuditHasher` hash chain.**
It is operational cross-reference metadata, not an audited regulatory fact. The
hash chain protects who/what/when/trade-detail; folding in a debugging pointer
would add no integrity value and would break every pre-existing chain. The
column is nullable so events written before V13 simply carry `NULL`.

### Distributed tracing and structured logging additions

- **W3C trace context across the gRPC boundary** — the risk-orchestrator →
  risk-engine call propagates `traceparent` via OpenTelemetry gRPC client and
  server instrumentation, so Tempo stitches Kotlin↔Python spans into one trace
  (satisfies the existing "instrument spans across the gRPC boundary" rule).
- **Structured JSON logging in the risk-engine** — a stdlib
  `JsonLogFormatter` (`risk-engine/src/kinetix_risk/log_formatter.py`) emits
  JSON log records. `book_id`, `correlation_id`, and `calculation_type` are
  first-class queryable fields in Loki.
- **`RISK_CALCULATION_FAILED` audit event** — a failed risk run now leaves an
  audit trace. `VaRCalculationService`'s failure path publishes
  `AuditEventType.RISK_CALCULATION_FAILED`, so failures are visible in the
  audit trail, not just in logs.
- **Audit query API** — `GET /api/v1/audit/events` supports `tradeId`,
  `eventType`, `from`, and `to` filters (gateway-proxied) alongside `bookId`
  and cursor pagination; `GET /api/v1/audit/verify` checks chain integrity and
  `GET /api/v1/audit/gaps` detects sequence-number gaps.

### Dashboards as code

Grafana dashboards are version-controlled JSON under
`deploy/observability/dashboards/` — **no hand-built dashboards in the Grafana
UI.** They are provisioned by the file-based dashboard provider
(`deploy/observability/grafana/provisioning/`) and wired into both the local
docker-compose stack and the Helm `observability` chart, so local matches prod.
Three business-domain dashboards ship:

| Dashboard | File | uid | Purpose |
|---|---|---|---|
| Trade Lifecycle | `trade-lifecycle.json` | `kinetix-trade-lifecycle` | Follow a trade from booking through risk to audit |
| Risk Run Health | `risk-run-health.json` | `kinetix-risk-run-health` | VaR run throughput, latency, and failures |
| Business Alerts & Events | `business-alerts.json` | `kinetix-business-alerts` | Limit breaches, alerts, and other business events |

Each dashboard carries Loki/Tempo drill-through links keyed on `correlationId`,
`tradeId`, and `bookId`. Retention: Loki 90 days, Tempo 30 days (Helm
`observability` chart).

### Added rules

- **DO** carry `correlationId` on every observability surface for a new event
  flow — MDC for Loki, span attribute for Tempo, and the `audit_events` row
  where the flow produces an audit event.
- **DON'T** fold `correlationId` (or any other purely operational metadata)
  into the audit hash chain — it is a cross-reference pointer, not an audited
  fact.
- **DO** add new dashboards as version-controlled JSON under
  `deploy/observability/dashboards/` and keep the Helm copy in sync — never
  build a dashboard directly in the Grafana UI.

See the wiki [Observability](../wiki/Observability.md) page for the
"how support tracks an event" runbook.

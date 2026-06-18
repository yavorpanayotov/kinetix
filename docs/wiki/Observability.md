# Observability

Kinetix runs the Grafana observability stack — Prometheus for metrics, Loki for
logs, Tempo for traces, with an OpenTelemetry Collector as the vendor-neutral
pipeline ([ADR-0008](https://github.com/yavorpanayotov/kinetix/blob/main/docs/adr/0008-grafana-stack-for-observability.md)).
This page covers the parts that make a single business event traceable
end-to-end: the `correlationId` join key, the dashboards-as-code setup, and the
runbook support uses to track what happened to a trade or a risk run.

## `correlationId` — the cross-system join key

Every inbound request mints a UUID `correlationId` at the gateway
([ADR-0022](https://github.com/yavorpanayotov/kinetix/blob/main/docs/adr/0022-correlation-id-propagation.md)).
It is propagated through every HTTP call, every Kafka event, and every gRPC
call in the causal chain. It is the one identifier that ties the three
observability pillars and the audit trail together:

| Surface | How `correlationId` appears | How to query it |
|---|---|---|
| Loki logs | Put into the SLF4J MDC (key `correlationId`) by every Kotlin service; placed on every structured log record by the risk-engine | LogQL filter on the log body, e.g. `{service="risk-orchestrator"} |= "<correlationId>"` |
| Tempo traces | Set as a span attribute | Search traces by attribute, or follow a drill-through link from a log line |
| `audit_events` table | Persisted as the nullable `correlation_id` column (Flyway `V13__add_correlation_id.sql`) | `GET /api/v1/audit/events` or the UI Activity tab |

**`correlationId` is deliberately excluded from the audit hash chain.** It is
operational cross-reference metadata, not an audited regulatory fact. The
`AuditHasher` chain protects who/what/when/trade-detail; folding in a debugging
pointer would add no integrity value and would break every pre-existing chain.
The `correlation_id` column is nullable — audit events written before V13
simply carry `NULL`.

### What else shipped with Event Observability (Audit v2)

- **Distributed tracing across the gRPC boundary** — the risk-orchestrator →
  risk-engine call propagates the W3C `traceparent`, so Tempo stitches
  Kotlin↔Python spans into one trace.
- **Structured JSON logging in the risk-engine** — a stdlib `JsonLogFormatter`
  emits JSON log records; `book_id`, `correlation_id`, and `calculation_type`
  are first-class queryable Loki fields.
- **`RISK_CALCULATION_FAILED` audit event** — a failed risk run now leaves an
  audit trace, not just a log line.
- **Audit query API** — `GET /api/v1/audit/events` supports `tradeId`,
  `eventType`, `from`, `to`, and `bookId` filters with cursor pagination;
  `GET /api/v1/audit/verify` checks chain integrity; `/api/v1/audit/gaps`
  detects sequence-number gaps.
- **UI Activity tab** — a top-level "Activity" tab renders the audit-trail view
  (paginated and filterable), so support no longer needs raw LogQL for routine
  lookups.

## Dashboards as code

Grafana dashboards are version-controlled JSON under
[`deploy/observability/dashboards/`](https://github.com/yavorpanayotov/kinetix/tree/main/deploy/observability/dashboards) —
**no hand-built dashboards in the Grafana UI** (changes there are not
persisted). They are provisioned by Grafana's file-based dashboard provider
(`deploy/observability/grafana/provisioning/`) and wired into both the local
docker-compose stack and the Helm `observability` chart, so what you see
locally matches production.

Three business-domain dashboards ship. The Grafana base URL is
`https://grafana.kinetixrisk.ai`; each dashboard is reachable by its stable
`uid` at `/d/<uid>`.

| Dashboard | File | URL | Shows |
|---|---|---|---|
| Trade Lifecycle | `trade-lifecycle.json` | <https://grafana.kinetixrisk.ai/d/kinetix-trade-lifecycle> | A trade from booking through limit checks, risk calculation, and into the audit trail |
| Risk Run Health | `risk-run-health.json` | <https://grafana.kinetixrisk.ai/d/kinetix-risk-run-health> | VaR run throughput, latency, and failures (`RISK_CALCULATION_FAILED`) |
| Business Alerts & Events | `business-alerts.json` | <https://grafana.kinetixrisk.ai/d/kinetix-business-alerts> | Limit breaches, alerts, and other business events |

Each dashboard carries Loki/Tempo drill-through links keyed on `correlationId`,
`tradeId`, and `bookId` — click a row and Grafana jumps straight to the
matching logs or trace. Retention: Loki keeps logs 90 days, Tempo keeps traces
30 days (Helm `observability` chart).

When you add a new dashboard, add it as JSON under
`deploy/observability/dashboards/` and keep the Helm copy
(`deploy/helm/kinetix/charts/observability/dashboards/`) in sync.

## How support tracks an event

A support engineer is given a `tradeId` (or a `correlationId`) and needs to
find out what happened. The fastest path, in order:

1. **Start in the UI Activity tab.** Open the Kinetix UI
   (`https://kinetixrisk.ai`) and go to the **Activity** tab. It renders the
   audit trail — paginated and filterable. Filter by the `tradeId`. Each row is
   a chained audit event (trade booked, risk run started/completed/failed,
   limit breach, etc.) and carries the `correlationId`. This is enough for most
   "what happened to this trade?" questions, with no LogQL required.

2. **Note the `correlationId`.** Copy the `correlationId` from the relevant
   audit row. This single value will be your join key for the rest of the
   investigation.

3. **Query the audit API directly if you need filters.** The Activity tab is
   backed by `GET /api/v1/audit/events` on the gateway
   (`https://api.kinetixrisk.ai`). It supports `tradeId`, `eventType`, `from`,
   `to`, and `bookId` filters, e.g.:

   ```
   GET https://api.kinetixrisk.ai/api/v1/audit/events?tradeId=<tradeId>&eventType=RISK_CALCULATION_FAILED
   ```

   Use `GET /api/v1/audit/verify` to confirm the audit chain is intact, and
   `/api/v1/audit/gaps` (audit-service) to check for missing sequence numbers.

4. **Open the right Grafana dashboard.** Go to `https://grafana.kinetixrisk.ai`:
   - Trade booking / lifecycle questions → **Trade Lifecycle**
     (`/d/kinetix-trade-lifecycle`).
   - A risk run that was slow or failed → **Risk Run Health**
     (`/d/kinetix-risk-run-health`).
   - A limit breach or alert → **Business Alerts & Events**
     (`/d/kinetix-business-alerts`).

   Each dashboard's panels are filterable by `correlationId`, `tradeId`, and
   `bookId`.

5. **Drill through to logs and traces.** From any dashboard row, use the
   built-in drill-through links keyed on `correlationId` to jump to:
   - **Loki** — the full structured logs for that causal chain across every
     service. The risk-engine logs are JSON with `book_id`, `correlation_id`,
     and `calculation_type` fields.
   - **Tempo** — the distributed trace, including the gRPC hop from
     risk-orchestrator into the Python risk-engine, so you can see exactly
     where time went or where a span errored.

In short: **Activity tab → `correlationId` → audit API filters → Grafana
dashboard → drill through to Loki/Tempo.** The `correlationId` is the thread
that runs through all of it.

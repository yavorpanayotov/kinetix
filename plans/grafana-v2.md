# Grafana v2: Dashboard Coverage, Correctness & Alerting Overhaul

## Context

A five-agent review (trader, architect, qa, ux-designer, data-analyst) audited the
14 Grafana dashboards under `infra/grafana/provisioning/dashboards/`
(`infrastructure/`, `overview/`, `trading/`, `risk/`). The dashboards have
reasonable breadth, but the review surfaced one platform-level safety hole, a set
of panels that render "No data" because the metrics they query are never emitted,
several statistically misleading panels, broad consistency drift, and ~8 missing
dashboards for services that are scraped but operationally dark.

Findings verified directly against the codebase before writing this plan:

- **Alerting is inert.** `deploy/observability/prometheus.yml:11` references
  `alert-rules.yml`, but that file does not exist. Alertmanager is wired and
  routed, but zero rules can ever fire — no service-down, VaR-breach, consumer-lag,
  or stale-price alert exists.
- **FIX Gateway dashboard has 6 broken panels.** `fix-gateway` registers only
  `fix_messages_in_total`, `fix_session_state`, `fix_session_reconciliation_total`,
  `fix_message_log`. The dashboard additionally queries `fix_messages_out_total`,
  `cancel_ack_latency_seconds`, `cancel_failed_total`, `unacknowledged_outbound_total`,
  `fix_message_log_partitions_archived_total`, `ghost_fill_detected_total`,
  `mtls_handshake_failed_total` — none are emitted. No ghost-fill-detection or
  mTLS code exists in the service.
- **`limit-utilisation` "VaR vs Limit" cannot show utilisation.** No `risk_var_limit`
  metric is emitted by any service. The panel plots raw `risk_var_value` against
  hardcoded $500k/$1M thresholds — a $900k VaR looks identical on a $800k-limit
  book and a $10M-limit book. Flagged independently by all five reviewers.
- **`greeks` delta/gamma use `unit: short`**; vega/theta/rho use `currencyUSD`
  (vega is dollars-per-vol-point, not a currency amount).
- **`ai-insights-service` is not scraped** — no job in `prometheus.yml`.
- **No test of any kind validates dashboard JSON** — a renamed metric or malformed
  file breaks panels silently with no CI signal.
- Plus: no-data indistinguishable from zero, `lastNotNull` masks down services as
  green, inconsistent refresh/time-range, implementation-detail panel titles, VaR
  shown without a holding period, and no dashboards for `gateway`, `audit-service`,
  `regulatory-service`, `notification-service`, `volatility-service`,
  `correlation-service`, position concentration, stress-test results, or P&L
  decomposition by Greek.

This plan ships, in dependency order: an alerting safety net + a dashboard
validator, JSON-only fixes for broken/misleading panels, backend instrumentation
for the metrics behind currently-dead panels, a consistency pass, and new
dashboards for the dark services.

## Status

This plan is loop-ready for `/work-plan`. Each `- [ ]` checkbox is one
independently-committable change, ordered top-to-bottom by dependency, with an
`Acceptance:` command on the line directly after it. Advance end-to-end with
`/loop /work-plan plans/grafana-v2.md`. The codebase must build and tests must
pass after every commit.

## Decisions applied

- **Validation gate.** A new zero-dependency Node script,
  `plans/scripts/validate-grafana.mjs`, is the test for every dashboard-JSON
  change. Grafana provisioned JSON is configuration, not the Kinetix React UI —
  Vitest/Playwright do not apply. The script is the acceptance command for all
  JSON-only checkboxes.
- **Instrumentation is in-boundary.** Adding Prometheus metrics to existing
  services is autonomous per CLAUDE.md ("adding a class/file within an existing
  service"). No new services, Kafka topics, DB tables, or cross-service API
  contracts are introduced. Micrometer (Kotlin) and `prometheus_client`/the
  existing `metrics.py` pattern (Python) are already dependencies — no new
  libraries.
- **TDD for instrumentation.** Each instrumentation checkbox follows
  Red-Green-Refactor; the Kotest/pytest test and the implementation land in the
  **same commit** so every commit builds green.
- **Kafka Messages-In query is kept.** The review flagged
  `rate(kafka_topic_partition_current_offset[5m])` as "wrong metric". Verified:
  the only Kafka scrape target is `kafka-exporter` (no broker JMX target exists),
  and offset-rate is the conventional throughput proxy for that exporter. It is
  **kept**, with a clarifying panel description. The real fix is the **DLQ panel**:
  a rate-based DLQ panel reads zero for a *stalled* DLQ (looks healthy) — it is
  changed to show absolute DLQ depth.
- **FIX Gateway scope.** Instrument the 5 metrics whose code paths already exist
  (outbound send, cancel handling via `CancelMessageBuilder`/`PendingNewCorrelator`,
  message-log archival). `ghost_fill_detected_total` and `mtls_handshake_failed_total`
  have **no underlying feature** — building ghost-fill detection or mTLS is new
  product work requiring separate approval. Those two panels are replaced with
  honest "not yet instrumented" placeholder panels rather than left silently
  broken.
- **Greeks units.** Delta/gamma stay raw dimensionless sensitivities with
  `unit: none` and explicit display names. Dollar-delta/dollar-gamma are *new
  metrics* delivered with the P&L-decomposition work (4.8), not by overloading the
  greeks dashboard.
- **`risk-engine` vs `risk-overview` are not merged.** `risk-overview` stays the
  business/limit view; `risk-engine` stays the engine-throughput view. The overlap
  is disambiguated with descriptions and dashboard links (3.x).
- **Dashboard standards.** `30s` refresh, `now-1h` default range, and a
  `$datasource` template variable on every dashboard.
- **Limit ownership.** `position-service` owns limits ("Trade booking, position
  management, P&L, limits" — CLAUDE.md) and emits `risk_var_limit`. VaR values
  (`risk_var_value`) are produced via `risk-orchestrator`/`risk-engine`.

## CI/CD & guardrail approvals (pre-approved here)

Per CLAUDE.md guardrails, the following are explicitly approved so subagents do
not stop mid-loop:

- **Editing `deploy/observability/prometheus.yml` and `alertmanager.yml`, and
  creating `deploy/observability/alert-rules.yml`** — observability configuration,
  **not** CI/CD pipeline files. Approved.
- **New and modified Grafana dashboard JSON** under
  `infra/grafana/provisioning/dashboards/`. Approved.
- **New Prometheus metrics in existing services** (Micrometer / `prometheus_client`).
  No new libraries. Approved.
- **New script** `plans/scripts/validate-grafana.mjs`. Approved.
- **No `.github/workflows/*` changes are anticipated.** If a subagent finds it
  must touch a CI/CD pipeline file, STOP and flag.
- No new Kafka topics, DB tables, services, or cross-service API contracts.

## Out of scope

- Building FIX **ghost-fill detection** or **mTLS** as product features — separate
  approval required. This plan only replaces their broken panels with honest
  placeholders.
- Wiring `validate-grafana.mjs` into CI (guardrail: no CI/CD pipeline changes).
- Grafana unified alerting — alerts route through Prometheus `alert-rules.yml` +
  Alertmanager per ADR-0008.
- Replacing `kafka-exporter` with a broker JMX exporter for native
  `messagesinpersec` metrics.
- SLO / error-budget dashboards and long-range capacity-planning dashboards.
- Screenshots or wiki updates — captured by the user post-loop.

## Execution plan

### PR 0 — Alerting safety net & dashboard validation

- [x] 0.1 Create `plans/scripts/validate-grafana.mjs` — a zero-dependency Node
      script that: (a) parses every `infra/grafana/provisioning/dashboards/**/*.json`
      and fails on any JSON syntax error; (b) checks every panel `datasource`
      (uid or name) resolves to a datasource provisioned in
      `infra/grafana/provisioning/datasources/datasources.yaml`; (c) warns when a
      `timeseries`/`stat`/`gauge`/`bargauge` panel declares no `unit`; (d) prints
      every PromQL/LogQL metric name referenced, grouped by dashboard; (e) if
      `deploy/observability/alert-rules.yml` exists, structurally validates it
      (every `- alert:` has `expr`, `for`, `labels`, `annotations`). Exits
      non-zero on any hard error.
      Acceptance: `node plans/scripts/validate-grafana.mjs`
- [x] 0.2 Create `deploy/observability/alert-rules.yml` (referenced by
      `prometheus.yml:11` but missing — the alert pipeline is currently inert).
      Add at least 12 rules across groups for: service down (`up == 0` per job),
      high HTTP 5xx error rate, Kafka consumer lag, DLQ depth growth, stale market
      data (`price_staleness_seconds`), VaR-limit breach, risk-run staleness,
      database connection saturation, and JVM/process health. Each rule has
      `expr`, `for`, a severity `label`, and `summary`/`description` annotations.
      Acceptance: `node plans/scripts/validate-grafana.mjs`

### PR 1 — Fix broken & misleading panels (dashboard JSON only)

- [x] 1.1 `infrastructure/kafka-health.json` — keep the Messages-In offset-rate
      query (correct throughput proxy for the kafka-exporter-only scrape) but add
      a panel `description` noting it is a partition-offset-derived estimate.
      Change the **DLQ panel** from `rate(...current_offset...)` to absolute DLQ
      depth so a stalled DLQ is visible instead of reading zero; add red
      thresholds.
      Acceptance: `node plans/scripts/validate-grafana.mjs`
- [ ] 1.2 `risk/greeks.json` — change the delta and gamma panels from
      `unit: short` to `unit: none` with explicit display names ("Net Delta
      (dimensionless)", "Net Gamma"); correct the vega panel `description` to state
      it is dollars-per-vol-point, not a currency amount.
      Acceptance: `node plans/scripts/validate-grafana.mjs`
- [ ] 1.3 `trading/pnl.json` — fix the "Unexplained P&L Fraction" panel: the
      `!= 0` filter in the denominator blanks the whole panel when total P&L is
      exactly zero (flat book, new book, end of day). Replace with a zero-safe
      denominator (`clamp_min(abs(pnl_attribution_total_pnl{...}), 1)`) so a flat
      book shows 0%, not "No data".
      Acceptance: `node plans/scripts/validate-grafana.mjs`
- [ ] 1.4 Across all 14 dashboards, set `fieldConfig.defaults.noValue` on every
      `stat`/`gauge`/`bargauge` panel, and stop service-health stat panels from
      using `lastNotNull` alone (add a companion `up`-based health panel or a
      window-aware reducer) so a service that has gone down is not rendered green
      from its last scraped value.
      Acceptance: `node plans/scripts/validate-grafana.mjs`
- [ ] 1.5 `risk/risk-engine.json` — fix the FRTB and stress-test panels: give the
      duration series its own y-axis and `unit: s`, separate from the rate series;
      add the missing
      `histogram_quantile(0.95, ...stress_test_duration_seconds_bucket...)` query
      so the stress panel shows duration as its title promises.
      Acceptance: `node plans/scripts/validate-grafana.mjs`

### PR 2 — Instrument metrics behind broken panels

- [ ] 2.1 Emit a `risk_var_limit{book_id,calculation_type,confidence_level}` gauge
      from `position-service` (the limit owner) reflecting each book's configured
      VaR limit. Then rewrite the `risk/limit-utilisation.json` "VaR vs Limit"
      panel to plot utilisation
      `risk_var_value / on(book_id) group_left() risk_var_limit` as a percentage
      with 80%/100% thresholds, replacing the hardcoded $500k/$1M steps. TDD.
      Acceptance: `./gradlew :position-service:test && node plans/scripts/validate-grafana.mjs`
- [ ] 2.2 Instrument `fix-gateway` with the 5 metrics its dashboard already
      queries but no code emits: `fix_messages_out_total`, `cancel_ack_latency_seconds`
      (Timer), `cancel_failed_total`, `unacknowledged_outbound_total` (Gauge from
      `PendingNewCorrelator`), `fix_message_log_partitions_archived_total`. TDD —
      Kotest assertions that each is registered, wired at the real call sites.
      Acceptance: `./gradlew :fix-gateway:test`
- [ ] 2.3 `trading/fix-gateway-overview.json` — replace the
      `ghost_fill_detected_total` and `mtls_handshake_failed_total` panels (no
      ghost-fill-detection or mTLS code exists) with clearly-labelled `text`
      placeholder panels stating the capability is not yet instrumented, so the
      dashboard has no silently-broken "No data" graphs.
      Acceptance: `node plans/scripts/validate-grafana.mjs`
- [ ] 2.4 Add a Prometheus `/metrics` endpoint to `ai-insights-service` and a
      corresponding `ai-insights-service` scrape job in
      `deploy/observability/prometheus.yml` (the service is currently not scraped
      at all).
      Acceptance: `( cd ai-insights-service && uv run pytest ) && grep -q 'ai-insights-service' deploy/observability/prometheus.yml`

### PR 3 — Consistency, correctness & navigation pass (dashboard JSON only)

- [ ] 3.1 Standardise dashboard chrome across all 14 JSON files: `refresh: "30s"`,
      default `time` range `now-1h`, consistent `timezone`, and a folder-aligned
      `tags` entry (`infrastructure`/`overview`/`trading`/`risk`).
      Acceptance: `node plans/scripts/validate-grafana.mjs`
- [ ] 3.2 Add a `$datasource` template variable (type `datasource`) to every
      dashboard and repoint every panel `datasource` to `${datasource}`, so
      dashboards are portable across Prometheus/Loki instances.
      Acceptance: `node plans/scripts/validate-grafana.mjs`
- [ ] 3.3 Rename implementation-detail panel titles to user-facing language across
      all dashboards (e.g. "PendingNewCorrelator: TTL Evictions" → "Order
      Correlator — TTL Evictions"; "fix_message_log Partitions Archived" → "FIX Log
      Partitions Archived").
      Acceptance: `node plans/scripts/validate-grafana.mjs`
- [ ] 3.4 On `risk-overview.json`, `risk-engine.json`, `limit-utilisation.json`
      and `greeks.json`, make VaR statistically self-describing: every VaR panel
      title/legend states confidence level and holding period (e.g. "VaR (99%,
      1-day)"); add panel `description`s clarifying cumulative-vs-interval for P&L
      gauges; add dashboard links between `risk-overview` and `risk-engine` to
      disambiguate the two.
      Acceptance: `node plans/scripts/validate-grafana.mjs`
- [ ] 3.5 `overview/system-health.json` — add `$job`/`$consumergroup` template
      variables and rework the Kafka Consumer Lag panel to group by consumer group
      (not group+topic+partition) so it stays legible at scale; add an amber
      threshold step to the Error Rate stat panel between green and red.
      Acceptance: `node plans/scripts/validate-grafana.mjs`

### PR 4 — New dashboards & coverage for dark services

Each item instruments the owning service (TDD) where new metrics are needed and
adds the dashboard in the same commit — one self-contained feature per checkbox.

- [ ] 4.1 Add a dedicated `overview/gateway.json` dashboard built from the ktor
      metrics the gateway already exposes: request rate, 4xx/5xx error rate,
      latency percentiles by route, upstream service-call latency, in-flight
      requests. No new instrumentation required.
      Acceptance: `node plans/scripts/validate-grafana.mjs && test -f infra/grafana/provisioning/dashboards/overview/gateway.json`
- [ ] 4.2 Instrument `position-service` with exposure metrics (`position_notional`,
      `position_count`, top-N concentration gauges by instrument/book) and add a
      `trading/position-concentration.json` dashboard showing net exposure,
      long/short split, and top-10 concentrations. TDD.
      Acceptance: `./gradlew :position-service:test && node plans/scripts/validate-grafana.mjs && test -f infra/grafana/provisioning/dashboards/trading/position-concentration.json`
- [ ] 4.3 Instrument `risk-engine` with stress-scenario result metrics
      (`stress_test_loss` gauge labelled by `scenario_name`/`book_id`) and add a
      `risk/stress-results.json` dashboard showing per-scenario P&L, worst scenario
      per book, and breach highlighting. TDD.
      Acceptance: `( cd risk-engine && uv run pytest -m unit ) && node plans/scripts/validate-grafana.mjs && test -f infra/grafana/provisioning/dashboards/risk/stress-results.json`
- [ ] 4.4 Instrument `regulatory-service` with model-governance metrics (VaR
      backtesting exceptions, backtest pass/fail counts, submission outcomes) and
      add a `risk/regulatory.json` dashboard. TDD.
      Acceptance: `./gradlew :regulatory-service:test && node plans/scripts/validate-grafana.mjs && test -f infra/grafana/provisioning/dashboards/risk/regulatory.json`
- [ ] 4.5 Instrument `volatility-service` and `correlation-service` with
      surface-health metrics (last-update age, surface point count, calibration
      failures) and add a `risk/surface-health.json` dashboard. TDD.
      Acceptance: `./gradlew :volatility-service:test :correlation-service:test && node plans/scripts/validate-grafana.mjs && test -f infra/grafana/provisioning/dashboards/risk/surface-health.json`
- [ ] 4.6 Instrument `notification-service` with WebSocket metrics (active
      connections, messages pushed, delivery failures, dropped connections) and add
      an `overview/notification-service.json` dashboard. TDD.
      Acceptance: `./gradlew :notification-service:test && node plans/scripts/validate-grafana.mjs && test -f infra/grafana/provisioning/dashboards/overview/notification-service.json`
- [ ] 4.7 Instrument `audit-service` with audit-trail metrics (append rate,
      hash-chain verification pass/fail, write latency, chain length) and add an
      `overview/audit-service.json` dashboard. TDD.
      Acceptance: `./gradlew :audit-service:test && node plans/scripts/validate-grafana.mjs && test -f infra/grafana/provisioning/dashboards/overview/audit-service.json`
- [ ] 4.8 Instrument P&L attribution by Greek component (delta / gamma / vega /
      theta / rho P&L, plus dollar-delta / dollar-gamma) in `risk-engine`, and
      extend `trading/pnl.json` with a P&L-decomposition panel set so a desk can
      see whether a move was delta-, vega-, or theta-driven. TDD.
      Acceptance: `( cd risk-engine && uv run pytest -m unit ) && node plans/scripts/validate-grafana.mjs && grep -qE 'delta_pnl|pnl.*decomposition|attribution' infra/grafana/provisioning/dashboards/trading/pnl.json`

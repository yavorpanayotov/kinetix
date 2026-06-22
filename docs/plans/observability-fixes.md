# Observability & System-Section Fixes

Fix the live-system issues surfaced on 2026-06-22: the broken **Prometheus** link in
the UI *System* section, and **70 dataless panels across 11 Grafana dashboards**.

Run hands-off with:

```
/loop /work-plan docs/plans/observability-fixes.md
```

The shared acceptance harness is `scripts/check_dashboard_data.py` — it runs each
dashboard's PromQL/LogQL against live Prometheus/Loki and exits non-zero on any
empty panel. Single dashboard: `python3 scripts/check_dashboard_data.py <path>`.

## Decisions applied

- **Scope:** full observability sweep (every dashboard), not just the two originally reported.
- **Root-cause depth:** where a panel is empty because a metric is *never emitted*, add the
  missing instrumentation in the service (don't just hide the panel). Where a metric simply
  isn't exercised by demo data, make demo exercise it.
- **Acceptance split:** dashboard-JSON / query fixes are verified live via the checker.
  Instrumentation fixes are verified by a JVM/pytest test asserting the meter is registered
  and incremented (fast, no redeploy per box); the **final checkbox redeploys and runs the
  full live sweep** to prove end-to-end.
- **Out-of-scope deferral:** if a SOURCE metric turns out to be genuinely future-feature work
  (not wired anywhere, no demo path), file a `bd` issue and note it under *Out of scope* — do
  NOT add a checkbox for it.

## Guardrails / pre-approval

- `deploy/docker/Caddyfile` may be edited (deploy config, within the observability fix scope). It is **not** a CI/CD pipeline file.
- **No new dependencies.** Micrometer + the Prometheus registry are already present; HikariCP/JVM
  binders ship with Micrometer. If any fix appears to need a new library, STOP and ask — do not add it.
- Do not modify CI/CD pipeline files. Do not delete/skip tests.

## Phase 1 — Prometheus link (System section)

- [ ] Add a `prometheus.kinetixrisk.ai` reverse-proxy host to `deploy/docker/Caddyfile` (→ `prometheus:9090`) and point the UI *System* link at `https://prometheus.kinetixrisk.ai` instead of `http://localhost:9090` (`ui/src/components/SystemDashboard.tsx`); update `SystemDashboard.test.tsx` to assert the new href.
  Acceptance: `cd ui && npx vitest run src/components/SystemDashboard.test.tsx && grep -q 'prometheus.kinetixrisk.ai' ../deploy/docker/Caddyfile`

## Phase 2 — Dashboard query fixes (QUERY class — metric exists, selector wrong)

- [ ] Fix gateway dashboard `4xx / 5xx Error Rate` panel — correct the `ktor_http_server_requests_seconds_count` label selector (status class) to match emitted labels.
  Acceptance: `python3 scripts/check_dashboard_data.py infra/grafana/provisioning/dashboards/overview/gateway.json`
- [ ] Fix trade-flow dashboard (Trade Booking Rate / Latency P95 / Error Rate) — correct the `ktor_http_server_requests_seconds_*` selectors (route/status labels) to the position-service's real labels.
  Acceptance: `python3 scripts/check_dashboard_data.py infra/grafana/provisioning/dashboards/trading/trade-flow.json`
- [ ] Fix risk-orchestrator dashboard `Error Rate (5xx)` panel — correct the `ktor_http_server_requests_seconds_count` selector. (The `var_calculation_*` panels on this dashboard are handled in Phase 3.)
  Acceptance: `python3 scripts/check_dashboard_data.py infra/grafana/provisioning/dashboards/risk/risk-orchestrator.json || true` then confirm only `var_calculation_*` panels remain empty.
- [ ] Fix kafka-health dashboard `DLQ Depth` panel — repair the `kafka_topic_partition_current_offset` query (topic template var / aggregation producing a 400).
  Acceptance: `python3 scripts/check_dashboard_data.py infra/grafana/provisioning/dashboards/infrastructure/kafka-health.json`
- [ ] Fix audit-service dashboard `Chain Verification Failures (5m)` panel — align with the real `audit_chain_verifications_total` label set (outcome/result label).
  Acceptance: `python3 scripts/check_dashboard_data.py infra/grafana/provisioning/dashboards/overview/audit-service.json`
- [ ] Fix limit-utilisation dashboard — repair the `ktor_http_server_requests_seconds_count` rejection/limit-check selectors; investigate `risk_var_value` / `risk_var_limit` and fix the query if the metric exists, else handle the metric in Phase 3.
  Acceptance: `python3 scripts/check_dashboard_data.py infra/grafana/provisioning/dashboards/risk/limit-utilisation.json || true` then confirm any remaining empties are SOURCE-class metrics deferred to Phase 3.

## Phase 3 — Instrumentation fixes (SOURCE class — metric never emitted)

Each box: add the meter in the owning service (Micrometer), wire it into the code path,
seed/exercise it in demo where needed, and add a test asserting the meter registers + increments.

- [ ] Enable HikariCP connection-pool metrics so database-health panels populate (`hikaricp_connections_*`). Register the Micrometer HikariCP binder on the shared datasource config in `common/`.
  Acceptance: `./gradlew :common:test --tests "*HikariMetric*"` (new test asserting `hikaricp_connections_active` is registered)
- [ ] Add risk-orchestrator VaR-calculation metrics (`var_calculation_count_total`, `var_calculation_duration_seconds`) around the orchestration path.
  Acceptance: `./gradlew :risk-orchestrator:test --tests "*VarCalculationMetric*"`
- [ ] Add notification-service in-app delivery metrics (`notification_inapp_messages_delivered_total`, `notification_inapp_delivery_failures_total`) with a `severity` tag.
  Acceptance: `./gradlew :notification-service:test --tests "*InAppDeliveryMetric*"`
- [ ] Add surface-health calibration-failure metrics (`volatility_surface_calibration_failures_total` in volatility-service, `correlation_matrix_calibration_failures_total` in correlation-service).
  Acceptance: `./gradlew :volatility-service:test :correlation-service:test --tests "*CalibrationFailureMetric*"`
- [ ] Add regulatory-service metrics (`regulatory_submission_outcomes_total`, `regulatory_backtest_runs_total`, `regulatory_backtest_exceptions_total`, `regulatory_backtest_current_exceptions`).
  Acceptance: `./gradlew :regulatory-service:test --tests "*RegulatoryMetric*"`
- [ ] Add fix-gateway FIX metrics (`fix_session_state`, `fix_messages_in_total`, `fix_messages_out_total`, `fix_session_reconciliation_total`, `cancel_*`, `pending_new_correlator_*`).
  Acceptance: `./gradlew :fix-gateway:test --tests "*FixMetric*"` (or the owning module if fix-gateway is part of position-service)
- [ ] Add P&L attribution metrics (`pnl_attribution_total_pnl`, `pnl_attribution_unexplained_pnl`, `pnl_attribution_greek_pnl`, `pnl_attribution_dollar_delta`, `pnl_attribution_dollar_gamma`) in the service that computes attribution; ensure demo exercises attribution.
  Acceptance: `./gradlew :position-service:test --tests "*PnlAttributionMetric*"` (adjust to owning module)
- [ ] Add risk-engine ML metrics (`ml_prediction_total`, `ml_anomaly_detected_total`) in the Python risk engine, exported on its Prometheus endpoint.
  Acceptance: `cd risk-engine && uv run pytest -k metric`

## Phase 4 — Logging pipeline (Loki has no streams)

- [ ] Diagnose and fix the log-shipping pipeline so service logs reach Loki (otel-collector/promtail → Loki). Verify Loki returns streams, then confirm log-search + service-logs dashboards populate.
  Acceptance: `curl -s 'http://localhost:3100/loki/api/v1/query_range?query=%7Bjob%3D~%22.%2B%22%7D&limit=1' | grep -q '"result":\[{' && python3 scripts/check_dashboard_data.py infra/grafana/provisioning/dashboards/overview/service-logs.json`

## Phase 5 — End-to-end verification

- [ ] Redeploy the platform and run the full live sweep; every dashboard must report 0 empty panels (excluding anything explicitly deferred under *Out of scope*).
  Acceptance: `./deploy/redeploy.sh && sleep 30 && python3 scripts/check_dashboard_data.py --all`

## Out of scope

_(Populated by the loop as it finds genuine future-feature metrics with no demo path. Each gets a `bd` issue, not a checkbox.)_

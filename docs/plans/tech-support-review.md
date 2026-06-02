# Tech Support Review: Kinetix Platform Observability & Triage Gaps

## Context

A walk-through of the live platform at `kinetixrisk.ai`, `api.kinetixrisk.ai`,
and `grafana.kinetixrisk.ai`, plus an audit of the local observability stack
under `infra/docker-compose.observability.yml` and the dashboards/alerts under
`deploy/observability/`, performed from the perspective of an L1 support
engineer fielding queries from traders.

Findings verified before writing this plan:

- **Production Grafana is completely down.** `https://grafana.kinetixrisk.ai`
  renders only the Grafana "failed to load its application files" error page,
  which means the SPA cannot fetch its JS bundles. Classic
  `root_url` / `serve_from_sub_path` / reverse-proxy misconfiguration in the
  Helm `observability` chart. **Until this is fixed, every dashboard listed in
  `deploy/observability/dashboards/README.md` is unreachable in production** —
  there is currently *no* way to investigate a production incident visually.
- **Production UI renders only the word "Kinetix" to an unauthenticated visitor.**
  No login link, no status indicator, no product description. A trader hitting
  the URL with no session has no signal that the platform is up or how to log
  in.
- **The gateway returns 404 (not 401) for authenticated routes hit without
  credentials.** `https://api.kinetixrisk.ai/api/v1/positions` and `/api/v1/prices`
  both return `404 Not Found` despite the routes existing in
  `gateway/src/main/kotlin/com/kinetix/gateway/routes/`. Returning 404 instead
  of `401 Unauthorized` masks information from support engineers triaging
  "trader X says the API is broken" — we cannot distinguish "wrong URL" from
  "session expired" without reproducing in a browser. This is a triage tax we
  pay on every API ticket.
- **Local Tempo is crash-looping.** `docker logs kinetix-tempo` shows the
  process restarting every ~60s with
  `failed to init module services: error initialising module: distributor: failed to create distributor: the Kafka topic has not been configured`.
  Tracing is unreliable locally; we cannot follow a request across services.
  Configuration regression in `infra/docker-compose.observability.yml` or the
  Tempo config file.
- **Local Loki is not ready.** `curl http://localhost:3100/ready` returns `503`
  while the container reports `Up 3 hours`. Log queries silently return empty
  or fail. Health-check / readiness wiring is masking the actual state.
- **Risk-calculation failure has no Prometheus signal.** `alert-rules.yml`
  explicitly documents this gap: `RISK_CALCULATION_FAILED` events land in the
  audit DB and Loki only; no `risk_calculation_failed_total` counter exists.
  The rule that would alert on a failing risk pipeline is commented out with a
  TODO. Trader question "why is my VaR stale?" today requires reading
  audit-service logs by hand. (This overlaps with `docs/plans/grafana-v2.md` —
  the metric work is folded there; this plan only adds the runbook entry.)
- **Trader-facing support runbooks do not exist.** `docs/runbooks/` contains a
  single entry (`zero-downtime-deployment.md`) — operations-facing, not
  support-facing. There is no documented procedure for the top trader queries:
  *"my P&L looks wrong"*, *"my VaR is stale"*, *"I can't see my position"*,
  *"market data looks frozen"*, *"limit breach didn't fire"*. Every ticket
  requires reconstructing the diagnostic flow from scratch.
- **No "follow a trade" diagnostic script exists.** When a trader gives me
  a trade ID, the diagnostic flow is: query Postgres (`position-service.trades`),
  query audit-service for the hash-chained entry, query Loki for log lines
  mentioning the ID, query Kafka offsets for `trades.lifecycle`, check
  `risk.results` for downstream consumption. That is 5 manual queries across
  3 systems. A `scripts/follow-trade.sh <tradeId>` that prints the consolidated
  timeline is a 30-line shell script that would shave 10–15 minutes off every
  trade-lifecycle ticket.
- **No "is market data fresh?" probe.** Price staleness is exposed as a
  Prometheus metric (`price_staleness_seconds{instrument_id=…}`) and there is
  an alert rule on it, but neither support nor a trader has a one-shot tool to
  answer *"how fresh is AAPL right now?"*. This is the single most common
  question I get.
- **No DLQ inspector.** `alert-rules.yml` fires `KafkaDlqBacklog` and
  `KafkaDlqDepthGrowing` correctly, but when the alert fires there is no
  documented procedure or tool to (a) view the parked messages, (b) replay
  them after the upstream fix, or (c) discard poison messages. Manual `kcat`
  invocations leak procedural knowledge that should be repo-resident.
- **Audit chain integrity is never verified continuously.** ADR-0017 says the
  audit trail is hash-chained, but no scheduled job or CLI verifies the chain
  end-to-end. The first time we will discover a broken chain is when
  compliance asks.

## Status

This plan is loop-ready for `/work-plan`. Each `- [ ]` checkbox is one
independently committable change, ordered top-to-bottom by dependency, with an
`Acceptance:` command on the line directly after it. Advance end-to-end with
`/loop /work-plan docs/plans/tech-support-review.md`. The codebase must build and
tests must pass after every commit.

## Decisions applied

- **Scope boundary vs `docs/plans/grafana-v2.md`.** The Grafana v2 plan owns
  dashboards, panels, and the bulk of instrumentation work (including the
  missing `risk_calculation_failed_total` counter). This plan owns: (a)
  production observability availability, (b) runbooks, (c) CLI diagnostic
  scripts that compose existing telemetry across systems, (d) information-
  masking fixes (404 → 401, blank landing page → branded landing). If a
  checkbox is already covered by Grafana v2, it is cross-referenced and
  *not* duplicated.
- **Diagnostic scripts are Bash + `kcat`/`psql`/`curl`.** No new runtime
  dependencies. Scripts live under `scripts/support/` and are loop-acceptance-
  testable via shellcheck and a basic invocation against the docker-compose
  stack.
- **Runbooks are markdown under `docs/runbooks/`.** Each runbook is a single
  file named after the trader complaint it answers. Acceptance for each
  runbook checkbox is "the file exists and links to at least one dashboard
  + one log query + one CLI script".
- **404-vs-401 fix is gateway-only.** Upstream services may legitimately
  return 404; the gateway is the boundary that authoritatively knows
  "this path exists but you are not authenticated". Fix lives in the
  authentication plugin, not in each route.
- **Production Grafana fix is a Helm chart change.** The deploy is via
  `deploy/helm/kinetix/charts/observability` — `values.yaml` likely needs
  `grafana.server.root_url` and `grafana.server.serve_from_sub_path` set
  to match the ingress.

## CI/CD approval

The following checkboxes touch files normally covered by `## Guardrails` in
`CLAUDE.md`. They are pre-approved for `/work-plan`:

- `deploy/helm/kinetix/charts/observability/values.yaml` — Grafana
  `root_url`/`serve_from_sub_path` fix. This is observability configuration,
  not CI/CD pipeline configuration.
- `deploy/observability/alert-rules.yml` — adding the runbook URL annotation
  to existing alerts.
- New files under `scripts/support/` and `docs/runbooks/`.

No new third-party libraries are introduced. No new services, Kafka topics,
DB tables, or cross-service API contracts.

## Out of scope

- **Dashboard panel work, new dashboards, and metric instrumentation** — owned
  by `docs/plans/grafana-v2.md`. This plan references those gaps in the context
  section but does not re-checkbox them.
- **Trace propagation audit across services.** Worth doing, but blocked on
  local Tempo working (see first checkbox). Will be a follow-up plan.
- **Status page at `https://kinetixrisk.ai/status`.** Useful for trader self-
  service but requires a public, unauthenticated read of cached health state.
  Deferred to a follow-up that nominates the right owning service.
- **Runbook automation (auto-execute diagnostics from Grafana panel links).**
  Aspirational; first ship the manual runbooks and the diagnostic scripts.

## Improvements

### Phase 1 — Restore primary observability

- [ ] Fix the local Tempo crash-loop by configuring the distributor Kafka topic (or switching distributor receivers off in the all-in-one config) in `infra/docker-compose.observability.yml` and Tempo's config file. Recreate the container.
  Acceptance: `docker logs kinetix-tempo --tail 50 2>&1 | grep -c 'error running Tempo' | grep -q '^0$' && curl -sf http://localhost:3200/ready`
- [ ] Fix the local Loki readiness by inspecting `docker logs kinetix-loki` for the underlying error and correcting the config under `infra/docker-compose.observability.yml`.
  Acceptance: `curl -sf http://localhost:3100/ready`
- [ ] Fix production Grafana asset loading by setting `grafana.server.root_url`, `grafana.server.serve_from_sub_path`, and the matching ingress rewrite in `deploy/helm/kinetix/charts/observability/values.yaml` so the SPA can fetch its JS bundles at `https://grafana.kinetixrisk.ai/`.
  Acceptance: `helm template deploy/helm/kinetix/charts/observability | grep -E 'root_url|serve_from_sub_path' | grep -q 'grafana.kinetixrisk.ai'`

### Phase 2 — Close information-masking gaps

- [ ] In `gateway/src/main/kotlin/com/kinetix/gateway/plugins/` (authentication plugin), return `401 Unauthorized` with a `WWW-Authenticate: Bearer` header for any request to `/api/v1/*` that lacks a valid session, instead of falling through to a 404. Add a Kotest acceptance test asserting `GET /api/v1/positions` without an Authorization header returns 401, not 404.
  Acceptance: `./gradlew :gateway:acceptanceTest --tests "*UnauthenticatedRequest*"`
- [ ] Update `ui/index.html` and the root route in `ui/src/` to render a minimal branded landing page for unauthenticated visitors: product name, one-sentence description, a "Log in" button pointing at the Keycloak login flow, and a link to `/status` (placeholder, deferred). Add a Playwright test under `ui/e2e/` asserting the page renders the login button.
  Acceptance: `cd ui && npx playwright test e2e/unauthenticated-landing.spec.ts`

### Phase 3 — Support runbooks (`docs/runbooks/support/`)

- [ ] Add `docs/runbooks/support/README.md` indexing every support runbook, with the format: "Trader complaint → runbook → primary dashboard → primary CLI script".
  Acceptance: `test -f docs/runbooks/support/README.md && grep -c '\.md' docs/runbooks/support/README.md | awk '$1 >= 5 { exit 0 } { exit 1 }'`
- [ ] Add `docs/runbooks/support/pnl-looks-wrong.md`: position-service → price snapshot → risk-orchestrator path; how to confirm latest position, latest price, latest revaluation timestamp; links to `trade-lifecycle.json` and `risk-run-health.json` dashboards.
  Acceptance: `test -f docs/runbooks/support/pnl-looks-wrong.md && grep -q 'risk-run-health' docs/runbooks/support/pnl-looks-wrong.md && grep -q 'trade-lifecycle' docs/runbooks/support/pnl-looks-wrong.md`
- [ ] Add `docs/runbooks/support/var-is-stale.md`: how to identify the last successful VaR run per book, where failure events surface today (audit-service + Loki), and how to trigger a re-run.
  Acceptance: `test -f docs/runbooks/support/var-is-stale.md && grep -q 'audit-service' docs/runbooks/support/var-is-stale.md`
- [ ] Add `docs/runbooks/support/position-missing.md`: trade booking flow through position-service, Kafka `trades.lifecycle`, audit chain entry; how to confirm the trade was published and consumed.
  Acceptance: `test -f docs/runbooks/support/position-missing.md && grep -q 'trades.lifecycle' docs/runbooks/support/position-missing.md`
- [ ] Add `docs/runbooks/support/market-data-frozen.md`: price-service health, last update timestamp per instrument, `price_staleness_seconds` Prometheus query, links to the `PriceStale`/`PriceSeverelyStale` alerts.
  Acceptance: `test -f docs/runbooks/support/market-data-frozen.md && grep -q 'price_staleness_seconds' docs/runbooks/support/market-data-frozen.md`
- [ ] Add `docs/runbooks/support/limit-breach-didnt-fire.md`: limits engine path, notification-service WebSocket fanout, `LimitBreached` alert wiring, and how to verify whether the breach was detected and only the notification was dropped vs not detected at all.
  Acceptance: `test -f docs/runbooks/support/limit-breach-didnt-fire.md && grep -q 'notification-service' docs/runbooks/support/limit-breach-didnt-fire.md`

### Phase 4 — Diagnostic CLI scripts (`scripts/support/`)

- [ ] Add `scripts/support/price-freshness.sh <instrument_id>` that prints the current `price_staleness_seconds` from Prometheus and the last update timestamp from the gateway. Includes a shellcheck-clean header and a `--all` mode that shows the 20 stalest instruments.
  Acceptance: `shellcheck scripts/support/price-freshness.sh && bash scripts/support/price-freshness.sh --all | head -1 | grep -q .`
- [ ] Add `scripts/support/follow-trade.sh <trade_id>` that: queries `position-service.trades` over the gateway, queries audit-service for the hash-chained entries referencing the trade, runs a Loki query for log lines mentioning the trade ID, and prints a single consolidated timeline. Read-only — no Kafka writes.
  Acceptance: `shellcheck scripts/support/follow-trade.sh && bash scripts/support/follow-trade.sh --help | grep -q 'trade'`
- [ ] Add `scripts/support/dlq-inspect.sh <topic>` that lists the parked messages on a `.dlq` topic via `kcat` with headers (failure reason, original offset, retry count) and supports `--peek N` to show only the first N messages. No replay or delete operations — inspection only.
  Acceptance: `shellcheck scripts/support/dlq-inspect.sh && bash scripts/support/dlq-inspect.sh --help | grep -q 'topic'`
- [ ] Add `scripts/support/audit-chain-verify.sh` that walks the audit-service hash chain from the most recent entry backwards N entries (default 1000), verifying each link, and prints PASS / FAIL with the offending entry ID if the chain breaks.
  Acceptance: `shellcheck scripts/support/audit-chain-verify.sh && bash scripts/support/audit-chain-verify.sh --help | grep -q 'verify'`

### Phase 5 — Wire runbooks into alerts

- [ ] In `deploy/observability/alert-rules.yml`, add a `runbook_url` annotation to every alert that has a corresponding runbook in `docs/runbooks/support/`. The URL points at the markdown file on the `main` branch of the GitHub repo. This makes every Alertmanager / Slack notification link directly to the diagnostic procedure.
  Acceptance: `grep -c 'runbook_url' deploy/observability/alert-rules.yml | awk '$1 >= 5 { exit 0 } { exit 1 }'`

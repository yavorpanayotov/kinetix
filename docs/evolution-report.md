# Kinetix Evolution Report

A risk management platform built from a single sentence on **2026-02-10** to a 1,580-commit, 12-service institutional system by **2026-04-14** — roughly 9 weeks of intense, AI-paired construction across 393 conversation sessions and 2,113 prompts.

---

## 1. Project Timeline

**2026-02-10 — Genesis & Plan**
"this project will be for building a complete, modern, insightful and AI powered risk management system for big financial institutions." That single prompt set everything in motion. The first day produced a written architecture, ADRs, and the empty Gradle monorepo with a version catalog. The first session crashed and lost progress, prompting the durable rule: "make sure all is documented somewhere in the project going forward."

**2026-02-11 → 02-12 — Skeletons**
Seven Ktor services scaffolded with health endpoints. Python risk-engine wired up with `uv` and pytest. TDD mandated from prompt #2: *"we will be following a strict tdd approach… always run all tests before a commit."*

**2026-02-20 → 02-21 — Core Vertical Slice (huge day)**
The system became real in 24 hours: React/Vite/Tailwind UI, Docker Compose stack, the position domain with P&L, Exposed/Flyway persistence, Kafka publishing, gateway REST, audit service, end-to-end acceptance test, market data ingestion, WebSocket P&L, the first VaR pipeline (Python gRPC), VaR dashboard UI, Prometheus/Grafana/Loki/Tempo observability, ML predictors (LSTM vol, GBT credit, anomaly detection), historical stress scenarios (GFC, COVID, Taper Tantrum), Greeks, FRTB regulatory reports, alert rules, JWT/RBAC, circuit breaker, rate limiting, Gatling load tests, and CI. 73 commits in one day.

**2026-02-22 → 02-26 — Liveable Local Dev**
Dev data seeders, `dev-up.sh` / `dev-down.sh` / `dev-restart.sh`, then a long stretch of UI polish on the Risk tab: tooltips for VaR/ES, Grafana dashboards per service, valuation job timeline with search/zoom/pagination, click-to-zoom timecharts.

**2026-02-27 → 03-04 — Valuation Pipeline & Stress Scenarios**
Unified VaR + Greeks under a single `Valuate` RPC, introduced PV as a first-class output, restructured tests into a 3-tier taxonomy. Big stress-scenario UI sprint (custom builder, comparison view, governance panel, CSV export, tooltips, position-level drill-down). Playwright E2E coverage rolled out across Risk, Positions, and Trades tabs (140+ tests).

**2026-03-11 → 03-19 — Instrument Type Hierarchy & Book Rename**
The big architectural pivot. A typed `InstrumentType` sealed hierarchy (11 subtypes) replaced loose strings, threaded through Kotlin → proto → Python → UI. Then the cross-cutting `portfolio → book` rename swept every service, database, Kafka event, gateway DTO, and UI component in a single day (March 19, 109 commits). Cross-book VaR with diversification, correlation heatmaps, and marginal/incremental VaR landed the same week.

**2026-03-23 → 03-26 — Trader Review & Allium Specs**
A `/trader`-driven review produced `trader-review-team-plan-23.03.2026.md`. Allium specifications were generated from the codebase via `/distill`, then 137 spec-vs-code divergences were resolved across 20 specs using the `/weed` agent. SA-CCR counterparty risk, OMS/FIX hardening, scenario governance (correlated/2D-grid/historical replay), reconciliation breaks, and FIX session events all landed.

**2026-03-27 → 03-30 — Demo Mode**
Pivot to portfolio-showcase mode: Keycloak-bypass auth, persona switcher (Trader / Risk Manager / CRO), DEMO badge, dismissible welcome strip, nightly demo reset cron, 30-day VaR timeline seeding, automatic EOD scheduled jobs, gateway demo-reset aggregation endpoint.

**2026-04-03 → 04-07 — Hardening & Polish**
Comprehensive error-handling pass: `ClientResponse` Either type with `ServiceUnavailable`/`UpstreamError`/`NetworkError` variants, HTTP timeouts on every client, stale-cache fallback for VaR, retry buttons across panels, ErrorBoundary wrapping major risk panels, partial-failure tests. Institutional-scale demo data (83 instruments, 252 daily closes, multi-leg derivatives, ~300 generated trades). Per-instrument analytical Greeks threaded through gateway to UI. `dto/` → `dtos/` package refactor, one-type-per-file enforced. Idempotent trade amend/cancel.

**2026-04-08 → 04-14 — Aftermath**
The `yavorpanayotovdr` GitHub account was suspended on April 13 and the conversation pivoted to recovering local state. Final week is small fixes and an evolution report request.

---

## 2. Initial Vision vs Current State

**Original goal:** an AI-powered risk system for investment banks and hedge funds, multi-asset, with traditional VaR/Greeks plus ML overlays.

**Pivots:**
- "I will not need traditional risk calculations for now" (Feb 10) — quickly reversed; classical VaR/Greeks/FRTB became the spine of the system.
- "the UI is quite important and is not for the future" (Feb 10) — UI became a dominant share of effort, with six tabs, dark mode, accessibility, Playwright E2E, demo personas.
- **From product to portfolio piece (April):** "kinetix is in demo mode, it's just a project I am using for my CV." Demo mode infrastructure became first-class.
- **From hand-coded to spec-driven:** Allium specifications were distilled from code in late March, then used as the source of truth for divergence audits via `/weed`.

---

## 3. Technical Evolution

**Stack additions (chronological):** Gradle monorepo → Ktor + Exposed + Flyway + Kafka → React/Vite/Tailwind/Vitest → Python `uv`/pytest/gRPC → Prometheus/Grafana/Loki/Tempo → LSTM/GBT/IsolationForest ML → Keycloak JWT → Playwright → TimescaleDB hypertables/continuous aggregates → Redis (Lettuce) → Helm charts → buf proto lint → Allium DSL.

**Architecture shifts:**
- **Acceptance test taxonomy** restructured into unit / integration / acceptance / end-to-end (Feb 26).
- **Kafka schemas consolidated** into the `common` module after a TradeEvent schema-drift incident (Mar 1).
- **Risk-orchestrator decoupled** from position-service via HTTP client interface (Phase 1.5).
- **`portfolio → book`** rename across all services (Mar 19) — single-day cross-cutting refactor.
- **`InstrumentType` sealed hierarchy** replaced string instrument types with typed positions (Phase A/B/C, mid-March).
- **`ClientResponse` Either type** introduced for typed error handling (Apr 3).
- **`dto/` → `dtos/` one-type-per-file** package refactor (Apr 7).

**Key integrations:** OTel Collector → Prometheus → Grafana dashboards per service; gRPC contract tests against the real Python risk-engine; Testcontainers for Kafka/Postgres integration; GitHub Actions CI per-module matrix; Keycloak with demo-bypass mode.

---

## 4. Problems & Solutions Log

| Problem | Solution | Date |
|---|---|---|
| First session crashed, all progress lost | Mandate persisted plan + ADR docs | 2026-02-10 |
| Testcontainers fails in `common` module | Move integration tests to service modules | 2026-02-21 |
| Exposed `shouldThrow` swallows exceptions in `newSuspendedTransaction` | Validate before transactional block | 2026-02-21 |
| Risk-engine module not found in Docker | `PYTHONPATH=/app/src` env var | 2026-02-21 |
| Price history sort order regression | Acceptance test catches DESC bug | 2026-03-01 |
| TradeEvent schema drift across 3 services | Consolidate schemas in `common`, add compat tests | 2026-03-01 |
| VaR cache empty on startup | Seed cache from DB on startup | 2026-03-02 |
| What-If panel stealing focus on every keystroke | Re-scope refs | 2026-03-03 |
| TimescaleDB migrations fail in plain Postgres tests | Conditional migrations on extension availability | 2026-03-26 |
| Audit hash chain inconsistent across services | Normalize `tradedAt` to microseconds before hashing | 2026-03-19 |
| Cross-cutting `portfolio` ambiguity (book vs portfolio) | Single-day rename across all 12 services | 2026-03-19 |
| Spec drift between Allium specs and code | `/weed` agent + 137 divergence fixes | 2026-03-26 |
| Trade amend/cancel not idempotent → E2E flakes | Make operations idempotent, align tests | 2026-04-07 |
| `yavorpanayotovdr` GitHub account suspended | Recover from local clone | 2026-04-13 |

---

## 5. Abandoned Approaches

- **What-If volatility-bump analysis** in the Risk tab — removed (Feb 27, `05a3bf01`) when stress scenarios subsumed the use case.
- **Inline VaR sparkline** on the Risk tab — replaced by Grafana-style trend chart with zoom (Feb 26).
- **Separate `CALCULATE_GREEKS` step** in the valuation pipeline — folded into `CALCULATE_VAR` then renamed to `VALUATION` (Feb 27).
- **"Calculation Runs" / "Pipeline" naming** — renamed twice (Calculation Runs → Calculation Jobs → Valuation Jobs) as the domain language settled.
- **Mocked database tests** — explicitly avoided in favour of Testcontainers for infra boundaries.
- **Per-service Kafka event files** — 8 duplicates deleted; consolidated to `common`.
- **Native HTML `title` tooltips** — replaced everywhere with click-only popovers after hover-only proved a "hidden feature" (Feb 25).

---

## 6. Current State Summary

**Working** — 12 services (gateway, position, price, rates, vol, correlation, ref-data, risk-orchestrator, regulatory, notification, audit, risk-engine), end-to-end VaR with Historical / Parametric / Monte Carlo modes, full Greeks (Delta/Gamma/Vega/Theta/Rho + Vanna/Volga/Charm), FRTB SBM/DRC/RRAO with CSV/XBRL export, cross-book VaR with diversification, SA-CCR counterparty risk, stress testing with governance, P&L attribution, audit hash chain, demo mode with persona switching, dark mode, CSV export everywhere, six-tab UI with full Playwright coverage, Helm charts for production deploy.

**In progress** — Recovering from the GitHub account suspension; the local clone is the only authoritative copy as of Apr 13.

**Known issues / tech debt** —
- Testcontainers Docker connectivity broken in `common` module.
- Exposed + Kotest `shouldThrow` incompatibility documented but not fixed upstream.
- 19 unmerged agent worktrees in `.claude/worktrees/` from parallel agent runs.
- Risk-engine `sqrt(T)` VaR scaling limitation documented but not addressed.

---

## 7. Session References

- **Genesis:** `5d799de3` (Feb 10 — original system prompt and plan)
- **Big vertical slice:** Feb 20–21 sessions (no single ID, ~73 commits)
- **First trader review:** `9ab051d4` (Feb 28 — `/trader` Greeks/valuation feedback)
- **Phase planning:** `ff240144` (Feb 28 → Mar 1, 30 prompts)
- **Big UI hardening sprint:** `d30bd682` (Mar 12–13, 32 prompts)
- **Trader review team plan:** `1e352111` (Mar 23–25, 39 prompts — produced the trader-review plan)
- **Allium spec audit:** `5affab94` (Mar 26–28, 42 prompts — `/weed` campaign)
- **Demo mode + GitHub recovery:** Apr 3–13 (multiple short sessions)

Built a 12-service institutional risk platform from a single sentence in nine weeks using strict TDD, AI agent teams, and an Allium spec-driven workflow.

---

## Where it stands today (2026-05-18)

Five weeks on from the previous report cut, the platform now reads as a mature codebase rather than a sprint output. The 12 microservices remain the production spine — gateway, position, price, rates, vol, correlation, ref-data, risk-orchestrator, regulatory, notification, audit, and the Python risk-engine — and a thirteenth, `ai-insights-service`, is planned in the AI v1 plan as the home for the first LLM-powered product features: a VaR Explainer that narrates risk moves in plain English, and Report Commentary that drafts the prose around regulatory exports. Both route through a Claude Code subscription via the Claude Agent SDK rather than direct API keys, which keeps the operational model consistent with how the codebase itself was built. Architectural decisions have climbed past 36 ADRs and behavioural specifications now sit at 24 Allium specs, both indexed for navigation. Total commit count is around 2,090. Two large plans completed since 2026-04-14 — demo-v2 (institutional-scale showcase data and book profiles) and the testing-overhaul (property-based tests across risk, P&L, correlation PSD, and volatility surfaces). Process-surface polish landed in early May: an auto-generated ADR index, an auto-generated specs index, a HOW_IT_WAS_BUILT.md narrative of the AI-assisted workflow, and a "Built with Claude Code" hero on the README with auto-computed stats. AI v1 — the VaR Explainer and Report Commentary — shipped behind `ai-insights-service`, and on **2026-05-21** the Kinetix Copilot (AI v2) completed: a proactive morning brief that narrates overnight risk at 06:45, intraday threshold-breach push over WebSocket, inline explainers across seven data surfaces, and a ⌘K free-form copilot — every numeric token sourced and cited against Kinetix's own data, with a deterministic demo-mode fallback so CI and public demos never call a live SDK ([ADR-0036](adr/ADR-0036-ai-copilot-architecture.md)). With v2 closed, the AI copilot is now a first-class part of the platform rather than a planned chapter.


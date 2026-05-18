# Demo Mode: Always-On Production Showcase

## Status

This plan is loop-ready for `/work-plan`. Each `- [ ]` checkbox below is one
independently-committable change, ordered top-to-bottom by dependency, with an
`Acceptance:` command on the line directly after it. Advance end-to-end with
`/loop /work-plan plans/demo-v2.md`.

## Decisions applied

- **Service name** `demo-orchestrator`. Pre-approved as a new module.
- **Trading hours** 09:00–16:30 UTC weekdays, 90s tick. Overridable via
  `DEMO_TRADING_HOURS_START` / `DEMO_TRADING_HOURS_END` /
  `DEMO_TRADE_CADENCE_SECONDS`.
- **Limit thresholds** ~80% of current 95% VaR and ~70% of current absolute
  delta. Re-seeded daily at 06:05 UTC.
- **Trade booking endpoint** `POST /api/v1/books/{bookId}/strategies/{strategyId}/trades`
  with `StrategyTradeRequest` (route in
  `position-service/.../routes/StrategyRoutes.kt`). Strategy ids reused from
  `DevDataSeeder`.
- **Limits API** Subagent greps `risk-orchestrator/.../routes/` for a dedicated
  per-book VaR-limit route. If none exists, fall back to
  `POST /api/v1/risk/budgets` (`CreateRiskBudgetRequest`,
  `budgetType=VAR_95|DELTA_ABS`).
- **Exposure read** `GET /api/v1/risk/hierarchy/BOOK/{bookId}` returns the
  per-book VaR + Greek aggregates used to size limits.
- **EOD observation** Kafka topic `risk.official-eod`, event
  `OfficialEodPromotedEvent` (published by
  `risk-orchestrator/.../kafka/KafkaOfficialEodPublisher.kt`). The dedicated
  topic supersedes the original "filter `risk.results` by AUTO_CLOSE" idea.
- **Backtest call** `POST /api/v1/regulatory/backtest/{bookId}` with
  `BacktestRequest`. Last-30-days VaR vs realised P&L sourced from
  `daily_risk_snapshots`.
- **Submission draft** `POST /api/v1/submissions` with
  `reportType=DAILY_RISK_SUMMARY`, `preparerId="demo-orchestrator"`,
  `deadline=T+1 17:00 UTC`.
- **Books and counterparties** reuse `DevDataSeeder` constants (8 books, 6
  counterparties). No new seed data.
- **TDD** test + minimal implementation land in the same commit so every commit
  builds green (CLAUDE.md "Commit Practices").

## CI/CD approval

No CI/CD pipeline file changes anticipated. `deploy/docker-compose.services.yml`
and `deploy/helm/kinetix/charts/demo-orchestrator/` are deployment configs, not
CI/CD pipelines — proceed without an explicit approval gate. If a subagent
discovers it must touch a `.github/workflows/*` or similar pipeline file, STOP
and flag (per `/work-plan` guardrails).

## Out of scope

- Scheduled "surprise events" (vol spikes / regime shocks). Explicitly
  deprioritised — the demo should look like normal production.
- Any modification to the 12 existing schedulers in `risk-orchestrator` /
  `price-service`.
- New Kafka topics, new database tables, new API contracts in other services.
- New seed data — reuse `DevDataSeeder` constants.
- New library dependencies in `demo-orchestrator/build.gradle.kts` beyond what
  `risk-orchestrator` already pulls in.

## Execution plan

### PR 0 — Module scaffolding

- [x] 0.1 Create `demo-orchestrator/` module: `build.gradle.kts` mirroring
      `risk-orchestrator/build.gradle.kts` (same `kinetix.kotlin-service` +
      `kinetix.kotlin-testing` plugins, no new deps); `Application.kt` boots a
      Ktor server with a `/health` endpoint and reads `DEMO_MODE` — when false
      the process starts but every scheduler/consumer is a no-op. Add
      `include("demo-orchestrator")` to `settings.gradle.kts`.
      Acceptance: `./gradlew :demo-orchestrator:build`
- [x] 0.2 Add `config/DemoConfig.kt` reading `DEMO_MODE`,
      `POSITION_SERVICE_URL`, `RISK_ORCHESTRATOR_URL`,
      `REGULATORY_SERVICE_URL`, `DEMO_TRADING_HOURS_START` (default `09:00`),
      `DEMO_TRADING_HOURS_END` (default `16:30`),
      `DEMO_TRADE_CADENCE_SECONDS` (default `90`). Kotest unit test covering
      defaults and overrides.
      Acceptance: `./gradlew :demo-orchestrator:test --tests "*DemoConfigTest"`

### PR 1 — Limit seeding

- [x] 1.1 Add `profile/DemoBookProfile.kt` (data class: `bookId`,
      `tradeProbability`, `instrumentIds`, `notionalRangeUsd`, `assetClass`)
      and `profile/DemoBookProfiles.kt` mapping each of the 8 `DevDataSeeder`
      book ids (`equity-growth`, `tech-momentum`, `emerging-markets`,
      `fixed-income`, `multi-asset`, `macro-hedge`, `balanced-income`,
      `derivatives-book`) to a profile. One type per file. Unit test asserts
      every seeded book has exactly one profile.
      Acceptance: `./gradlew :demo-orchestrator:test --tests "*DemoBookProfilesTest"`
- [x] 1.2 Add `client/RiskOrchestratorClient.kt` interface plus
      `client/RiskOrchestratorHttpClient.kt` Ktor-CIO implementation. Methods:
      `readBookExposure(bookId): BookExposureSnapshot` against
      `GET /api/v1/risk/hierarchy/BOOK/{bookId}`; `seedLimit(bookId, limitType,
      threshold)` against the limit route discovered by grep (fall back to
      `POST /api/v1/risk/budgets`). DTOs in `client/dtos/`, one per file.
      MockEngine-based unit test.
      Acceptance: `./gradlew :demo-orchestrator:test --tests "*RiskOrchestratorClientTest"`
- [x] 1.3 Add `schedule/LimitSeedJob.kt`. Behaviour: for each seeded book read
      current exposure, post limits at 80% of `varValue` and 70% of absolute
      delta. Idempotent on `(bookId, limitType)`. Unit test drives the job
      with a MockK fake client and asserts the right thresholds were posted.
      Acceptance: `./gradlew :demo-orchestrator:test --tests "*LimitSeedJobTest"`
- [x] 1.4 Wire `LimitSeedJob` into `Application.kt`: run once at startup and
      schedule daily at 06:05 UTC. All wiring guarded by `DEMO_MODE=true`.
      Acceptance: `./gradlew :demo-orchestrator:build`

### PR 2 — Simulated trading

- [x] 2.1 Add `client/PositionServiceClient.kt` interface plus
      `client/PositionServiceHttpClient.kt` Ktor-CIO implementation. Method
      `bookTrade(bookId, strategyId, request: StrategyTradeRequest)` against
      `POST /api/v1/books/{bookId}/strategies/{strategyId}/trades`. Local DTO
      mirrors `position-service`'s `StrategyTradeRequest` field-for-field.
      MockEngine unit test.
      Acceptance: `./gradlew :demo-orchestrator:test --tests "*PositionServiceClientTest"`
- [x] 2.2 Add `schedule/SimulatedTraderJob.kt`. Each tick (every
      `DEMO_TRADE_CADENCE_SECONDS`): if current UTC clock is inside trading
      hours AND Mon–Fri, then for each book roll `profile.tradeProbability`;
      on hit generate 1–3 trades drawn from the profile's `instrumentIds` and
      `notionalRangeUsd` and post via `PositionServiceClient`. Outside hours
      / weekends: no-op. Counterparty assignment cycles through the 6
      `DevDataSeeder` counterparties. Unit test covers outside-hours no-op,
      weekend no-op, in-hours generates profile-respecting payloads.
      Acceptance: `./gradlew :demo-orchestrator:test --tests "*SimulatedTraderJobTest"`
- [x] 2.3 Wire `SimulatedTraderJob` into `Application.kt` behind
      `DEMO_MODE=true`.
      Acceptance: `./gradlew :demo-orchestrator:build`
- [ ] 2.4 Add `src/acceptanceTest/.../SimulatedTradingAcceptanceTest.kt` —
      Testcontainers Postgres + Kafka, a Ktor mock server stubbing
      `position-service` on a random port (per CLAUDE.md: real wire, no
      transport mocks). One scheduler tick on a forced in-hours clock should
      produce a `StrategyTradeRequest` POST observed by the mock.
      Acceptance: `./gradlew :demo-orchestrator:acceptanceTest --tests "*SimulatedTradingAcceptanceTest"`

### PR 3 — EOD observer

- [ ] 3.1 Add `client/RegulatoryServiceClient.kt` interface plus
      `client/RegulatoryServiceHttpClient.kt` Ktor-CIO implementation.
      Methods: `runBacktest(bookId, BacktestRequest)` and
      `createSubmission(CreateSubmissionRequest)`. DTOs in `client/dtos/`.
      MockEngine unit test.
      Acceptance: `./gradlew :demo-orchestrator:test --tests "*RegulatoryServiceClientTest"`
- [ ] 3.2 Add `schedule/EodCycleObserverJob.kt` — Kafka consumer on
      `risk.official-eod` for `OfficialEodPromotedEvent`. On each event: wait
      30s, then call `runBacktest` followed by `createSubmission`
      (`reportType=DAILY_RISK_SUMMARY`, `preparerId="demo-orchestrator"`,
      `deadline=T+1 17:00 UTC`). Reuse the `RetryableConsumer` pattern from
      `notification-service`'s `RiskResultConsumer`. Unit test drives the
      consumer with a fake event and asserts both regulatory calls happen.
      Acceptance: `./gradlew :demo-orchestrator:test --tests "*EodCycleObserverJobTest"`
- [ ] 3.3 Wire `EodCycleObserverJob` into `Application.kt` behind
      `DEMO_MODE=true`.
      Acceptance: `./gradlew :demo-orchestrator:build`

### PR 4 — Deployment

- [ ] 4.1 Add a `demo-orchestrator` block to
      `deploy/docker-compose.services.yml`, mirroring the `risk-orchestrator`
      entry: `DEMO_MODE=true`, the three service URLs, trading-hours envs,
      `depends_on` `position-service` / `risk-orchestrator` /
      `regulatory-service`.
      Acceptance: `grep -q '^[[:space:]]*demo-orchestrator:' deploy/docker-compose.services.yml`
- [ ] 4.2 Copy `deploy/helm/kinetix/charts/risk-orchestrator/` to
      `deploy/helm/kinetix/charts/demo-orchestrator/`, updating `Chart.yaml`
      `name` and `values.yaml` `image` + `port`. Demo-only — enabled in the
      demo values overlay only.
      Acceptance: `test -f deploy/helm/kinetix/charts/demo-orchestrator/Chart.yaml && grep -q '^name: demo-orchestrator' deploy/helm/kinetix/charts/demo-orchestrator/Chart.yaml`
- [ ] 4.3 Final module check — full unit + acceptance suite green.
      Acceptance: `./gradlew :demo-orchestrator:check`

---

## Reference: original design

Everything below is unchanged design context, kept so the per-checkbox
subagent has full scope detail.

### Context

Kinetix is going on show in demo mode. The system already has substantial
demo-friendly infrastructure — a 252-day deterministic price tape, 8 seeded
books, 30 days of historical VaR, 252 days of P&L attribution, 5 demo
personas, and twelve scheduled jobs in `risk-orchestrator` that already make
the platform feel alive at a steady cadence (VaR recalc every 60s, regime
detection every 15min, factor decomposition daily at 04:00 UTC, auto-close at
17:30 UTC).

But a viewer dropping into the demo today will see a *static-looking*
environment. Prices wiggle (good) and VaR recalculates (good), but **nothing
is happening on the book**: no new trades, no limit breaches, no EOD
promotion that flows into a backtest exception, no regulatory submission
being drafted. A trader watching this will say "you've got the screens but
where's the workflow?"

This plan adds a new **`demo-orchestrator`** service — approved by the user
as a new module — whose sole job is to choreograph the *production-shaped*
activity that a real risk desk would generate on its own. It runs on the real
clock (no time compression), always on. Viewers arriving at any hour see a
system that looks like it's halfway through a normal trading day.

### Current State

**Already scheduled (no changes needed):**

| Job | Location | Cadence |
|---|---|---|
| ScheduledVaRCalculator | `risk-orchestrator/.../schedule/ScheduledVaRCalculator.kt` | 60s |
| ScheduledCrossBookVaRCalculator | `risk-orchestrator/.../schedule/ScheduledCrossBookVaRCalculator.kt` | 120s |
| ScheduledSodSnapshotJob | `risk-orchestrator/.../schedule/ScheduledSodSnapshotJob.kt` | 60s check, fires ≥06:00 UTC |
| ScheduledAutoCloseJob | `risk-orchestrator/.../schedule/ScheduledAutoCloseJob.kt` | 17:30 UTC weekdays |
| ScheduledFactorDecomposition | `risk-orchestrator/.../schedule/ScheduledFactorDecomposition.kt` | 04:00 UTC daily |
| ScheduledRegimeDetector | `risk-orchestrator/.../schedule/ScheduledRegimeDetector.kt` | 15min |
| ScheduledCounterpartyRiskCalculator | `risk-orchestrator/.../schedule/ScheduledCounterpartyRiskCalculator.kt` | 18:00 UTC daily |
| PriceFeedSimulator (GBM) | `price-service/.../feed/PriceFeedSimulator.kt` | 60s |
| DemoTapeReplaySweeper | `price-service/Application.kt:227-243` | every few seconds (opt-in via `DEMO_TAPE_REPLAY_ENABLED`) |
| TradeEventConsumer → triggers intraday VaR | `risk-orchestrator/Application.kt:628-634` | event-driven |
| PriceEventConsumer → triggers intraday VaR | `risk-orchestrator/Application.kt:653-664` | event-driven (60s cooldown) |

**Demo gaps identified:**

1. **No simulated trading flow.** Trades only enter via UI or external API.
   Trade blotter, intraday P&L, and limit checks stay quiet during the demo
   window.
2. **EOD promotion is auto-fired but downstream isn't observable.**
   `ScheduledAutoCloseJob` auto-promotes at 17:30 UTC, but nothing nudges
   regulatory-service to refresh backtest exceptions or draft a daily
   submission — the Reports / Regulatory / EOD Timeline tabs show no fresh
   artifact each day.
3. **Limit breaches don't fire on their own.** The notification pipeline
   (`LimitBreachEventConsumer`) is wired, but no limits are pre-configured
   near current book exposures, so the Alerts tab stays empty unless trades
   happen to blow through hardcoded thresholds.

### Design

A new Kotlin/Ktor service `demo-orchestrator/` modeled on the existing
service skeletons (mirror `risk-orchestrator`'s module structure). The
service runs three scheduled jobs, all guarded by `DEMO_MODE=true`. In
non-demo deployments the service is simply absent from the compose / helm
profile.

#### Module layout

```
demo-orchestrator/
  build.gradle.kts                                        # Ktor + common, like other services
  src/main/kotlin/com/kinetix/demo/
    Application.kt                                        # Bootstrap, scheduler wiring, DEMO_MODE gate
    schedule/
      SimulatedTraderJob.kt                               # Books trades during demo trading hours
      LimitSeedJob.kt                                     # Configures limits near current exposures (runs at startup + 06:05 UTC daily)
      EodCycleObserverJob.kt                              # Watches risk.official-eod for EOD promotion, fans out to regulatory
    client/
      PositionServiceClient.kt                            # HTTP client to position-service for trade booking
      RegulatoryServiceClient.kt                          # HTTP client to regulatory-service for backtest / submission triggers
      RiskOrchestratorClient.kt                           # HTTP client for limit configuration + exposure read
    profile/
      DemoBookProfile.kt                                  # Per-book trading style (HFT-like / macro / long-only)
      DemoBookProfiles.kt                                 # The 8 books mapped to profiles
    config/
      DemoConfig.kt                                       # Cadence, trading hours, breach probability knobs
  src/test/kotlin/com/kinetix/demo/
    schedule/SimulatedTraderJobTest.kt                    # Unit (Kotest FunSpec, MockK)
    schedule/LimitSeedJobTest.kt
    schedule/EodCycleObserverJobTest.kt
  src/acceptanceTest/kotlin/com/kinetix/demo/
    SimulatedTradingAcceptanceTest.kt                     # Real position-service + Kafka via Testcontainers
```

#### Job 1 — `SimulatedTraderJob`

**Cadence:** every 90 seconds during demo trading hours (09:00–16:30 UTC
weekdays).

**Behaviour per tick:**
- For each of the 8 demo books, with `profile.tradeProbability` chance, book
  1–3 trades.
- Trade selection driven by `DemoBookProfile`:
  - `tech-momentum` → buys/sells AAPL/MSFT/GOOGL/AMZN/TSLA, small notional,
    high frequency
  - `macro-hedge` → larger FX / rates trades, low frequency
  - `balanced-income` → bond ladder rebalancing
  - etc.
- Posts trades via `POST /api/v1/books/{bookId}/strategies/{strategyId}/trades`
  with `StrategyTradeRequest`.
- Trades feed into `trades.lifecycle` Kafka → `TradeEventConsumer` →
  intraday VaR fires automatically. No need to call risk-orchestrator
  directly.
- Counterparty assignment from the 6 seeded exposures (reuse `DevDataSeeder`
  constants) so counterparty risk numbers move too.

**Why this works:** the existing trade event consumers and intraday P&L
pipeline do the heavy lifting. We just inject trades; the rest of the system
reacts the way it would in production.

#### Job 2 — `LimitSeedJob`

**Cadence:** runs at service startup, then every day at 06:05 UTC (just
after SOD baseline at 06:00).

**Behaviour:**
- Read current book exposures from risk-orchestrator
  (`GET /api/v1/risk/hierarchy/BOOK/{bookId}`).
- For each book, post limits set to **~80% of current 95% VaR** and **~70%
  of current absolute delta** — i.e. tight enough that the simulated trading
  flow will breach a couple per day, loose enough not to breach constantly.
- Limits idempotent by `(bookId, limitType)` key — re-running just updates
  thresholds.

**Why daily reseed:** after EOD promotion, baseline exposures change.
Re-tightening daily guarantees breaches keep firing across days rather than
only on day one of the deploy.

#### Job 3 — `EodCycleObserverJob`

**Cadence:** Kafka consumer on `risk.official-eod` topic
(`OfficialEodPromotedEvent` published by
`risk-orchestrator/.../kafka/KafkaOfficialEodPublisher.kt`).

**Behaviour per event:**
1. Wait 30s (let materialized-view refresh and counterparty risk job
   complete).
2. Call regulatory-service:
   - `POST /api/v1/regulatory/backtest/{bookId}` — appends today's VaR vs
     realised P&L (may produce a Kupiec/Christoffersen exception).
   - `POST /api/v1/submissions` — drafts a `DAILY_RISK_SUMMARY` row
     (status `DRAFT`, deadline T+1).

**Why a separate observer rather than baking into ScheduledAutoCloseJob:**
keeps demo-only behaviour out of the production scheduler.
`ScheduledAutoCloseJob` stays focused on EOD promotion; the observer is a
demo-side reaction.

### Configuration

`DEMO_MODE=true` env var on the demo-orchestrator container. Default
`false`, so the service is a no-op outside demo profiles.

Add to `docker-compose.services.yml`:
```yaml
demo-orchestrator:
  build: ./demo-orchestrator
  environment:
    DEMO_MODE: "true"
    POSITION_SERVICE_URL: "http://position-service:8080"
    RISK_ORCHESTRATOR_URL: "http://risk-orchestrator:8080"
    REGULATORY_SERVICE_URL: "http://regulatory-service:8080"
    DEMO_TRADING_HOURS_START: "09:00"
    DEMO_TRADING_HOURS_END: "16:30"
    DEMO_TRADE_CADENCE_SECONDS: "90"
  depends_on:
    - position-service
    - risk-orchestrator
    - regulatory-service
```

Helm chart: `deploy/helm/kinetix/charts/demo-orchestrator/` — copied from
the existing `risk-orchestrator` subchart and enabled only in the demo
values overlay.

`settings.gradle.kts` — add `include("demo-orchestrator")`.

### Verification (end-to-end on a fresh deploy)

1. `./deploy/redeploy.sh` with `DEMO_MODE=true` in compose env.
2. Open `https://kinetixrisk.ai`, log in as `trader1`.
3. **Within 5 minutes:** new trades appear in Trade Blotter, position grid
   quantities change, intraday P&L chart starts moving on a book.
4. **Within 30 minutes:** at least one limit breach in the Alerts tab.
5. **At 17:30 UTC:** EOD designation appears in EOD History tab; within 1
   minute, a "EOD complete" alert fires.
6. **At 17:35 UTC:** Reports tab shows a new draft regulatory submission
   for today's date; Regulatory tab backtest exception count reflects
   today's run.
7. **Next morning at 06:05 UTC:** new limits are visible (tightened around
   fresh SOD baseline).

### Notes for the Implementer

- **One type per file** (CLAUDE.md) — `DemoBookProfile` and
  `DemoBookProfiles` are separate files; same for any DTOs.
- **TDD** — write failing test first for each job, then minimal impl. Both
  land in the same commit so every commit builds green.
- **Trading hours check uses UTC**, no local tz conversion.
- **Counterparty assignment** — reuse the 6 seeded counterparties from
  `risk-orchestrator/.../seed/DevDataSeeder.kt` rather than introducing new
  ones.
- **Be conservative on trade notionals.** A demo book that gets blown up to
  50x VaR in two hours looks broken, not realistic. Start with
  profile-defined notional caps and adjust during demo rehearsal.
- **The 12 existing schedulers are not to be modified.** Demo-only logic
  stays in demo-orchestrator.
- **Hash-chain audit** — trades flow through audit-service normally; verify
  the hash chain isn't broken by burst trade insertion.

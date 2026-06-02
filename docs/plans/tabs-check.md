# Tabs Check — Live UI Bug-Fix Plan

Live audit of https://kinetixrisk.ai on 2026-05-28 — every top-level tab, every
sub-tab, the main action buttons, and the global keyboard shortcuts. Each tab
was loaded in isolation (fresh page) so cascade failures from sticky drawers do
not mask real bugs.

The headline finding is a **fatal app-wide crash** on Counterparty Risk: clicking
any counterparty row triggers `Cannot read properties of undefined (reading
'toFixed')`, the top-level `ErrorBoundary` swaps the whole UI for a "Something
went wrong / Reload page" card, and the user is locked out of every other tab
until they reload. That is P0.

Everything else either works as designed or is a smaller polish item. The
non-crashing tabs that look "empty" (Regulatory, Reports) are functional once
their action button is clicked — Calculate FRTB renders a full breakdown,
Generate produces a report — so they are not broken, just sparse landing
states.

Loop-ready for `/work-plan`. Each `- [ ]` is one independently committable
change with an `Acceptance:` command on the line immediately under it. Advance
end-to-end with `/loop /work-plan docs/plans/tabs-check.md`.

## What was exercised

- All 12 top tabs: Positions, Trades, P&L, Risk, EOD History, Scenarios,
  Counterparty Risk, Regulatory, Reports, Activity, Alerts, System.
- All sub-tabs: Trades · {Blotter, Place Order, Execution Cost, Reconciliation};
  Risk · {Dashboard, Run Compare, Market Data, Intraday}.
- Marquee action buttons: Run All Scenarios, Calculate FRTB, Generate Report,
  Refresh (where present), counterparty-row drill-down, EOD row selection.
- Header controls: theme toggle, Firm/Hierarchy picker, Multi-Asset, Frozen
  toggle, Ask Kinetix (Copilot launcher), Persona switcher, notification bell.
- Keyboard shortcuts: `Cmd/Ctrl+K` (Command Palette), `?` (Shortcuts Overlay),
  `Shift+H` (Hedge Recommendation).

Findings logged at `/tmp/audit3-findings.json` plus screenshots under
`/tmp/audit3-shots/`. Probe scripts are at `ui/_audit.mjs`, `ui/_audit2.mjs`,
`ui/_audit3.mjs` (uncommitted; safe to remove once the plan completes).

## What works

These tabs and flows rendered cleanly on a fresh page load against the
2026-05-28 deploy and were exercised without errors:

- **Positions** — grid renders, price WS connected, hierarchy selector seeded.
- **Trades · Blotter / Place Order / Execution Cost / Reconciliation** —
  all four sub-tabs render their primary surface; Place Order's submit gate
  stays disabled with an empty form (correct).
- **P&L** — VaR breach banners, intraday P&L chart, SOD baseline strip,
  P&L Waterfall, "Need a hedge?" CTA all render.
- **Risk · Dashboard / Run Compare / Market Data / Intraday** — all four
  sub-tabs render; sub-tab switching is clean.
- **EOD History** — VaR/ES trend chart + table render; clicking a row opens a
  detail drawer with EOD breakdown by instrument.
- **Scenarios** — table renders the stress scenarios; "Run All Scenarios"
  populates Base VaR, Stressed VaR, VaR Multiplier, P&L Impact for every row.
- **Regulatory** — "Calculate FRTB" produces a full FRTB breakdown (Total
  Capital, SbM, DRC, RRAO, per-risk-class delta/vega/curvature, totals).
  Download CSV enables after the calc.
- **Reports** — template dropdown loads, "Generate" produces a Report Output
  card and pushes a row to Report History.
- **Activity** — audit log table renders with filters (event type, subject,
  date range, user).
- **Alerts** — Notification Center renders Create Alert Rule form, rules
  list, and the Triggered alerts queue with severity chips.
- **System** — "All Systems Operational" banner + Service Health tiles for
  Gateway, Position Service, Prices, Risk Orchestrator, Notifications, Rates,
  Reference Data, Volatility, Correlations, regulatory-service, audit-service,
  plus Observability deep-links (Prometheus, Grafana, Tempo).
- **Counterparty Risk landing** — list of 6 counterparties with Net Exposure,
  Peak PFE, CVA, status pill.
- **Ask Kinetix** launcher opens the Command Palette in copilot mode (search,
  built-in queries: "Limit breaches today", "P&L vs yesterday", "VaR drivers
  this week", "Top positions by risk contribution", "Volatility dislocations").

## What is broken

| # | Severity | Symptom | Where |
|---|---|---|---|
| F1 | **P0 — fatal** | Clicking a counterparty row throws `Cannot read properties of undefined (reading 'toFixed')`; ErrorBoundary replaces entire app with "Something went wrong" card | `ui/src/components/CounterpartyRiskDashboard.tsx` PfeChart / DetailPanel |
| F2 | P1 | EOD drawer (opened by clicking a row) does not close on tab change or Escape; subsequent tab clicks are intercepted and silently fail | `ui/src/components/EodTimelineTab.tsx` |
| F3 | P1 | Scenarios tab intermittently throws `Cannot read properties of undefined (reading 'length')` when navigated to after EOD History → row click; not reproducible in isolation | likely `ScenariosTab.tsx` `comparedScenarios.length` / `historicalScenarioNames.length` reading from a not-yet-loaded hook |
| F4 | P2 | "Reverse Stress" and "Manage Scenarios" buttons stay disabled with no tooltip explaining why | `ui/src/components/ScenarioControlBar.tsx` |
| F5 | P2 | Reports "Generate" returns 0 rows for `Risk Summary` against `balanced-income`; AI Commentary skeleton never resolves | `ui/src/components/ReportsTab.tsx` + gateway report path |
| F6 | P3 | Cmd/Ctrl+K and `?` shortcuts reported as failing by the probe — likely false negative (the Ask Kinetix click opens the same palette successfully), but worth a targeted verification | `App.tsx` keydown listeners |

## Decisions applied

- **F1 is the only fatal — it leads.** Everything else is graceful degradation
  or polish. Fix F1 first so a user clicking a counterparty does not nuke the
  whole UI.
- **F2 is the implicit cause of F3.** EOD's drawer sticks around after a tab
  switch, leaving the next tab in a half-mounted state. Fixing F2 (drawer
  closes on tab change or Escape) may make F3 disappear; verify F3 separately
  with the same probe sequence after F2 lands and only do dedicated work on F3
  if it still repros.
- **F1 root cause hypothesis: a tenor row in `exposure.pfeProfile` is missing
  one of `pfe95` / `expectedExposure` / `pfe99`**, so `Math.max(...flatMap)` is
  NaN, `maxValue` falls back to 1 via `|| 1`, but a downstream consumer
  (likely tooltip or axis-tick formatter not visible in `PfeChart` itself)
  reads `undefined.toFixed`. The fix is two-pronged: (a) null-guard the chart
  inputs so a single bad row degrades gracefully (e.g. skip rows, show
  "Partial PFE profile" notice) instead of throwing, and (b) tighten the dto
  so the gateway either guarantees the fields or returns them as `null` and
  the UI is forced to handle the null case.
- **F5 may be a backend data gap, not a UI bug.** Generate succeeds (renders
  "Report generated successfully with 0 rows"), so the UI is wired correctly;
  the report-data backend is returning no rows for the chosen template+book.
  Diagnose end-to-end before changing UI code.
- **F6 is a probe ergonomics fix first.** Re-test `Cmd+K` and `?` with a
  detector that matches the actual palette/overlay text ("Ask the copilot",
  "or type to jump to a tab") and only file a UI bug if the shortcut genuinely
  doesn't fire.

## CI/CD approval

None required — all edits are scoped to `ui/src/`, no dependency changes,
no CI file edits.

## Plan

- [ ] F1.a — Add a failing Vitest covering `<PfeChart>` when a row has
  `expectedExposure: undefined` / `pfe95: undefined` (the exact shape that
  crashes today). Test asserts the chart either renders with the bad row
  skipped or shows a "Partial PFE profile" empty-state — not that it throws.
  Acceptance: `cd ui && npm run test -- --run src/components/CounterpartyRiskDashboard.test.tsx`

- [ ] F1.b — Make `<PfeChart>` resilient: in
  `ui/src/components/CounterpartyRiskDashboard.tsx`, filter `profile` to rows
  where both `pfe95` and `expectedExposure` are finite numbers before
  computing `maxValue` / `flatMap` / `toY`. Empty result after filtering
  renders the existing "No PFE profile available" empty state. Make the
  failing test from F1.a pass without weakening any other test.
  Acceptance: `cd ui && npm run test -- --run src/components/CounterpartyRiskDashboard.test.tsx`

- [ ] F1.c — Re-run the live probe `ui/_audit3.mjs` against
  https://kinetixrisk.ai (`AUDIT_BASE_URL` defaults to it). Confirm
  `/tmp/audit3-findings.json` no longer contains the
  `counterparty-risk` → `'toFixed'` error entries and that
  `/tmp/audit3-shots/cp-detail.png` shows the detail panel rendered (PFE
  chart present *or* PFE empty-state present, no "Something went wrong"
  card).
  Acceptance: `node ui/_audit3.mjs && jq -e 'all(.[]; .area != "counterparty-risk" or (.detail|test("toFixed")|not))' /tmp/audit3-findings.json`

- [ ] F1.d — Add a Playwright e2e at `ui/e2e/counterparty-detail.spec.ts`
  (or extend the existing `counterparty-risk.spec.ts`) that clicks a row,
  asserts `[data-testid="counterparty-detail-panel"]` is visible and the
  error-boundary "Something went wrong" element is not. Mock the
  `/api/v1/counterparty-risk/<id>` response to return a `pfeProfile` with one
  tenor missing `expectedExposure` so the regression is caught even if the
  backend later changes.
  Acceptance: `cd ui && npx playwright test counterparty-risk.spec.ts counterparty-detail.spec.ts`

- [ ] F2.a — Add a Playwright spec that selects an EOD row, switches to
  another tab, and asserts the EOD detail drawer is gone (or that the next
  tab's main region is interactable — its first button is clickable inside 1
  s). Currently this fails because the drawer hangs around.
  Acceptance: `cd ui && npx playwright test --grep "EOD drawer closes"`

- [ ] F2.b — In `ui/src/components/EodTimelineTab.tsx`, clear `selectedDate`
  (and any compare state) when the tab unmounts; close the drawer on
  `Escape` (add a keydown listener gated on drawer-open). Re-run the spec
  from F2.a — it must now pass — and the full
  `cd ui && npm run test -- --run src/components/EodTimelineTab.test.tsx`
  suite must still pass.
  Acceptance: `cd ui && npm run test -- --run src/components/EodTimelineTab.test.tsx && npx playwright test --grep "EOD drawer closes"`

- [ ] F3 — Re-run `node ui/_audit2.mjs` (the sequential, non-isolated probe)
  *after* F2 ships. If
  `jq '.[] | select(.area == "scenarios" and (.detail | test("length")))'
  /tmp/audit2-findings.json` is empty, F3 is resolved by F2 — close it with a
  note and move on. If it still repros, file `bd` ticket
  `ui-scenarios-length-crash` with the recorded stack trace and the
  reproducer steps (positions → trades → pnl → risk → eod-row → scenarios)
  for a focused follow-up; do not block this plan on it.
  Acceptance: `node ui/_audit2.mjs && (jq -e '[.[] | select(.area=="scenarios" and (.detail|test("length")))] | length == 0' /tmp/audit2-findings.json || bd show ui-scenarios-length-crash)`

- [ ] F4 — In `ui/src/components/ScenarioControlBar.tsx`, add a `title=` (or
  small inline hint) on "Reverse Stress" and "Manage Scenarios" when they
  are disabled, explaining the gate (e.g. "Run all scenarios first" / "Sign
  in as a model-governance role"). No behaviour change — purely surface the
  reason. Update the existing Vitest to assert the tooltip text.
  Acceptance: `cd ui && npm run test -- --run src/components/ScenarioControlBar.test.tsx`

- [ ] F5.a — Probe the report path end-to-end from the command line:
  `curl -sk -X POST https://api.kinetixrisk.ai/api/v1/reports/generate -H 'content-type: application/json' -d '{"templateId":"tpl-risk-summary","bookId":"balanced-income"}' | jq`.
  If the gateway returns `rows: []`, the bug is backend data, not UI: file
  `bd` ticket `reports-zero-rows-risk-summary` capturing the request /
  response and close F5 here without UI changes. If the gateway returns a
  non-empty body, escalate to a UI parsing bug and continue with F5.b.
  Acceptance: `curl -sk -X POST https://api.kinetixrisk.ai/api/v1/reports/generate -H 'content-type: application/json' -d '{"templateId":"tpl-risk-summary","bookId":"balanced-income"}' | jq -e '.rows | length >= 0'`

- [ ] F5.b — (Only if F5.a returned a non-empty body.) Trace where the rows
  are dropped — likely a property-name mismatch between the gateway response
  and `ReportsTab.tsx`'s reducer. Add a Vitest covering the fixture from F5.a
  and a fix. Skip this step if F5.a closed the bug as a backend ticket.
  Acceptance: `cd ui && npm run test -- --run src/components/ReportsTab.test.tsx`

- [ ] F5.c — Investigate the AI Commentary skeleton that never resolves on
  the Reports tab. Open the network panel via Playwright and confirm the
  `/api/v1/copilot/...` call for the commentary either errors or never
  fires; route the finding to `bd` (`reports-ai-commentary-stuck`) with the
  evidence and only patch the UI if the call returns a valid body that the
  component fails to render.
  Acceptance: `cd ui && npx playwright test --grep "Reports AI commentary"`

- [ ] F6 — Add a targeted Playwright spec at
  `ui/e2e/global-shortcuts.spec.ts` that loads a fresh page and asserts
  `Meta+K` (and as a fallback `Control+K`) opens the Command Palette by
  detecting `text=/Ask the copilot/`; `?` opens the Shortcuts Overlay by
  detecting `text=/Keyboard shortcuts/`. If both pass, close F6 — the
  earlier probe gave a false negative. If either fails, fix the
  corresponding `App.tsx` keydown listener (Cmd+K guard or `?` guard) and
  re-run.
  Acceptance: `cd ui && npx playwright test global-shortcuts.spec.ts`

## Out of scope

- Backend report-data quality and AI-commentary backend availability (covered
  by a separate `bd` ticket created in F5.a / F5.c).
- Scenarios "Manage Scenarios" feature itself — only the disabled-tooltip
  affordance is in scope here (F4). Wiring the panel for non-governance
  personas is its own product story.
- Header design polish (theme toggle ergonomics, persona switcher copy,
  etc.) — none of those are broken; they are not in this plan.
- Removing the `_audit.mjs`, `_audit2.mjs`, `_audit3.mjs` probe scripts
  from `ui/`. Leave them in the worktree until F1.c, F3, and F6 are signed
  off; delete in the same commit that closes the plan.

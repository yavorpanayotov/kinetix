# UI Overhaul Plan

**Date:** 17 May 2026
**Authors:** Marcus (senior trader, 25+ yrs sell-side & buy-side) and the senior UX designer
**Scope:** End-to-end review of the Kinetix risk platform UI in `ui/src/`. Eleven top-level tabs, ~150 components, dark-mode aware, Vite + React + TS + Tailwind.

This plan is the consolidated output of two independent reviews — one from a trader's workflow perspective, one from a UX designer's perspective. Where we agreed, the item is bumped in priority. Where we disagreed, both views are recorded so the implementer can decide.

---

## TL;DR

The UI has the right bones. Coverage is genuinely institutional: 11 tabs, hierarchy selection, regime/scenario/tape indicators, what-if, hedge engine, stress, cross-book VaR, EOD timeline, regulatory dashboard, audit/reports. Most of the *missing* features are integration and consolidation work, not net-new screens.

The biggest problems are:

1. **No always-on P&L / VaR ticker.** A trader running this app today has to switch tabs to see the one number that matters most. Unforgivable on a risk system.
2. **Information overload on the Risk tab dashboard.** 14 panels scrolling in a single column with no grouping or progressive disclosure. A risk manager opens it and has no idea where to look first.
3. **Banner soup.** Up to four stacked banners can push content below the fold; one of them (`reconnecting`) re-fires `role="alert"` every second, which is a screen-reader regression.
4. **No triage workflow on Alerts.** The backend has ACKNOWLEDGED / ESCALATED / RESOLVED states but the UI exposes no actions to produce them — Alerts is read-only plus rule CRUD, not a real ops queue.
5. **No global search / command palette.** With 11 tabs, multi-level hierarchy, and named entities (instruments, counterparties, books, scenarios), navigation is purely manual.
6. **Design-system drift.** Three section-heading sizes, two sub-tab implementations, one component (`CounterpartyRiskDashboard`) that hardcodes the dark palette and will look broken in light mode.
7. **Accessibility regressions hiding in plain sight.** No focus trap on the What-If panel, no ARIA role on DataQualityIndicator dropdown, color-only P&L for the colorblind, `role="alert"` re-firing on every render.

Everything below is grouped by workstream, ranked roughly by impact-vs-effort. File pointers are provided so each item is actionable without re-reading the whole codebase.

---

## Workstream 1 — Always-On Risk State (HIGH IMPACT)

A trader's brain is a P&L and Greek tracker. If the system doesn't display those numbers continuously, the trader will keep one eye on Bloomberg and one on Kinetix — meaning the system has lost the desk.

### 1.1 Persistent P&L + VaR ticker strip
**Where:** Below the tab bar in `ui/src/App.tsx`, always visible (every tab).
**What:** A single dense strip: NAV · Unrealised P&L (with %) · Intraday P&L · 1d 95% VaR vs Limit (with utilisation %) · Net Delta · Net Vega · Last calc time. Color-coded breach (red if VaR > 80% of limit). Updates live from the WebSocket stream.
**Why it matters:** This is the *only* thing a trader needs to see to know whether they're in trouble. Today, this data is split across Positions (BookSummaryCard), Risk (VaRDashboard), P&L tab, and the header. Consolidate it into one strip. A `PnlTickerStrip.tsx` exists already (probably scoped to P&L tab only) — promote and extend it.
**Files:** `ui/src/App.tsx` (add slot), new `ui/src/components/RiskTickerStrip.tsx` or extend `PnlTickerStrip.tsx`, hook on top of existing `useBookSelector` / `usePnlAttribution` / `useVaR` / `useVarLimit` / `useGreeks`.

### 1.2 Active scenario / regime annotation on affected panels
**Where:** `ui/src/components/VaRDashboard.tsx`, `RiskTab.tsx` panels.
**What:** When `useActiveScenario().scenario != null`, display a small annotation on every risk number that came out of a scenario run — not just the header pill. Same for `useMarketRegime().regime` when VaR is regime-adjusted.
**Why:** Today the header pills tell you *that* a scenario is active, but the numbers on screen don't say *whether* they reflect it. Trust failure in a crisis: "is this P&L pre- or post-scenario?"
**Effort:** small — pass scenario/regime context down through `RiskTab` and `VaRDashboard` (already partly threaded for scenarios via `activeScenario` hook in App.tsx).

### 1.3 Limits-and-utilisation header
**Where:** New panel atop the Risk tab Dashboard sub-tab (`ui/src/components/RiskTab.tsx`).
**What:** A persistent "what is breached right now?" band — limit breaches, near-breaches (>80%), recent alerts (last 30 min). One glance answers: where am I exposed.
**Why:** `LimitsPanel` is in the scroll wall. Limits should be the first thing on the Risk tab, not the eleventh.

---

## Workstream 2 — Information Architecture & Layout (HIGH IMPACT)

### 2.1 Group the 11 top-level tabs into 3 clusters
**Where:** `ui/src/App.tsx` — `TABS` constant.
**What:** Visually group as: `[Trading: Positions · Trades · P&L]` `[Risk: Risk · EOD · Scenarios · Counterparty]` `[Ops: Regulatory · Reports · Alerts · System]`. Either as separated tab groups with thin dividers, or as a 2-level nav (primary cluster selector → secondary tabs).
**Why:** Eleven flat tabs is a navigation anti-pattern. The current order interrupts the natural Risk → Scenarios → Counterparty flow with EOD History sitting between Risk and Scenarios. At medium breakpoints, labels collapse to icon-only and no human can scan 11 unlabelled icons.
**Trader view:** Trader doesn't care about cluster labels — cares that the four screens they use 95% of the time (Positions, Risk, Scenarios, Alerts) are reachable in <2s. Clustering helps because muscle memory survives reorder.
**UX view:** Promote EOD History into the Risk tab as a sub-tab (Risk already has Dashboard / Intraday / Run Compare / Market Data — EOD is a natural fifth).

### 2.2 Risk tab Dashboard: group panels into collapsible sections
**Where:** `ui/src/components/RiskTab.tsx` lines 227–369.
**What:** Replace the 14-panel `mt-4` scroll with four `<SectionBlock>` containers:
- **Market Risk:** VaRDashboard, IntradayVaRChart (currently in a sibling sub-tab — consider promoting summary onto Dashboard), CorrelationHeatmap
- **Position & Factor Risk:** PositionRiskTable, KrdPanel, FactorDecompositionPanel, FactorAttributionHistoryChart
- **P&L, Stress & Liquidity:** PnlSummaryCard, StressSummaryCard, LiquidityRiskPanel, MarginPanel
- **Limits & Jobs:** LimitsPanel, JobHistory
Each section collapsible with state persisted to workspace prefs (`useWorkspace`).
**Why:** A risk manager opening this tab today is met with a wall of 14 panels and no hierarchy. Pinning matters but is harder than just grouping.
**Stretch:** Allow users to pin 2–3 panels to a "favourites" section at the top.

### 2.3 Saved views beyond `defaultTab + defaultBook`
**Where:** `useWorkspace` hook, `App.tsx` workspace save button.
**What:** Promote the single workspace to *named views*: "Equities morning check," "Credit stress monitor," etc. Each view captures: tab, sub-tab, hierarchy selection, column visibility, time range, panel collapse state.
**Why:** Risk managers monitor 3–5 different "modes" daily. One default is not enough.
**Effort:** medium — backend already has workspace persistence; extend to multi-row.

### 2.4 Cross-tab linking
**Where:** Multiple components.
**What:**
- Alerts list → click to jump to the affected book + tab (e.g. VaR breach → Risk tab on that book)
- CounterpartyRiskDashboard counterparty row → trades blotter filtered to that counterparty
- Reports output row → Risk tab on the reported book at the reported valuation date
- ScenarioComparisonTable scenario row → ScenarioDetailPanel
**Why:** The navigation graph is a one-way tree. Real workflows are a mesh.

---

## Workstream 3 — Alerts & Triage Workflow (HIGH IMPACT)

The Alerts tab today is **read-only** — you can see alerts and create rules, but you cannot acknowledge, escalate, comment, or resolve. The backend supports these states; the UI doesn't expose them.

### 3.1 Per-alert actions
**Where:** `ui/src/components/NotificationCenter.tsx` and `AlertDrillDownPanel.tsx`.
**What:** Each alert row gets: Acknowledge (with optional note), Escalate (with reason + assignee), Resolve (with resolution text), Snooze. Status badge cycles through the lifecycle states the backend already supports.
**Why:** A CRITICAL alert with no acknowledge button is theatre. A real risk ops desk needs accountability — who saw this, when, what did they do?

### 3.2 Alerts as a queue, not a list
**Where:** `NotificationCenter.tsx`.
**What:** Default view = unresolved + unacknowledged, sorted CRITICAL > WARNING > INFO then by age. Show counts per status as filter chips. Auto-collapse RESOLVED alerts older than 24h.
**Why:** A flat time-ordered list buries the things requiring action.

### 3.3 Replace `EQUALS` operator with `WITHIN tolerance`
**Where:** `NotificationCenter.tsx` create-rule form.
**What:** "Equal to" for floating-point risk values never fires. Replace with `WITHIN ±tolerance` (or remove `EQUALS` entirely for numeric metric types).
**Why:** Trust failure — a rule that "looks valid" but mathematically cannot trigger.

### 3.4 Breach banner on affected screens
**Where:** Existing `RiskAlertBanner` is in RiskTab — generalize.
**What:** When VaR > 80% of limit, show a sticky banner on Positions and P&L too, not just Risk. Same for any active CRITICAL alert.
**Why:** The breach should follow me; I should not have to navigate to find it.

---

## Workstream 4 — Banner & State Communication (MEDIUM IMPACT, HIGH POLISH)

### 4.1 Consolidate banners into a single status bar
**Where:** `ui/src/App.tsx` lines 305–359 — DemoWelcomeStrip + exhausted banner + reconnecting banner + maintenance banner.
**What:** Single horizontal bar that swaps content/severity rather than stacking. Demo strip is a separate, dismissible row that disappears after first session.
**Why:** Four stacked banners push content below the fold on 1080p. Multiple simultaneous `role="alert"` regions create overlapping screen-reader announcements.

### 4.2 Fix `role="alert"` re-firing every second
**Where:** `ui/src/App.tsx` lines 344–348 — `reconnecting-banner`.
**What:** Move the elapsed-time counter (`(47s)`) into a sibling `<span aria-live="off">` so the primary status announces once, the timer updates silently.
**Why:** Screen reader announces "Reconnecting... (1s) Reconnecting... (2s)..." once a second — unusable.

### 4.3 Standardise loading / empty / error states
**Where:** `ReportsTab.tsx` ("Loading report templates..." plain text), and audit every component.
**What:** All loading uses `<Spinner>` + label, all errors use the consistent error card pattern, all empty uses `<EmptyState>`. Audit & sweep.
**Why:** ReportsTab is invisible on white background. Pattern fragmentation = bug surface.

---

## Workstream 5 — Design System (MEDIUM IMPACT)

### 5.1 Extract `<SectionHeading>` with one canonical size
**Where:** New `ui/src/components/ui/SectionHeading.tsx`.
**What:** One component replaces three different `text-sm/text-base/text-lg font-semibold` patterns across BookSummaryCard, CounterpartyRiskDashboard, ReportsTab, SystemDashboard.
**Why:** Same semantic role rendered three different ways is a design system telling you it doesn't exist yet.

### 5.2 Extract `<SubTabBar>`
**Where:** Replace inline sub-tab logic in `App.tsx` Trades tab and `RiskTab.tsx`.
**What:** One shared component. Fix the Trades sub-tab missing `dark:text-primary-400` while you're there.
**Why:** Two implementations + one bug = drift.

### 5.3 Fix `CounterpartyRiskDashboard` hardcoded dark palette
**Where:** `ui/src/components/CounterpartyRiskDashboard.tsx`.
**What:** Replace bare `bg-slate-800` / `text-slate-200` with the `dark:` prefix convention used everywhere else.
**Why:** Looks broken in light mode today.

### 5.4 Reserve color for semantic, not decorative
**Where:** CounterpartyRiskDashboard (amber/indigo as decorative column colors) and similar.
**What:** Use color when it carries meaning (severity, sign, status). For decorative grouping, use weight / borders / spacing.
**Why:** Color exhaustion — when everything is colored, nothing reads as a warning.

---

## Workstream 6 — Accessibility (MEDIUM IMPACT, LOW EFFORT)

### 6.1 Focus trap in WhatIfPanel
**Where:** `ui/src/components/WhatIfPanel.tsx`.
**What:** Either hand-roll a trap with `useEffect` keydown listener cycling through `panel.querySelectorAll('button,input,select,textarea,[tabindex]')`, or accept a tiny utility. The panel has `role="dialog" aria-modal="true"` but no actual modal behaviour for keyboard users — tab escapes to background content.
**Why:** WCAG 2.1 SC 2.1.2 keyboard trap requirement; this is the primary workflow panel.

### 6.2 Focus management on DataQualityIndicator dropdown
**Where:** `ui/src/components/DataQualityIndicator.tsx`.
**What:** Add `role="dialog"` + `aria-label`. On open, move focus into the dropdown. Escape already closes — keep that.
**Why:** Keyboard users can open the dropdown but cannot reach its content without tabbing through intervening header elements.

### 6.3 Explicit `+` sign on positive P&L
**Where:** `ui/src/utils/format.ts` — `formatMoney` / new `formatSignedMoney`.
**What:** For P&L values (not NAV or market value), prefix `+` when value > 0. Apply where `pnlColorClass` is used.
**Why:** Color-only differentiation is invisible to ~8% of male users. The negative sign is already there; adding `+` symmetrically gives a secondary cue without changing visual language for sighted users.

### 6.4 Form error descriptions in NotificationCenter
**Where:** `ui/src/components/NotificationCenter.tsx` create-rule form.
**What:** Add `aria-invalid`, `aria-describedby` linking to inline error messages, not just HTML5 `required`.
**Why:** Screen-reader user gets no feedback on which field failed.

---

## Workstream 7 — Power-User Features (LOW EFFORT, HIGH "WOW")

### 7.1 `Cmd+K` command palette
**Where:** New `ui/src/components/CommandPalette.tsx`, global keydown in `App.tsx`.
**What:** Modal with text input + fuzzy filter over: tab names, sub-tab names, currently loaded book IDs, currently loaded instrument IDs, counterparties, scenarios. Enter navigates. Recent items at top.
**Why:** With 11 tabs × 4 sub-tabs × N books × M instruments, manual navigation is the bottleneck. A day's work for material productivity gain.

### 7.2 Keyboard shortcuts overlay (`?` key)
**What:** Press `?` to show the cheat sheet — Shift+H (Suggest Hedge) already exists and is undiscoverable; arrow keys on tab bar; Cmd+K once it lands; Cmd+/ to focus search.
**Why:** Power users live on keyboards. We have shortcuts; we just don't expose them.

### 7.3 Position-level annotations
**Where:** `PositionGrid.tsx`, new endpoint.
**What:** Click a position → add a note ("hedging Mon," "under credit review"). Notes show as a tiny icon in the row; hover/click to read.
**Why:** Every risk platform gets this request within 6 months of going live. Builds desk memory.

---

## Workstream 8 — Trader-Specific Workflow Gaps (TRADER VIEW)

These are pure Marcus observations the UX review wouldn't catch.

### 8.1 Time-to-decision matters more than information density
**Observation:** The current Risk tab Dashboard, after a 10pt vol spike, requires me to scroll past 7 panels to find vega. That is too long. Vega, Delta, Net Liquidity, VaR-vs-Limit should be readable in the first viewport without scrolling.
**Fix:** Already covered by 1.1 (ticker strip) and 2.2 (grouping). Tagged here because it is the *reason* for those changes.

### 8.2 Hedge engine surfacing
**Observation:** `HedgeRecommendationPanel` is hidden behind `Shift+H` or a button labelled "Suggest Hedge" sitting on one tab. Hedge engines are the most-requested feature on any risk platform.
**Fix:** When VaR utilisation > 80% OR when a CRITICAL alert is triggered, surface a "Need a hedge?" CTA on the relevant screens. Always reachable from the ticker strip's VaR indicator.

### 8.3 Stale data must be visually unambiguous
**Observation:** `HedgeSuggestionDto.dataQuality === 'STALE'` shows a small yellow STALE pill. That's a 10px badge for what could be a five-figure trading mistake.
**Fix:** Stale on a risk metric should wash the whole panel in a desaturated overlay or yellow tint, with an explicit "computed at X, source as of Y" line. If liquidity data is 2 hours old, I want to feel that, not squint for it.

### 8.4 Trade markers on P&L and VaR timelines
**Observation:** `IntradayVaRChart` already supports trade annotations (`tradeAnnotations` prop). Good. Extend the same idea to the intraday P&L chart and the VaR trend chart.
**Why:** "Why did P&L move at 2:47pm?" → "Because you traded X." That answer should be on the chart.

### 8.5 Position grid: show me *risk* before *quantity*
**Observation:** `PositionGrid` columns are: Instrument · Name · Type · AssetClass · Quantity · AvgCost · MarketPrice · MarketValue · UnrealisedPnL · RealisedPnL. Risk metrics are in a *second* grouped header to the right.
**Fix:** Make the *default* view show: Instrument · MV · UPnL · Δ · Γ · Vega · VaR%. Quantity / avg cost / market price live under a "Details" toggle. A risk-first system shouldn't be quantity-first by default.

### 8.6 The "what just changed?" view
**Observation:** Run-comparison exists (great). But the common question is: "what is different right now vs. an hour ago?" — i.e. ad-hoc time-shifted compare, not run-vs-run.
**Fix:** "Compare with snapshot" button on Risk tab → pick a time (-15m, -1h, -EOD yesterday) → see diff overlay on the same panels.

---

## Workstream 9 — Responsive Strategy Decision (LOW EFFORT)

Half-responsive is worse than intentionally non-responsive. Decide and commit.

**Recommendation:** Declare desktop-only (1280px minimum). Add `min-width: 1280px` on `<body>` or a small-viewport warning page ("Kinetix is desktop-only — please use a screen ≥1280px wide"). Strip the partial `md:` / `lg:` / `hidden sm:` accommodations and the maintenance burden they imply.

**Counter-view:** If genuine mobile use exists (a trader checking VaR on a phone), build a *separate* read-only mobile shell rather than half-responsive desktop. But that is a separate plan, not an item here.

---

## Top 10 Prioritised Changes (Impact × Effort)

| # | Change | Workstream | Effort | Impact |
|---|---|---|---|---|
| 1 | Persistent P&L + VaR ticker strip | 1.1 | M | XL |
| 2 | Group Risk tab Dashboard into 4 collapsible sections | 2.2 | S | L |
| 3 | Alerts triage (Ack / Escalate / Resolve actions) | 3.1, 3.2 | M | L |
| 4 | Cmd+K command palette | 7.1 | M | L |
| 5 | Risk-first PositionGrid default columns | 8.5 | S | L |
| 6 | Cross-tab linking (Alerts → Risk, Counterparty → Trades) | 2.4 | M | L |
| 7 | Banner consolidation + fix `role="alert"` re-fire | 4.1, 4.2 | S | M |
| 8 | Focus trap in WhatIfPanel | 6.1 | S | M |
| 9 | `<SectionHeading>` + `<SubTabBar>` extraction | 5.1, 5.2 | S | M |
| 10 | Saved views (multi-workspace) | 2.3 | M | L |

S = ~1 day. M = ~3 days. L = ~1 week.

---

## Quick Wins (do these first, all <1 day each)

- [x] Fix `role="alert"` re-fire on reconnecting banner (`App.tsx:344–348`) — split timer into `aria-live="off"` sibling.
- [x] Light-mode CounterpartyRiskDashboard — replace bare `bg-slate-800` with `dark:` prefix pattern.
- [x] Trades sub-tab dark-mode active color — add `dark:text-primary-400`.
- [x] `+` prefix on positive P&L in `formatMoney` / `formatSignedMoney`.
- [x] Remove or rename `EQUALS` operator in alert rules — "Equal to" for floats never fires.
- [x] Replace ReportsTab plain-text loading state with `<Spinner>`.
- [x] Add `role="dialog"` + focus management to DataQualityIndicator dropdown.
- [x] Document the existing `Shift+H` shortcut somewhere visible (tooltip on Suggest Hedge button already exists — extend to a `?` overlay).

---

## Workstream Implementation Tracker

Quick Wins are done. The items below are the larger Workstream changes broken into checkboxes for autonomous /work-plan execution. Order follows the "How to Use This Plan" guidance (Workstream 1 first → 2/3 → 5 → 7) with the Top 10 priority breaking ties within bands. Each checkbox references the scope section by number; the subagent reads that section for the full brief.

If an item's scope has been partially addressed already (some sub-fixes shipped as Quick Wins), the subagent should re-read the scope, check the current code, and do only what remains.

### Phase 1 — Always-On Risk State (Workstream 1 first per plan recommendation)

- [x] 1.1 Persistent P&L + VaR ticker strip (scope §1.1) — promote `PnlTickerStrip` to a global slot below the tab bar; XL impact, ~3d effort.
- [x] 1.2 Active scenario / regime annotation on affected risk panels (scope §1.2) — pass `activeScenario` / `marketRegime` context through `RiskTab` and `VaRDashboard`, annotate per-number not just header.
- [x] 1.3 Limits-and-utilisation header atop Risk Dashboard (scope §1.3) — promote `LimitsPanel` summary to a sticky band above the Dashboard scroll.

### Phase 2 — IA, Alerts, and Polish (Workstreams 2, 3, 4 — sequential here for the loop)

- [x] 2.2 Group Risk tab Dashboard into 4 collapsible `<SectionBlock>` sections (scope §2.2) — Market Risk / Position & Factor / P&L Stress Liquidity / Limits & Jobs; persist collapse state to workspace prefs.
- [x] 3.1a Per-alert action: **Acknowledge** wired in `NotificationCenter` and `AlertDrillDownPanel` with optimistic update + lifecycle status badge. (Escalate / Resolve / Snooze deferred — see "Blocked items needing decisions" at the bottom.)
- [x] 3.2 Alerts as a queue, not a list (scope §3.2) — default sort CRITICAL > WARNING > INFO then by age; filter chips with counts; auto-collapse RESOLVED >24h old.
- [x] 3.4 Breach banner generalised beyond RiskTab (scope §3.4) — show `RiskAlertBanner` on Positions and P&L when VaR > 80% of limit or any CRITICAL alert active.
- [x] 4.1 Consolidate banners into a single status bar (scope §4.1) — single horizontal bar that swaps content/severity instead of stacking; demo strip stays as its own dismissible row.
- [x] 4.3 Audit & sweep: standardise loading / empty / error states across every component (scope §4.3) — Quick Win covered `ReportsTab` loading only; full audit remains for empty + error states everywhere else.
- [x] 8.5 Risk-first PositionGrid default columns (scope §8.5) — default view: Instrument · MV · UPnL · Δ · Γ · Vega · VaR%; quantity / avg cost / market price behind a "Details" toggle.
- [x] 2.4 Cross-tab linking (scope §2.4) — Alerts row → affected book on Risk tab; Counterparty row → filtered Trades; Reports output → Risk tab at that valuation date; ScenarioComparisonTable row → ScenarioDetailPanel.
- [x] 2.1 Group 11 top-level tabs into 3 clusters (scope §2.1) — visually group `[Trading]` / `[Risk]` / `[Ops]` in the `TABS` constant.
- [x] 2.3 Saved views: named multi-workspace (scope §2.3) — promote single workspace to named views capturing tab + sub-tab + hierarchy + columns + time range + collapse state. Backend persistence exists per plan; extend to multi-row. (Note: plan was wrong about backend — persistence is localStorage-only; v1→v2 envelope migration done in client.)

### Phase 3 — Design System & Accessibility (sweeping between feature PRs)

- [x] 5.1 Extract `<SectionHeading>` with one canonical size (scope §5.1) — replace three different `text-sm/base/lg font-semibold` patterns across BookSummaryCard, CounterpartyRiskDashboard, ReportsTab, SystemDashboard.
- [x] 5.2 Extract `<SubTabBar>` and consolidate the two inline implementations (scope §5.2) — App.tsx Trades tab + RiskTab.tsx. Quick Win patched the Trades dark-mode class; full extraction remains.
- [x] 5.4 Reserve colour for semantic, not decorative (scope §5.4) — drop CounterpartyRiskDashboard amber / indigo column tints; replace with weight / borders / spacing.
- [x] 6.1 Focus trap in WhatIfPanel (scope §6.1) — hand-roll a keydown-cycling trap; the panel claims `role="dialog" aria-modal="true"` but doesn't behave like one for keyboard users.
- [x] 6.4 Form error descriptions in NotificationCenter create-rule form (scope §6.4) — `aria-invalid` + `aria-describedby` linked to inline error messages.

### Phase 4 — Power-User & Trader Polish (last per plan)

- [x] 7.1 Cmd+K command palette (scope §7.1) — new `CommandPalette.tsx`, global keydown in `App.tsx`, fuzzy filter over tabs / sub-tabs / books / instruments / counterparties / scenarios.
- [x] 7.3 Position-level annotations — moved to "Blocked items needing decisions" (needs new backend endpoint per §7.3; see below).
- [x] 8.2 Hedge engine surfacing on breach (scope §8.2) — "Need a hedge?" CTA when VaR utilisation > 80% or any CRITICAL alert is active; reachable from the ticker strip's VaR indicator.
- [x] 8.3 Stale data visual unambiguity (scope §8.3) — STALE pill replaced with desaturated overlay / yellow tint over the whole panel + explicit "computed at X, source as of Y" line.
- [x] 8.4 Trade markers on intraday P&L and VaR trend charts (scope §8.4) — extend `tradeAnnotations` pattern from `IntradayVaRChart` to `IntradayPnlChart` and the VaR trend chart.
- [x] 8.6 "Compare with snapshot" — ad-hoc time-shifted compare (scope §8.6) — button on Risk tab → pick -15m / -1h / -EOD yesterday → diff overlay on the same panels.

### Phase 5 — Responsive Strategy

- [x] 9. Adopt desktop-only floor (scope §9) — `min-width: 1280px` on `<body>` or a small-viewport warning page; strip partial `md:` / `lg:` / `hidden sm:` accommodations and the maintenance burden they imply.

### Phase 6 — Unparked: backend work for Workstream 3 + 7 (user-approved)

User-approved on 2026-05-18. The work-plan loop is authorised to add the backend contracts listed here, since the user has explicitly OK'd it. Subagent prompts for these items must include that authorisation note so the guardrail doesn't trip.

- [x] 3.1b.1 Backend — add `POST /api/v1/notifications/alerts/{id}/escalate` and `POST /api/v1/notifications/alerts/{id}/resolve` HTTP routes in `notification-service`. Repository methods (`escalate`, `resolve`) already exist on `ExposedAlertEventRepository`; just need route handlers + gateway proxy. Body for escalate: `{ reason: string, assignee?: string }`; body for resolve: `{ resolutionText: string }`. Acceptance tests in `notification-service` + gateway acceptance test. (Note: commit `ab8a1706` also captured ~8 ai-insights-service files from a parallel session's working tree — harmless but messy; flag for cleanup. Wider gateway acceptance suite has pre-existing `instrumentType` migration failures unrelated to this work.)
- [x] 3.1b.2 UI — wire Escalate + Resolve actions in `NotificationCenter` and `AlertDrillDownPanel`. Pattern mirrors the Acknowledge UI shipped in §3.1a (optimistic update, inline form, lifecycle status badge transitions).
- [x] 3.1b.3 Backend — add Snooze support to alert events. Schema: add `snoozed_until: timestamp NULL` column to the alert events table (Flyway migration); repository methods `snooze(id, until)` and a "skip if snoozed" guard in the evaluator. Gateway proxy route. Acceptance + integration test that a snoozed rule does not re-fire until the timestamp passes.
- [ ] 3.1b.4 UI — wire Snooze action in `NotificationCenter`. Presets: 1h / 4h / 24h / until tomorrow.
- [ ] 7.3.1 Backend — Flyway migration in `position-service`: `position_notes` table with `(id uuid pk, book_id text, instrument_id text, note text, author text, created_at timestamp default now())`. Add `PositionNotesRepository` (CRUD) + service + DTO in `common`. Acceptance tests.
- [ ] 7.3.2 Backend — Kotlin routes in `position-service`: `GET /api/v1/positions/{bookId}/notes` (list), `POST /api/v1/positions/{bookId}/notes` (create with `{instrumentId, note}`), `DELETE /api/v1/positions/notes/{id}` (delete). Gateway proxy. Acceptance tests.
- [ ] 7.3.3 UI — API client `ui/src/api/positionNotes.ts` + hook `usePositionNotes(bookId)`. Per-row note icon in `PositionGrid` (clickable to open popover); popover shows existing notes for that instrument + a "Add note" form.

### Phase 7 — UI follow-ups noticed during the main loop

- [ ] FU1 Extend `ErrorCard` API with optional `retryTestId` and `retryLabel`; convert `VaRDashboard`, `EodTimelineTab`, and `HedgeRecommendationPanel`'s ad-hoc error states to use it. Preserves their existing Playwright test IDs.
- [ ] FU2 Saved views: when the active view changes at runtime, push the view's hierarchy selection, time range, and column-visibility prefs down into the relevant hooks. Currently only `defaultTab` re-applies. Source files: `useWorkspace`, `App.tsx`, `useHierarchy` (or equivalent).
- [ ] FU3 Apply `formatSignedMoney`'s `+`-prefix treatment to the `formatNum`-based P&L call sites that pair with `pnlColorClass`: `PnlTickerStrip`, `PnlSummaryCard`, `PnlWaterfallChart`, `PnlAttributionTable`, `IntradayPnlChart`, `StrategyGroupRow`. Add a `formatSignedNum` helper (or extend `formatNum` with a `signed` option) and wire it up.
- [ ] FU4 Strip the partial `md:` / `lg:` / `hidden sm:` Tailwind accommodations across the codebase. The §9 small-viewport warning makes them dead code; remove them so future contributors aren't tempted to extend half-responsive patterns.
- [ ] FU5 Investigate and fix the five pre-existing Playwright failures on `main`: `counterparty-risk`, `position-data-rendering` (P&L `+` formatting assertion), `risk-error-states`, `trade-blotter` (CSV export), `ui-resilience` (timing). Each may be a one-line test fix or a real regression — find out per spec.

### Blocked items needing decisions

(none currently — Phase 6/7 above unparked the previous blockers)

---

## What This Plan Deliberately Does Not Cover

- **New asset class support** — out of scope, this is UI.
- **Backend changes** — only flagged where the UI cannot deliver without them (saved-views storage already exists; alert action endpoints already exist; intraday P&L already streamable).
- **Mobile app** — see Workstream 9.
- **Demo-mode polish** — separate plan; demo strip is already tracked elsewhere.

---

## How to Use This Plan

This is *a plan*, not a queue. Suggested approach:

1. **Walk through Workstream 1 first.** Always-on risk state is the single change that most transforms the user's relationship with the app.
2. **Pick 4–5 quick wins** from the list above and ship them in a single PR while Workstream 1 is being scoped — fast visible improvement.
3. **Workstreams 2 and 3 in parallel** once Workstream 1 lands — IA grouping is independent of alerts triage.
4. **Workstream 5 (design system) as a sweeping refactor between feature PRs** — touch one component at a time, leave each one healthier.
5. **Workstream 7 (power-user)** comes last but produces the most "wow" demo moments; save it for a milestone.

— Marcus & the UX team

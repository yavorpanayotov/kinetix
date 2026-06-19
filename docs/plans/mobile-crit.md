# Mobile surface — Crit Loop

A self-converging **evaluator–optimizer** loop that polishes the phone surface
(`ui/src/components/mobile/`, rendered below 1280px) until two expert critics —
`trader` and `ux-designer` — stop finding things. Drive it with **one command**:

```
/loop /crit-loop docs/plans/mobile-crit.md
```

`/loop` (no interval) re-arms each tick via `ScheduleWakeup`; `/crit-loop` does
one iteration and decides for itself whether to FIX or CRITIQUE based on the
ledger below. The loop ends **itself** when two consecutive crit rounds add no
new findings (`Dry rounds` reaches 2).

> Scope is the **already-built** read-only phone surface from
> `docs/plans/mobile-phone-access.md` (now complete). This loop does **not** add
> features, write actions, or new views — it polishes what exists. Everything in
> that plan's "Out of scope" section stays out of scope here.

## Loop state

- **Dry rounds:** 0  <!-- consecutive crit rounds that added zero findings; loop stops at 2 -->
- **Last crit round:** 2026-06-19 — round 1, +20 findings (7 high, 7 med, 6 low)

## The six steps (one iteration = one box, or one crit refill)

1. **OBSERVE** — `cd ui && npx playwright test e2e/screenshots/mobile-crit.capture.spec.ts --project=chromium`
   → PNG matrix in `docs/screenshots/mobile-crit/` (4 views × light/dark @ 390px).
2. **CRITIQUE** — fan out `trader` + `ux-designer` (parallel), each reading the
   PNGs **and** the relevant component code against its rubric below.
3. **TRIAGE** — merge, dedup against `done`/`wont-fix` rows in the ledger, drop
   conflicts to the human (see Conflict rule). Append survivors as `- [ ]` boxes.
4. **FIX** — one box per tick, TDD (Vitest + Playwright), per CLAUDE.md.
5. **VERIFY** — `cd ui && npm run lint && npm run test && npx playwright test mobile-access`.
6. **CONFIRM** — re-shoot only the changed view; the raising critic checks it's
   actually resolved before the box is ticked.

### Rubrics

- **trader (Marcus):** glanceable in 3 seconds? Is the breach/limit state
  unmissable? Are the *right* numbers on a phone (not desktop density dumped
  small)? Does stale data scream (the freshness banner)? Can I trust what I see
  at 2am? Read-only — never asks for a write action (out of scope).
- **ux-designer:** touch targets ≥ 44px, thumb reach for the bottom nav, type
  scale and contrast (WCAG AA) in **both** themes, information hierarchy, no
  horizontal overflow at 390px, loading/empty/stale states, dark-mode parity.

### Conflict rule

When trader and ux-designer give **opposing** guidance on the *same view*
(e.g. denser vs bigger touch targets), TRIAGE does **not** auto-fix. It appends
the conflict under "Human calls" below and continues — the phone is small enough
that these are the user's judgement, not the loop's.

## Findings ledger

Each fix is one focused, committable change with an acceptance command. New
findings are appended here by the crit round, ordered worst-first.

<!-- BEGIN FINDINGS -->

### Round 1 — 2026-06-19

- [x] **Stale "N hours ago" has no ceiling and bad grammar** (trader+ux, high)
  `MobileFreshnessBanner.tsx` `formatRelative` reports raw hours past 60 min, so seed data shows the meaningless **"12481 hours ago"**, and "1 hours ago" is ungrammatical. Fix: `≥ 24h` → render an absolute date (`toLocaleDateString` short month/day/year); fix singular "1 hour". Add unit cases for >24h and the singular boundary.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Positions view has no freshness banner** (trader+ux, high)
  `MobilePositionsView.tsx:76` passes `dataAsOf={null}` unconditionally, so the only view with no staleness signal. Thread a timestamp from `usePositions`/the summary; if none exists, render a static "no timestamp available" banner rather than nothing — every monitor view must carry freshness.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Red/stale state must dominate the data, not just the banner** (trader+ux, high)
  At red staleness the VaR number renders crisp and full-size — a 520-day-stale number looks identical to a 2-min one. Fix: at `red`, visually degrade the data card (e.g. `opacity-50`) and make the banner heavier (`py-3`, larger type); raise dark-banner opacity `dark:bg-red-900/40`→`/70` and amber likewise so the strip reads as distinct from the page in dark mode.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Utilisation bar renders zero-width when no limit configured** (trader, high)
  `MobileRiskView.tsx` shows an empty grey bar + dashes when `hasLimit` is false — reads as "0% used / no risk", the opposite of the truth. Fix: when no limit, suppress the bar and show "No limit configured" (amber) inline with the Limit field.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Bottom nav touch targets undersized** (ux, high)
  `MobileApp.tsx:129` tab buttons use `py-2`. Bump to `py-3` and add `min-h-[48px]` so the thumb hit-zone clears 48px.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Book selector touch target undersized** (ux, high)
  `MobileApp.tsx:72` `<select>` uses `px-2 py-1` (~30px tall). Raise to `py-2`/`py-2.5` for a ≥40px hit zone; add `min-w-[7rem]` so the arrow doesn't overlap a truncated name.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Theme toggle touch target ~28px** (ux, high)
  `MobileApp.tsx:84` toggle is `p-1.5` around a 16px icon. Raise to `p-2.5`/`p-3` (~36–40px) — frequently tapped, invisible padding so no visual cost.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **P&L freshness banner absent when intraday stream hasn't delivered** (trader+ux, med)
  `MobilePnlView.tsx` feeds the banner `latest?.snapshotAt ?? null`; when the stream is silent NAV/unrealised render with no timestamp. Fix: `latest?.snapshotAt ?? summary?.asOf ?? null`, with a static "no timestamp" fallback rather than hiding.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Breach state not unmissable** (trader+ux, med)
  At breach the badge + number go red but the card border doesn't. Add a red rail/border (`border-l-4 border-l-red-600` or `border-red-300 dark:border-red-600`) so breach reads from the corner of the eye, matching the full-card treatment in `MobileAlertsView`.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Alerts empty state gives false reassurance with no feed-health signal** (trader, med)
  "You're all caught up." looks identical whether the feed is live or the socket dropped. If `useNotifications` exposes a connection/last-event signal, show "Feed connected as of X"; if it errors, switch copy to "Alert feed unavailable" (amber).
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Active tab indicated by colour only — and the active tab reads as near-invisible** (ux, med↑)
  `MobileApp.tsx` active state is only `text-primary-400` vs `text-slate-400` — fails for colour-blind users. Worse, observed in the round-1 captures: on the dark nav the active tab's `text-primary-400` icon+label are so low-contrast they look *blank* (the active slot appears empty in pnl/positions captures). Add a structural marker: `border-t-2 border-primary-400` active / `border-t-2 border-transparent` inactive, AND verify the active label/icon are legible on `bg-surface-800` in both themes (brighten the active token if needed).
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Positions dark-mode row separators invisible** (ux, med)
  `MobilePositionsView.tsx:84` cards (`dark:border-slate-700 dark:bg-surface-800`) bleed into the `surface-900` page in dark mode. Raise edge contrast: `dark:border-slate-600` or `dark:bg-surface-700`.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Alerts dark empty-state subtitle contrast borderline** (ux, med)
  `MobileAlertsView.tsx:106` subtitle `dark:text-slate-500` on near-black at `text-xs` is at the AA edge. Step to `dark:text-slate-400` or bump the size to 14px.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Risk view empty whitespace reads as broken** (ux, med)
  One small card over ~540px of blank surface looks unfinished/loading. Add a contextual note in the existing `text-xs text-slate-400` style ("VaR for selected book — see Positions for full exposure") — improves wayfinding without adding data.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **NAV has no sign colour when negative** (trader, low)
  `MobilePnlView.tsx` renders NAV neutral always. Apply `pnlColorClass` when negative (positive stays neutral). `pnlColorClass` is already imported.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Positions list silently caps at top 15** (trader, low)
  When `positions.length > TOP_N`, add a footer: "Showing top 15 by market value — full blotter on desktop."
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Alerts tab has no unread badge** (trader, low)
  Hoist `useNotifications` to the `MobileApp` shell and render a red dot/count on the Alerts tab icon when triggered alerts exist, so a user on another tab gets a passive signal.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **"Intraday P&L" label is fragile at 390px** (ux, low)
  `MobilePnlView.tsx:95` uppercase tracking-wide label is tight in a 50/50 grid. Shorten to "Intraday" (context is unambiguous on the P&L view).
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **Alert-detail Close button ~28px tall** (ux, low)
  `MobileAlertsView.tsx:194` close button `px-3 py-1`; it's the only exit from the detail overlay. Raise to `py-2` + `min-h-[44px]`.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access

- [x] **No safe-area inset on the bottom nav** (ux, low)
  `MobileApp.tsx:117` `<nav>` sits under the iPhone home indicator. Add `pb-[env(safe-area-inset-bottom,0px)]`.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test mobile-access


### Round 1 — added mid-loop (surfaced while fixing finding 11)

- [x] **Audit mobile surface for undefined Tailwind utility classes** (loop, med)
  Tailwind v4 `@theme` in `src/index.css` defines only `primary-500..900` and `surface-50/700/800/900`. Utilities like `text-primary-400`, `border-primary-300`, `surface-100..600` emit **no CSS** and render invisibly — and className-only unit tests pass anyway (caught finding 11 where the active nav tab was invisible). Grep `ui/src/components/mobile/` for `primary-[1-4]00` and `surface-(50|1|2|3|4|5|6)00` (undefined shades), replace each with a defined shade, and visually confirm. Acceptance also greps for residual undefined classes.
  Acceptance: cd ui && npm run lint && npm run test && npx tsc --noEmit && npx playwright test mobile-access && ! grep -rnE "(text|bg|border)-primary-[1-4]00|(text|bg|border)-surface-(100|200|300|400|500|600)" src/components/mobile/
<!-- END FINDINGS -->

## Done / won't-fix (dedup memory — do not re-raise)

<!-- BEGIN RESOLVED -->
- Stale freshness banner: ≥24h renders absolute date ("Data as of Jan 15, 2025"), singular "1 hour"/"1 minute" grammar fixed. `MobileFreshnessBanner.tsx`. [207b58fb]
- Positions freshness: positions feed exposes no timestamp, so a static "Position data — no timestamp available" banner now renders (was nothing). `MobilePositionsView.tsx`. [6047f562] — follow-up: if the gateway ever returns an as-of for positions, swap the static banner for the live `MobileFreshnessBanner`.
- Red staleness dominates data: extracted shared `utils/freshnessLevel.ts`; Risk card dims (`opacity-50`) when red-stale, banner heavier (`py-3 text-base`) + darker dark-mode strip. `MobileFreshnessBanner.tsx`, `MobileRiskView.tsx`. [170e4053] — follow-up: P&L + Positions cards could dim at red too (helper now reusable).
- No-limit risk state: when no VaR limit is configured, the empty bar is suppressed and an amber "No limit configured" note renders (was a dash reading as 0%/no risk). `MobileRiskView.tsx`. [aea717f8]
- Bottom-nav touch targets: four tab buttons now `py-3 min-h-[48px] justify-center` (was `py-2`). `MobileApp.tsx`. [e37b1891]
- Book selector touch target: header `<select>` now `py-2.5 min-w-[7rem]` (was `px-2 py-1`). `MobileApp.tsx`. [e99e749f]
- Theme toggle touch target: now `p-2.5` (~40px, was `p-1.5`/~28px). `MobileApp.tsx`. [5974b580]
- P&L freshness: stream-less P&L now shows a static "Intraday P&L — no timestamp available" banner (was nothing); summary DTO has no asOf field so static fallback like Positions. `MobilePnlView.tsx`. [281caf6f] — follow-up: adding an as-of to `BookAggregationDto` is a backend API contract change (out of scope).
- Breach unmissable: VaR card gets a red border+fill (`border-red-300 bg-red-50 dark:border-red-800 dark:bg-red-900/30`) on breach, matching the CRITICAL alert card; reuses existing `VAR_BREACH_THRESHOLD`. `MobileRiskView.tsx`. [15fadaa4] (visual confirm via unit test — capture mock has no limit so can't reach breach state).
- Alerts feed health: threaded `connected` from `useAlertStream` through `useNotifications`; empty state now branches on `connected && !error` — amber "Alert feed unavailable" when down, green "Feed live" when healthy (was always "You're all caught up"). `useNotifications.ts`, `MobileAlertsView.tsx`. [aee42227] — follow-ups (file separately if wanted): distinguish reconnecting vs exhausted in copy; desktop `NotificationCenter` could surface the same signal.
- Active-tab marker + visibility: active bottom-nav tab now `border-primary-500 text-white` (was undefined `text-primary-400`/`primary-300` → invisible on the dark nav); inactive `border-transparent text-slate-400`. Structural rail satisfies a11y + makes the active tab legible. `MobileApp.tsx`. [9d5c4334 + parent fix] — NB the subagent's first attempt used undefined `primary-400`/`300`; visual confirm caught it. Spawned the "undefined Tailwind utility" audit finding.
- Positions dark row contrast: rows now `dark:border-slate-600 dark:bg-surface-700` (was `slate-700`/`surface-800`, bled into the surface-900 page). `MobilePositionsView.tsx`. [e9fda74b]
- Alerts caught-up subtitle: dark shade `slate-500`→`slate-400` for AA contrast on surface-900; amber warning branch untouched. `MobileAlertsView.tsx`. [c6a2d84d] (confirmed via unit test — capture shows the amber feed-down branch, not the healthy branch).
- Risk wayfinding note: muted one-line note below the VaR card (data-present only) so the empty space reads as intentional. `MobileRiskView.tsx`. [2b5fc0d5]
- NAV negative colour: `pnlColorClass` applied to NAV only when negative; positive/zero stays neutral. `MobilePnlView.tsx`. [5b57bc3f]
- Positions truncation footer: "Showing top {TOP_N} by market value — full blotter on desktop." shown only when count exceeds the cap; reuses `TOP_N`. `MobilePositionsView.tsx`. [283f8842]
- Alerts tab badge: `useNotifications` hoisted to the shell; red count chip ("9+" cap, `aria-label`) on the Alerts tab when TRIGGERED alerts exist. `MobileApp.tsx` + new Playwright assertion. [4af47c9b]
- Intraday label: column label "Intraday P&L"→"Intraday" (context is unambiguous on the P&L view) for breathing room at 390px. `MobilePnlView.tsx`. [b9683f04]
- Alert-detail Close button: `px-3 py-1`→`px-3 py-2 min-h-[44px]`. `MobileAlertsView.tsx`. [750f6c5d]
- Safe-area inset: bottom nav now `pb-[env(safe-area-inset-bottom,0px)]` so it clears the iPhone home indicator (0px fallback = no change elsewhere). `MobileApp.tsx`. [8a98266e]
- Undefined-utility audit: grepped the whole mobile surface — no remaining undefined `primary-1xx..4xx`/`surface-1xx..6xx` classes (fix 11 had removed the only real instance; the one `primary-400` left is in a documenting comment). Grep gate added to acceptance. No code change needed.
<!-- END RESOLVED -->

## Human calls (conflicts surfaced for the user)

<!-- BEGIN CONFLICTS -->
- Round 1: none. The ux touch-target asks don't conflict with the trader's
  glanceability asks — the views are sparse (see "Risk view empty whitespace"),
  so bigger controls cost no density.
<!-- END CONFLICTS -->

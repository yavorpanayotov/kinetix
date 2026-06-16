# Mobile phone access — read-only monitor surface

## Goal

Make Kinetix usable on a phone. Today the app hard-blocks any viewport under
1280px with a "Kinetix is desktop-only" warning (`SmallViewportWarning.tsx`,
`MIN_VIEWPORT_WIDTH_PX = 1280`). This plan replaces that dead-end, below the
floor, with a purpose-built **phone-first, read-only** surface that surfaces the
monitor-and-glance views a trader actually wants from a phone — and deliberately
*excludes* the dense desktop tools that do not reflow.

This is the scope the user confirmed after the `/trader` (Marcus) review: phone,
read-only, the curated subset below. **No push notifications. No Slack/Teams. No
native app. No order entry, acknowledge, or any write action.**

## Decisions applied

1. **Dedicated mobile surface, not a responsive reflow of the desktop.** The
   dense grids (`PositionGrid`, `RiskTab`, `PnlTab`, `NotificationCenter`,
   correlation matrix, P&L waterfall) were intentionally made non-responsive
   (FU4 stripped the `sm:`/`md:`/`lg:` classes). We do not re-add responsive
   behaviour to them. Instead a separate `MobileApp` component tree renders a
   curated set of read-only cards built from the existing data hooks.
   *Overridable:* if the user prefers reflowing the existing views, stop and
   re-plan — but that is the path Marcus explicitly advised against.

2. **Breakpoint: below 1280px → `MobileApp`; 1280px and above → `AppContent`
   (unchanged).** This removes the dead-end for every small screen. Phones and
   tablets both get the mobile surface; the layout is phone-first single-column
   with a `max-width` so it also reads fine on a tablet. The existing desktop
   experience at ≥1280px is untouched.

3. **The four mobile views (Marcus's curated set):**
   - **Risk** — VaR vs limit, utilisation %, breach state. From `useVaR` + `useVarLimit`.
   - **P&L** — headline NAV / unrealised / intraday numbers only (no waterfall).
     From `useHierarchySummary` + `useIntradayPnlStream`.
   - **Alerts** — list + single-alert detail, **read-only** (no acknowledge).
     From `useNotifications` / `useAlerts`.
   - **Positions** — top exposures summary, read-only (no full grid). From
     `usePositions` + `usePositionRisk`.

4. **Navigation is local state**, mirroring the existing `activeTab` pattern in
   `App.tsx` — a bottom tab bar (thumb reach) switching a `MobileView` union.
   No router, no new dependency.

5. **Freshness is loud.** A full-width staleness banner sits at the top of every
   mobile view, reusing the threshold logic in `LastUpdatedIndicator.tsx`
   (5 min → amber, 15 min → red) but rendered as a banner, not a small inline
   label. Marcus's non-negotiable: stale data on a small screen must scream.

6. **Dark mode** is inherited from the existing `useTheme` / `.dark` mechanism —
   it is the 2am default and must work on both surfaces.

## Out of scope (do NOT checkbox)

- Push notifications, Slack/Teams delivery, native/PWA push.
- Acknowledge / escalate / resolve / snooze from mobile (write actions).
- Order entry, what-if, hedge, scenario runs from mobile.
- Reflowing `PositionGrid`, `RiskTab`, `PnlTab`, `NotificationCenter`, Greeks,
  correlation matrix, P&L waterfall, regulatory, reports, trade blotter, audit,
  system dashboard onto the phone. These stay desktop-only; the mobile surface
  simply does not offer them.
- The `bookId` / `userRole` audit-gap fixes surfaced in the PM spec — real bugs,
  but unrelated to viewing on a phone. File separately if wanted.

## Test note (guardrail)

Checkbox 1 changes behaviour that existing `SmallViewportWarning` tests cover
(below 1280px no longer shows the warning — it shows `MobileApp`). Per the
project testing policy, those tests are **updated** to assert the new behaviour,
not deleted or skipped. The `SmallViewportWarning` component itself may be
retired or repurposed as the empty-state inside `MobileApp`; either way no test
is removed without the change being reflected in an updated assertion.

## Checkboxes

- [x] Scaffold `MobileApp` shell and switch `App.tsx` to render it below 1280px
  Add `ui/src/components/mobile/MobileApp.tsx`: header (logo, book selector,
  theme toggle), a bottom tab bar with the four `MobileView` values, and
  `activeMobileView` local state. Render an empty placeholder per view for now.
  In `App.tsx`, replace the `tooSmall ? <SmallViewportWarning/> : <AppContent/>`
  branch with `tooSmall ? <MobileApp/> : <AppContent/>`. Update the existing
  `SmallViewportWarning` unit tests to assert `MobileApp` now renders below the
  floor (and `AppContent` above it). Write a `MobileApp.test.tsx` covering the
  shell, default view, and nav switching.
  Acceptance: cd ui && npm run lint && npm run test

- [x] Add `MobileFreshnessBanner` component
  New `ui/src/components/mobile/MobileFreshnessBanner.tsx`. Takes a `dataAsOf`
  timestamp, renders a full-width banner reusing the threshold/colour logic from
  `LastUpdatedIndicator.tsx` (neutral < 5 min, amber 5–15 min, red ≥ 15 min with
  a "VERIFY BEFORE ACTING" message). Unit test the three threshold states.
  Acceptance: cd ui && npm run lint && npm run test

- [x] Build the mobile **Risk** view
  New `ui/src/components/mobile/MobileRiskView.tsx`. VaR value, limit,
  utilisation % with a simple bar, and breach colour when utilisation > 0.8
  (reuse the `VAR_BREACH_THRESHOLD` constant from `RiskTickerStrip.tsx`). Data
  from `useVaR` + `useVarLimit`. Freshness banner at top. Unit test: renders
  values, shows breach styling above threshold, handles loading/empty.
  Acceptance: cd ui && npm run lint && npm run test

- [ ] Build the mobile **P&L** view
  New `ui/src/components/mobile/MobilePnlView.tsx`. Headline numbers only — NAV,
  unrealised P&L (with `pnlColorClass` from `utils/format`), intraday P&L total.
  No waterfall, no chart. Data from `useHierarchySummary` + `useIntradayPnlStream`.
  Freshness banner. Unit test: renders numbers, P&L sign colours, loading/empty.
  Acceptance: cd ui && npm run lint && npm run test

- [ ] Build the mobile **Alerts** view (read-only)
  New `ui/src/components/mobile/MobileAlertsView.tsx`. A list of alert cards
  (severity as full-card background per the UX spec, status badge, book, breach
  magnitude, triggered-at) and tap-through to a single-alert detail panel.
  **Read-only — no acknowledge/escalate/resolve controls.** Data from
  `useNotifications` / `useAlerts`. Unit test: list renders, severity styling,
  detail open/close, empty state.
  Acceptance: cd ui && npm run lint && npm run test

- [ ] Build the mobile **Positions** view (summary, read-only)
  New `ui/src/components/mobile/MobilePositionsView.tsx`. Top exposures as a
  compact list — instrument/book, market value, unrealised P&L — capped to a
  sensible count (e.g. top 15), read-only, no editable notes, no 11-column grid.
  Data from `usePositions` + `usePositionRisk` (or `useHierarchySummary` for the
  aggregate). Freshness banner. Unit test: renders rows, sorts by exposure,
  loading/empty.
  Acceptance: cd ui && npm run lint && npm run test

- [ ] Wire the four views into `MobileApp` and finalise the bottom nav
  Replace the placeholders with the real view components, wire the shared book
  selection and theme, ensure the bottom tab bar switches between Risk / P&L /
  Alerts / Positions. Update `MobileApp.test.tsx` to assert each view mounts when
  its tab is selected.
  Acceptance: cd ui && npm run lint && npm run test

- [ ] Playwright E2E at a phone viewport
  New `ui/e2e/mobile-access.spec.ts` driving a 390px viewport. Assert: the app
  loads (no "desktop-only" warning), the bottom nav switches between the four
  views, mocked data renders in each, no horizontal overflow at 390px, and the
  desktop-only tools (positions grid, scenarios, regulatory, blotter) are NOT
  present. Mock API routes per `ui/e2e/fixtures.ts`.
  Acceptance: cd ui && npx playwright test mobile-access

- [ ] Full UI suite + lint green
  Final gate: whole Vitest suite and ESLint clean, and the new Playwright spec
  passes alongside the existing ones.
  Acceptance: cd ui && npm run lint && npm run test && npx playwright test

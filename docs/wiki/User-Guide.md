# User Guide

A walkthrough of the Kinetix web application — every tab, sub-tab, panel, and control a trader, risk manager, or operator uses day to day. The UI is a React 19 + TypeScript single-page app served at `https://kinetixrisk.ai` (local) and backed by the [gateway](Services) service.

The interface is organised into **12 tabs grouped into three clusters**, sitting under a shared chrome (header, live KPI ticker, notification inbox) that is visible on every tab.

![Risk dashboard](https://raw.githubusercontent.com/yavorpanayotov/kinetix/main/docs/screenshots/risk-dashboard.png)

| Cluster | Tabs | Page |
|---|---|---|
| **Trading** | Positions, Trades, P&L | [User Guide: Trading](User-Guide-Trading) |
| **Risk** | Risk, EOD History, Scenarios, Counterparty Risk | [User Guide: Risk](User-Guide-Risk) |
| **Operations** | Regulatory, Reports, Activity, Alerts, System | [User Guide: Operations](User-Guide-Operations) |

Source of truth for tab wiring: [`ui/src/App.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/App.tsx) (the `TABS` array and cluster definitions).

---

## Screen layout

From top to bottom, every screen is composed of the same stacked regions:

1. **Header** — branding, portfolio/hierarchy selector, copilot launcher, market-regime and data-quality indicators, theme toggle, identity.
2. **Tab bar** — the 12 tabs in three clusters, separated by thin dividers, with live badges.
3. **Banners** — connection / maintenance / system-degraded notices (only when relevant).
4. **Notification strip** — a collapsed inbox of alerts, the AI morning brief, and live copilot pushes.
5. **Risk ticker strip** — an always-visible KPI row for the selected book (NAV, P&L, VaR, Greeks, connection state).
6. **Breach banner** — a sticky call-out shown on Positions / Risk / P&L when VaR utilisation exceeds 80% or a CRITICAL alert is active.
7. **Main content** — the active tab.

---

## Global chrome (visible on every tab)

### Header controls

| Control | What it does | Source |
|---|---|---|
| **Hierarchy selector** | Navigate the firm → division → desk → book tree. Picks the scope all tabs operate on; popover shows NAV and P&L roll-ups per level. | [`HierarchySelector.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/HierarchySelector.tsx) |
| **Copilot launcher** ("Ask Kinetix") | Opens the command palette in copilot mode. Shows the OS-appropriate ⌘K / Ctrl-K hint. | [`CopilotLauncher.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/CopilotLauncher.tsx) |
| **Regime indicator** | Pill showing the detected market regime (NORMAL, ELEVATED_VOL, CRISIS, RECOVERY). Click for the confidence level, the VaR parameters that regime drives, and the underlying signals (20-day realised vol, cross-asset correlation). | [`RegimeIndicator.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/RegimeIndicator.tsx) |
| **Data-quality indicator** | Health icon (OK / warning / critical) with a dropdown of individual data checks and messages. | [`DataQualityIndicator.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/DataQualityIndicator.tsx) |
| **Workspace view picker** | Save, switch, rename, and delete named workspace layouts (collapsed sections, filters). | [`WorkspaceViewPicker.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/WorkspaceViewPicker.tsx) |
| **Theme toggle** | Switch light / dark mode. | `useTheme.ts` |
| **Identity** | Production: role badge (ADMIN / RISK_MANAGER / TRADER / COMPLIANCE), username, logout. Demo: a persona switcher to act as different roles. | [`PersonaSwitcher.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/PersonaSwitcher.tsx) |

In demo mode the header also shows a **scenario indicator** (the active seeded scenario) and a **tape-replay indicator** (Live / Replay / Frozen market-data state).

### Tab bar badges

- **Alerts badge** — count of unresolved alerts (blue pill); an amber dot if alert monitoring itself errored.
- **System dot** — a red dot on the System tab when overall health is DEGRADED.
- **Keyboard nav** — arrow keys move focus between tabs; Home / End jump to first / last.

### Notification strip

A 36-px collapsed bar (bell icon + severity counts) that expands into an inbox: [`NotificationStrip.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/NotificationStrip.tsx)

- **Morning brief** — an AI-generated summary of the day's market and risk context, auto-expanded on the first open of the trading day.
- **Saved-query chips** — one-click launchers for common copilot questions (e.g. "Top VaR drivers").
- **Intraday pushes** — live AI insights streamed over WebSocket (newest first, older ones collapsed).
- **Notifications** — dismissible alert items grouped by severity.

### Risk ticker strip

An always-on KPI band for the selected book: connection status, NAV, unrealised P&L, intraday P&L, VaR with limit utilisation (red above 80%), aggregate Greeks, and the last-calc timestamp. When a VaR breach is active it surfaces a **"Need a hedge?"** button. See [`RiskTickerStrip.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/RiskTickerStrip.tsx).

### Command palette (⌘K / Ctrl-K)

[`CommandPalette.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/CommandPalette.tsx) — a global launcher with two modes:

- **Command mode** — fuzzy search across tabs, sub-tabs, books, instruments, and scenarios, with a recent-commands list.
- **Copilot mode** — free-form questions routed to the AI copilot (`/chat`), with streaming answers, inline **citations**, and multi-turn follow-ups scoped to the current book. See [AI Features](AI-Features).

![AI copilot with citation](https://raw.githubusercontent.com/yavorpanayotov/kinetix/main/docs/screenshots/copilot-narrative.png)

### Slide-in analysis panels

| Panel | Opened from | Purpose | Source |
|---|---|---|---|
| **Hedge recommendation** | `Shift+H`, the ticker's "Need a hedge?" button, the breach banner, or the Risk tab | AI-suggested hedge trades with expected VaR / Greek impact; can be sent to the What-If panel. | [`HedgeRecommendationPanel.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/HedgeRecommendationPanel.tsx) |
| **What-If** | Positions tab, or "Send to What-If" from a hedge recommendation | Add/remove hypothetical trades and see the impact on VaR, Greeks, and P&L; presets like "Reduce largest" and "Flatten delta". | [`WhatIfPanel.tsx`](https://github.com/yavorpanayotov/kinetix/blob/main/ui/src/components/WhatIfPanel.tsx) |

### Keyboard shortcuts

Press `Shift + /` (i.e. `?`) anywhere to open the shortcuts overlay.

| Shortcut | Action |
|---|---|
| `⌘K` / `Ctrl-K` | Open command palette / copilot |
| `Shift+H` | Suggest a hedge |
| `←` / `→` | Move between tabs |
| `Home` / `End` | First / last tab |
| `Esc` | Close any dialog, dropdown, or drill panel |
| `?` | Show this overlay |

### Inline AI explainers

Across the Risk, Scenarios, Reports, and Alerts tabs, **"Explain"** buttons open an inline copilot panel that streams a plain-English narrative for that specific row (a VaR result, a stress scenario, a breach) with source citations. Only one explainer is open at a time. See [AI Features](AI-Features).

---

## Cross-tab navigation

Several tabs hand off context to one another, carrying the relevant filters with them:

- **Counterparty Risk → Trades** — "Jump to Trades" opens the Trade Blotter pre-filtered to that counterparty.
- **Reports → Risk** — "Open in Risk" focuses the reported book and seeds the Risk tab's valuation date to the report's as-of date.
- **Alerts → Risk** — opening an alert focuses the hierarchy on the alert's book and switches to the Risk tab.

---

## Conventions used across tabs

- **Scope** follows the hierarchy selector — a single book shows position-level detail; a desk/division/firm shows aggregated, diversified roll-ups.
- **Export CSV** is available on most tables (positions, trades, execution costs, P&L attribution, scenarios, alerts, regulatory, reports).
- **Empty / loading / error states** are explicit everywhere: a friendly empty card, a labelled spinner, or a red error card with a Retry button. Section-level error boundaries keep one failing panel from taking down the tab.
- **Column visibility** on the Positions grid persists to `localStorage`; collapsible Risk-dashboard sections persist to the saved workspace view.

Continue to the cluster pages: [Trading](User-Guide-Trading) · [Risk](User-Guide-Risk) · [Operations](User-Guide-Operations).

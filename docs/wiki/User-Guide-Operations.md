# User Guide: Operations

The **Operations cluster** covers regulatory capital, reporting, the audit trail, alerting, and platform health. These are the tabs a risk officer, compliance team, or operator lives in.

Five tabs: **Regulatory**, **Reports**, **Activity**, **Alerts**, **System**. Each is a single-view tab (no sub-tabs). Shared chrome is in the [User Guide](User-Guide) overview.

---

## Regulatory

FRTB Standardised Approach capital. Source: [`RegulatoryDashboard.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/RegulatoryDashboard.tsx) (via [`RegulatoryTab.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/RegulatoryTab.tsx)).

![Regulatory FRTB tab](https://raw.githubusercontent.com/panayotovk/kinetix/main/docs/screenshots/regulatory-frtb.png)

- **Actions:** Calculate FRTB, Download CSV, Download XBRL (the last two enable once a result exists).
- **Summary cards:** Total Capital Charge, SBM, DRC, RRAO, plus a stacked bar of each charge's share of the total.
- **SBM breakdown table:** Delta / Vega / Curvature / Total for every risk class — GIRR, CSR (non-sec, sec-CTP, sec-non-CTP), Equity, Commodity, FX — always rendered in order, even at zero.
- **Summary metrics:** Total SBM, Gross JTD, Net DRC, Total RRAO, Total Capital Charge, with a "Calculated at" timestamp.

The methodology and bucket correlations are documented in [FRTB Capital](FRTB-Capital).

---

## Reports

Generate, narrate, and download reports. Source: [`ReportsTab.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/ReportsTab.tsx).

- **Generate** — pick a template, confirm the book, optionally set an as-of date, and Generate; Download CSV appears on completion.
- **AI commentary** — after generation, an AI narrative summarising the report's key drivers streams into a card (see [AI Features](AI-Features)).
- **Report history** — reports generated this session, each with an **"Open in Risk"** link that focuses that book and date in the Risk tab.
- **Recent reports** — a server-backed list across users, with status badges (COMPLETE / RUNNING / FAILED) and download links on completed runs.

---

## Activity

The hash-chained audit trail of every material system event. Source: [`AuditLogPanel.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/AuditLogPanel.tsx). Background: [Audit and Compliance](Audit-and-Compliance), ADR-0017.

![Activity audit trail](https://raw.githubusercontent.com/panayotovk/kinetix/main/docs/screenshots/activity-audit.png)

- **Chain-integrity indicator** — verifies the hash chain on load: "Chain verified · N events" (green) or "Chain broken · N events" (red), with a recheck action.
- **Filters** — book, trade, event type (e.g. `TRADE_BOOKED`), and a from/to time window.
- **Events table** — Time, Event (colour-coded by class: info / warning / critical / success), Subject (the affected entity), Book, and User.
- **Cursor pagination** — 25 events per page via a "Load more" button.

---

## Alerts

Define alert rules and triage the live alert queue. Source: [`NotificationCenter.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/NotificationCenter.tsx).

![Alerts tab](https://raw.githubusercontent.com/panayotovk/kinetix/main/docs/screenshots/alerts-tab.png)

**Create rule** — name, rule type (VAR_BREACH, PNL_THRESHOLD, RISK_LIMIT, DELTA_BREACH, VEGA_BREACH, CONCENTRATION, MARGIN_BREACH), threshold, operator (above / below), severity (CRITICAL / WARNING / INFO), and delivery channels (in-app / email / webhook). Inline validation gates the Create button.

**Rules table** — every rule with name, type, threshold, severity, enabled state, and delete (with a confirmation dialog).

**Recent alerts queue**
- Status-filter chips (TRIGGERED / ACKNOWLEDGED / ESCALATED / RESOLVED) with counts; RESOLVED is hidden until opted in.
- **Batch acknowledge** — select-all-visible plus a count badge and an acknowledge-selected action.
- **Per-alert actions** via inline forms: **Acknowledge** (optional note), **Escalate** (reason + assignee), **Resolve** (resolution text), **Snooze** (1 h / 4 h / 24 h / until tomorrow), **Go to Risk**, and **Explain** (an inline AI narrative of the breach).
- Rows are ordered by severity then recency; old resolved alerts (> 24 h) collapse into an expandable summary. Export CSV is available.

---

## System

Platform health and observability entry points. Source: [`SystemDashboard.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/SystemDashboard.tsx).

![System dashboard](https://raw.githubusercontent.com/panayotovk/kinetix/main/docs/screenshots/system-dashboard.png)

- **Status banner** — "All Systems Operational" (green) or "Degraded" (amber), with a refresh.
- **Service health grid** — a card per backend service (gateway, position, price, risk-orchestrator, notification, rates, reference-data, volatility, correlation, regulatory, audit) with a READY/DOWN status dot and, where configured, a link straight to that service's Grafana dashboard.
- **Observability links** — quick access to the curated Grafana dashboards: System Health, Service Overview, Risk Overview, Trade Flow, Database Health, Kafka Health, and Service Logs, plus Prometheus (dev only). See [Observability](Observability).

---

Continue to: [Trading](User-Guide-Trading) · [Risk](User-Guide-Risk) · back to the [User Guide](User-Guide).

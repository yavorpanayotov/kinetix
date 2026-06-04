# User Guide: Risk

The **Risk cluster** is the analytical heart of Kinetix. It covers market risk (VaR/ES, Greeks, factor and component decomposition), historical end-of-day analysis, stress and scenario testing, and counterparty credit risk.

Four tabs: **Risk**, **EOD History**, **Scenarios**, **Counterparty Risk**. The methodology behind these numbers is documented in [Risk Methodology](Risk-Methodology); shared chrome is in the [User Guide](User-Guide) overview.

---

## Risk

The central intraday risk dashboard. Source: [`RiskTab.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/RiskTab.tsx). It has four sub-tabs.

A **valuation-date picker** at the top runs every calculation as-of a chosen date (default today), and a **snapshot-compare control** shows deltas against −15 min / −1 h / −4 h / start-of-day. In a single-book view, **Suggest Hedge** (`Shift+H`) opens the hedge panel.

### Dashboard (default)

A collapsible, multi-section view; section open/closed state persists to the saved workspace.

**Market Risk**
- **VaR gauge** ([`VaRDashboard.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/VaRDashboard.tsx), [`VaRGauge.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/VaRGauge.tsx)) — VaR, Expected Shortfall, and limit utilisation (green < 60%, amber 60–85%, red > 85%) at a selectable confidence level (95% / 97.5% / 99%). A trend chart plots VaR (and a Greeks overlay) over time with zoom; an **Explain VaR** button and a **What-If** button sit alongside.
- **Risk sensitivities** — aggregate Delta / Gamma / Vega / Theta / Rho with explanatory popovers ([`RiskSensitivities.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/RiskSensitivities.tsx)).
- **Component breakdown** — a pie of VaR contribution by asset class or instrument type, with the diversification benefit ([`ComponentBreakdown.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/ComponentBreakdown.tsx)).
- **Aggregated-view panels** (firm / division / desk only): book-contribution table, hierarchy-contribution table, a risk-budget allocator, and a cross-asset correlation heatmap.

**Position & Factor Risk**
- **Position risk table** — per instrument: market value, full Greeks, DV01, VaR/ES contribution and % of portfolio; sortable, CSV-exportable, with inline copilot explanations per row ([`PositionRiskTable.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/PositionRiskTable.tsx)).
- **Key rate durations** — DV01 by tenor for fixed-income holdings, expandable to per-instrument detail ([`KrdPanel.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/KrdPanel.tsx)).
- **Factor decomposition** and a **factor-attribution history chart** — VaR by risk factor (equity beta, rates duration, credit spread, FX, vol), regime-adjusted when the regime is non-normal.
- A compact **counterparty-exposure tile** (the full view lives in the Counterparty Risk tab).

**P&L, Stress & Liquidity**
- **P&L summary card**, a **stress summary card** (top recent scenarios, best/worst case, "Run now"), a **liquidity-risk panel** (LVaR, concentration, data-quality and staleness), and a **margin panel** (initial / variation / total).

**Limits & Jobs**
- **Limits panel** — ceilings and utilisation by level (firm / division / desk / book / trader / counterparty), intraday and overnight, with breach colouring ([`LimitsPanel.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/LimitsPanel.tsx)).
- **Job history** — recent VaR calculation jobs, each with a "Compare" action that opens Run Compare.

In an aggregated view before a cross-book run, the dashboard notes it is showing a *sum* of book VaRs and offers **Recalculate All** to compute the diversified portfolio figure.

### Intraday

A detailed intraday VaR evolution chart with **trade-event markers** (time, side, instrument, qty/price) overlaid, brush-to-zoom, and a tooltip per point. Source: [`IntradayVaRChart.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/IntradayVaRChart.tsx). Answers "did VaR spike from a trade or a market move?"

### Run Compare

Side-by-side comparison of two calculation runs. Source: [`RunComparisonContainer.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/RunComparisonContainer.tsx). Three modes:

- **Daily VaR** — compare two dates.
- **Model** — compare two methodologies (e.g. parametric vs. historical).
- **Backtest** — predicted VaR vs. realised P&L, flagging exceptions with hit-rate and the Kupiec POF test ([`BacktestComparisonView.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/BacktestComparisonView.tsx)).

Each comparison surfaces component, position, and Greek deltas, new/resolved limit breaches, and a threshold slider to filter small moves.

### Market Data

The reference surfaces feeding the engine:
- **Vol surface** — implied-vol skew (vs. strike) and term structure (vs. maturity) for a chosen instrument, with an optional compare-to date ([`VolSurfacePanel.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/VolSurfacePanel.tsx)).
- **Yield curve** — yield vs. tenor by currency, with hollow markers on interpolated points ([`YieldCurvePanel.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/YieldCurvePanel.tsx)).

---

## EOD History

Historical end-of-day risk snapshots with drill-down. Source: [`EodTimelineTab.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/EodTimelineTab.tsx).

- **Date-range picker** with presets (1D, 5D, 1M, 3M, YTD).
- **Trend chart** — VaR and ES by calendar date; gaps for non-trading days; click a date to drill in.
- **Daily grid** — one row per day (date, VaR, ES, position count, promotion metadata) with checkboxes to select up to two dates for comparison.
- **Drill panel** — a side panel showing the position-risk table as-of the selected EOD date and its promotion provenance ("Promoted by … at …"); comparing two dates shows the changes between them.

---

## Scenarios

Stress testing, scenario governance, and reverse stress. Source: [`ScenariosTab.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/ScenariosTab.tsx).

- **Control bar** — Run All (approved scenarios), Custom Scenario, Compare (2–3 selected), Export CSV, Manage Scenarios, Reverse Stress; plus confidence-level and time-horizon selectors.
- **Comparison table** — per scenario: category badge, base VaR, stressed VaR, multiplier, P&L impact, and limit-breach status. Rows expand into a **detail panel** with asset-class, position-level, and Greek-under-stress views; an **Explain** button narrates the result.
- **Custom scenario builder** — define vol and price shocks, then **Save as Scenario** (submitted for approval) or **Run as Ad-Hoc** ([`CustomScenarioBuilder.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/CustomScenarioBuilder.tsx)).
- **Governance panel** — a library of scenarios with Draft / Submitted / Approved / Retired status and approve/retire actions (risk-officer gated). See [Audit and Compliance](Audit-and-Compliance).
- **Historical replay** — replay an approved historical basket against the current book to find the worst historical period.
- **Reverse stress** — solve for the market moves that would produce a target loss.

---

## Counterparty Risk

Credit exposure, PFE, CVA, and regulatory counterparty capital. Source: [`CounterpartyRiskDashboard.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/CounterpartyRiskDashboard.tsx).

- **Counterparty list (left)** — searchable, sortable table: Counterparty, Net Exposure, Peak PFE, CVA (italic when estimated), and a Wrong-Way-Risk indicator. High-exposure counterparties (top decile) and those with an **expired ISDA agreement** are badged; the latter offer a **Block new trades** action. A methodology label reads e.g. "Monte Carlo · 95% · 1Y".
- **Detail view (right)** — for the selected counterparty: net exposure, peak PFE, and CVA metrics; **Compute PFE** and **Compute CVA** actions; and a **PFE profile chart** plotting Expected Exposure and PFE-95 across tenors.
- **SA-CCR panel** — the Standardised Approach for Counterparty Credit Risk: replacement cost, EEPE, multiplier, and add-ons by asset class ([`SaCcrPanel.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/SaCcrPanel.tsx)).
- **Block trades dialog** — opens a remediation ticket and blocks new orders for a counterparty with an expired agreement.
- **Jump to Trades** — opens the Trade Blotter filtered to that counterparty.

---

Continue to: [Trading](User-Guide-Trading) · [Operations](User-Guide-Operations) · back to the [User Guide](User-Guide). Methodology detail: [Risk Methodology](Risk-Methodology), [FRTB Capital](FRTB-Capital).

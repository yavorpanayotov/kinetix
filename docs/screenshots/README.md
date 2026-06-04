# UI Screenshots

Captured from the running Kinetix UI with deterministic mocked routes (the same
fixtures the Playwright e2e suite uses), so the numbers are stable and no live
backend is required. Regenerate with:

```bash
cd ui && npx playwright test e2e/screenshots/capture.spec.ts --project=chromium --workers=1
```

Capture spec: [`ui/e2e/screenshots/capture.spec.ts`](../../ui/e2e/screenshots/capture.spec.ts).
These back the per-tab [User Guide](../wiki/User-Guide.md) wiki pages.

> Run with `--workers=1` for deterministic capture — a couple of book-scoped tabs
> can otherwise race the async book-selection on a slow dev server.

| Screenshot | Tab | Description |
| --- | --- | --- |
| ![Positions](positions-tab.png) | Positions | Firm summary, book summary cards, and the live position grid with valuation + risk columns. |
| ![Trade Blotter](trades-blotter.png) | Trades · Blotter | Trade history with side/fill/status, venue routing, and CSV export. |
| ![Execution Cost](trades-execution-cost.png) | Trades · Execution Cost | Realised slippage and transaction-cost analysis per filled order. |
| ![Reconciliation](trades-reconciliation.png) | Trades · Reconciliation | Internal vs prime-broker holdings with CLEAN/BREAKS status. |
| ![Place Order](trades-place-order.png) | Trades · Place Order | Order ticket with pre-trade risk preview. |
| ![P&L](pnl-tab.png) | P&L | Intraday P&L panel and the SOD-baseline attribution workflow. |
| ![Risk Dashboard](risk-dashboard.png) | Risk · Dashboard | Firm cross-book VaR with diversification benefit, book contributions, correlation matrix, position/factor risk. |
| ![Intraday VaR](risk-intraday.png) | Risk · Intraday | Intraday VaR evolution with trade-event markers. |
| ![Market Data](risk-market-data.png) | Risk · Market Data | Vol skew, ATM term structure, and the GBP yield curve (interpolated 5Y). |
| ![EOD History](eod-history.png) | EOD History | VaR/ES trend chart and the daily grid with promotion status. |
| ![Scenarios](scenarios-tab.png) | Scenarios | Stress-test comparison table with multipliers, P&L impact, and limit breaches. |
| ![Counterparty Risk](counterparty-risk-tab.png) | Counterparty Risk | Per-counterparty net exposure, peak PFE, CVA, and WWR. |
| ![Regulatory](regulatory-frtb.png) | Regulatory | FRTB capital — SbM/DRC/RRAO with the per-risk-class breakdown. |
| ![Reports](reports-tab.png) | Reports | Report generation form and recent-reports history. |
| ![Activity](activity-audit.png) | Activity | Hash-chained audit trail (chain verified) with color-coded event types. |
| ![Alerts](alerts-tab.png) | Alerts | Alert-rule creation and the triage queue with per-alert actions. |
| ![System](system-dashboard.png) | System | Service-health grid and observability dashboard links. |
| ![Copilot](copilot-narrative.png) | Copilot | The AI copilot answering "Why did my VaR move?" with a grounded, cited tool call. |

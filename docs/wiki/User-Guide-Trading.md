# User Guide: Trading

The **Trading cluster** is the first group in the tab bar. It is where a trader captures and reviews activity for the selected book: live holdings, the order/trade lifecycle, and daily P&L attribution.

Three tabs: **Positions**, **Trades**, **P&L**. All operate on the scope chosen in the [hierarchy selector](User-Guide#global-chrome-visible-on-every-tab) and most support a trader filter and CSV export.

For shared chrome (header, ticker, copilot, hedge / what-if panels) see the [User Guide](User-Guide) overview.

---

## Positions

A live portfolio grid with valuation and risk analytics per holding. Source: [`PositionGrid.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/PositionGrid.tsx).

![Positions tab](https://raw.githubusercontent.com/panayotovk/kinetix/main/docs/screenshots/positions-tab.png)

### Summary cards

A row of book-level cards: **position count**, **market value**, **unrealised P&L** (colour-coded), and — when risk data is loaded — **book delta** and **book VaR**.

### Filtering and controls

- **Instrument search** — case-insensitive match on ticker and display name.
- **Instrument-type filter** — dropdown with per-type counts (e.g. `STOCK (24)`, `OPTION (5)`).
- **Details toggle** — show/hide the quantity, average cost, and market-price columns.
- **Column settings** — per-column visibility checkboxes; the choice persists to `localStorage`.
- **Export CSV** — downloads the visible columns for the filtered rows.
- **What-If** — opens the [What-If panel](User-Guide#slide-in-analysis-panels) seeded with the current book.
- **Live / Disconnected** indicator — driven by the price-stream WebSocket; the grid dims while reconnecting.

### The grid

Paginated at 50 rows, horizontally scrollable, grouped under **Position Details** and **Risk Metrics** headers when risk data is present.

| Group | Columns |
|---|---|
| Identifying | Instrument, Name, Type (badge), Asset Class |
| Valuation | Last Price, [Quantity, Avg Cost, Market Price — via Details toggle], Market Value, Unrealised P&L, Realised P&L |
| Risk (when available) | Delta, Gamma, Vega, VaR Contribution % — all **sortable** |

A footer totals the VaR-contribution column (which can exceed 100% because of diversification offsets). When a single book is selected, a **notes** column lets you attach and delete per-instrument notes via a popover ([`PositionNotePopover.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/PositionNotePopover.tsx)).

**Empty states:** "No positions to display", or "No positions match the current filter" when a filter clears the grid.

---

## Trades

The order and trade lifecycle. Four sub-tabs, selected via the sub-tab bar.

### Trade Blotter

Every trade with fill status and venue routing. Source: [`TradeBlotter.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/TradeBlotter.tsx).

![Trade blotter](https://raw.githubusercontent.com/panayotovk/kinetix/main/docs/screenshots/trades-blotter.png)

- **Filters:** instrument (substring), side (Buy/Sell), counterparty (server-side), instrument type. A **venue-routing status dot** (green/amber/red) sits top-left.
- **Columns:** Time, Instrument, Name, Type, Side (green buy / red sell), Qty, Filled, Open, Price, Notional, Status badge, and optional Venue and Venue Order ID (with copy-to-clipboard).
- **Status badges** are colour-coded — FILLED (green), CANCELLED / REJECTED (red), PENDING / WORKING / EXPIRED (grey), PARTIAL / AMENDED (amber).
- **Row expansion** — terminal-status trades (expired, cancelled, rejected) expand to show the fills/amendments history.
- **Pagination** is server-side (50 per page) with "Showing X–Y of Z".
- Arriving here via "Jump to Trades" from Counterparty Risk pre-fills the counterparty filter.

### Place Order

A minimal order ticket (cash equity) that routes through the FIX gateway. Source: [`PlaceOrderPanel.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/PlaceOrderPanel.tsx).

- **Fields:** instrument, side, quantity, order type (LIMIT / MARKET), arrival price, limit price (LIMIT only), time-in-force (DAY / GTC / IOC / FOK / GTD), venue (NYSE / NASDAQ / LSE / TSE / HKEX / OTHER).
- **Pre-trade risk preview** — once the required fields are filled, a panel previews the candidate order's risk impact before you submit ([`PreTradeRiskPreviewPanel.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/PreTradeRiskPreviewPanel.tsx)).
- **Holiday warning** — an amber banner if the TIF is DAY/GTD on a venue whose holiday calendar is incomplete.
- **Lifecycle states:** idle → submitting → success (a confirmation dialog with clOrdID and copyable venue order ID) or error (banner with retry). The submit button is disabled until the form is complete.

### Execution Cost

Realised slippage and transaction-cost analysis on filled orders. Source: [`ExecutionCostPanel.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/ExecutionCostPanel.tsx).

- **Columns:** Order ID, Instrument, Side, Qty, Arrival Price, Avg Fill, Slippage (bps), Total Cost (bps), Completed.
- Slippage and total cost are colour-coded (amber = cost, green = gain).
- An amber **simulation-mode banner** reminds you that orders are logged, not transmitted to a broker.

### Reconciliation

Internal positions vs. prime-broker holdings. Source: [`ReconciliationPanel.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/ReconciliationPanel.tsx).

- One **summary card per reconciliation date** with a CLEAN (green) or BREAKS (amber) badge and a matched count (e.g. "Matched: 47 / 50").
- When breaks exist, a detail table lists Instrument, Internal Qty, PB Qty, Break Qty, and Break Notional.

---

## P&L

Intraday P&L and daily attribution to Greek factors. Source: [`PnlTab.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/PnlTab.tsx).

![P&L tab](https://raw.githubusercontent.com/panayotovk/kinetix/main/docs/screenshots/pnl-tab.png)

### Intraday P&L chart

Cumulative intraday P&L over the trading day, with optional **trade markers** annotating execution times. Driven live by the intraday-P&L WebSocket, falling back to the last session's historical snapshots when live data is unavailable (with a "Last session" indicator).

### Start-of-Day baseline

Attribution is measured against an SOD baseline ([`SodBaselineIndicator.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/SodBaselineIndicator.tsx)):

- **Create snapshot** — set the baseline from the current market state.
- **Pick from history** — choose a baseline from a past valuation job.
- **Reset baseline** — clear it (with a confirmation dialog).

If no baseline exists the tab prompts you to set one; once set, **Compute P&L Attribution** runs the decomposition.

### Attribution outputs

- **Waterfall chart** — total P&L decomposed into its components ([`PnlWaterfallChart.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/PnlWaterfallChart.tsx)).
- **Factor attribution table** — per position: Total P&L plus Delta / Gamma / Vega / Theta / Rho contributions and an **Unexplained** residual ([`PnlAttributionTable.tsx`](https://github.com/panayotovk/kinetix/blob/main/ui/src/components/PnlAttributionTable.tsx)).
- **Benchmark attribution** — a comparison section against a benchmark when a book is selected.
- A provenance line records which baseline produced the figures; **Export CSV** and **Recompute** are available once data exists.

---

Continue to: [Risk](User-Guide-Risk) · [Operations](User-Guide-Operations) · back to the [User Guide](User-Guide).

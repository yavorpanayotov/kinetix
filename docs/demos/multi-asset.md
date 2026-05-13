# Multi-asset demo flow (~5 min)

The default scenario. Use this as the warm-up for any first-time buyer
who hasn't told you which segment they're in. It shows the firm-wide
shape without claiming any particular specialisation.

## Setup

```bash
curl -sS -X POST \
  -H "X-Demo-Reset-Token: $DEMO_RESET_TOKEN" \
  "https://api.kinetixrisk.ai/api/v1/admin/demo-reset?scenario=multi-asset"
```

Wait for the gateway to return `ok` (all 8 services reset). Confirm the
scenario pill in the UI header reads **Multi-Asset** before opening the
deck.

## Story

> Kinetix is one platform across asset classes — equities, rates, credit,
> FX, commodities, and derivatives — running 8 books in 3 divisions. Every
> trade lands in the same blotter, every position rolls up to the same
> firm-level limits, every risk number is recalculated on the same engine.

## Click path

1. **Header → Scenario indicator.** Point out the **Multi-Asset** pill
   next to the data-quality and regime indicators. "This is the dataset
   loaded — we'll swap to focused scenarios in a minute."
2. **Books tab → BookSummaryCard.** Scroll the 8 books. Stop on
   `multi-asset` and `derivatives-book`. Show the asset-class mix.
3. **Trade Blotter.** Apply the **Trader** dropdown filter — the
   server-paginated blotter renders the first 50 of ~95k tape trades in
   under three seconds. Sort by `Traded At`.
4. **VaR Dashboard.** Open the firm VaR gauge. Show the regime bands on
   the trend chart — the Mar-2020 and Oct-2022 analogs are visible.
5. **CounterpartyRiskDashboard.** Highlight the 30-name universe with
   ratings spread AAA → BB and the percentile-flagged top-decile
   exposures.

## Expected screen states

| Step | What you should see |
|---|---|
| 1 | Header pill: **Multi-Asset**. RegimeIndicator: **Normal**. |
| 2 | 8 books, 3 divisions, mix of equity / fixed-income / FX / commodity rows. |
| 3 | Blotter ≤ 3 s initial paint. Page count > 1,000. |
| 4 | VaR trend chart with two grey vertical bands labelled `2020-Q1 stress` and `2022-Q4 stress`. |
| 5 | Counterparty table shows 30 rows with sort + search; top-decile rows highlighted. |

## Closing

> That's the platform on default. The next thing I'd like to show you is
> the same machinery focused on \[buyer's segment\] — switching scenarios
> takes 90 seconds and reshapes the entire dataset around their workflow.

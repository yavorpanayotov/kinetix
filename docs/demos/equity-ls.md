# Equity Long/Short demo flow (~5 min)

For equity hedge funds, multi-strat pods, and equity prop desks. Shows
how Kinetix carries a real factor-neutral book with sector tilts and
sub-strategy attribution.

## Setup

```bash
curl -sS -X POST \
  -H "X-Demo-Reset-Token: $DEMO_RESET_TOKEN" \
  "https://api.kinetixrisk.ai/api/v1/admin/demo-reset?scenario=equity-ls"
```

After all 8 services return `ok`, the scenario pill should read
**Equity L/S**. Position count for the `equity-ls` book ≈ 800.

## Story

> A factor-neutral, sector-tilted L/S book — 800 positions, four sectors
> net long (Tech / Healthcare / Industrials / Consumer Discretionary)
> and four sectors net short (Financials / Energy / Consumer Staples /
> Materials). Gross long and gross short balance to within a few percent
> of total gross, so the book carries no directional market beta — it's a
> pure factor / sector bet.

## Click path

1. **Header.** Confirm **Equity L/S** pill. Note the regime indicator
   stays normal — this isn't a stress demo.
2. **Books tab → BookSummaryCard for `equity-ls`.** Gross long ≈ Gross
   short. Net exposure visibly small relative to gross. "That's
   factor-neutral — they care about stock selection and sector tilts,
   not about whether the S&P goes up tomorrow."
3. **PositionGrid → Sector group.** Sort positions by sector. Show the
   four net-long sectors, then the four net-short sectors. Each sector
   has ~100 positions; the long/short split is 65/35 in the tilted
   direction.
4. **FactorDecompositionPanel.** Switch from "Notional" to "Factor
   contribution". Walk through Market β (near zero), Size, Value,
   Momentum, Quality. The buyer should see the book's sector tilts
   show up here as the dominant factor exposures.
5. **Trade Blotter → filter by book = `equity-ls`.** Show the trade
   density — every position has supporting trades that ladder over the
   last 19 days. Filter by sector prefix (e.g. `TCH` for Tech) to scope
   the blotter to one sector.

## Expected screen states

| Step | What you should see |
|---|---|
| 1 | Header pill: **Equity L/S**. |
| 2 | BookSummaryCard shows `equity-ls`. Gross long minus gross short < 20 % of total gross. |
| 3 | 8 sector groups, ~100 positions each. 4 net-long, 4 net-short. |
| 4 | Market β bar near zero; sector / size factors carry the loading. |
| 5 | Blotter populated with `seed-els-*` trade IDs; trader and counterparty filters work. |

## Closing

> One book, 800 names, factor-neutral on the surface and sector-bet
> underneath — and every change rolls up to the same firm limits and
> the same risk recalc. That's the L/S workflow on Kinetix.

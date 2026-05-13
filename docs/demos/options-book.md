# Options-book demo flow (~5 min)

For derivatives desks, vol traders, and anyone whose first question is
"does it do Greeks". Walks through the full vol smile, term structure,
and gamma profile on a 1,000-position book.

## Setup

```bash
curl -sS -X POST \
  -H "X-Demo-Reset-Token: $DEMO_RESET_TOKEN" \
  "https://api.kinetixrisk.ai/api/v1/admin/demo-reset?scenario=options-book"
```

After `ok` on all 8 services the scenario pill should read
**Options Book**. Total positions ≈ 1,056 across two books:
`options-equity-vol` (576) and `options-cross-asset-vol` (480).

## Story

> Two vol books, 1,056 option positions across weekly and monthly
> expiries. Each underlying has a 4-strike ladder — in-the-money, ATM,
> and two out-the-money strikes — for both calls and puts. The book
> exposes the full vol smile, term structure, and the gamma profile
> through weekly expiry. Every Greek is recalculated by the same risk
> engine the rest of the firm uses.

## Click path

1. **Header.** Confirm **Options Book** pill.
2. **Books tab.** Two books visible: `options-equity-vol` and
   `options-cross-asset-vol`. Open the equity book.
3. **PositionGrid → group by Underlying.** Pick `SPX`. Show the 48
   positions: 6 expiries × 4 strikes × call+put.
4. **VolatilitySurfaceView (or vol-smile chart).** Show the SPX smile at
   the M1 tenor — OTM puts priced richer than OTM calls. Switch tenor
   to M3 and show the term-structure flattening.
5. **GreeksTrendChart for the SPX positions.** Walk through Delta,
   Gamma, Vega, Theta. Pause on Gamma — point out the spike as W1
   approaches expiry.
6. **Cross-asset book.** Open `options-cross-asset-vol`. Show VIX, gold
   (`GC`), oil (`CL`), and FX (`EURUSD`, `USDJPY`). "Same engine, same
   Greeks shape, different asset classes."

## Expected screen states

| Step | What you should see |
|---|---|
| 1 | Header pill: **Options Book**. |
| 2 | Two books listed; option counts ~576 and ~480. |
| 3 | 48 SPX positions in a 6 × 4 × 2 ladder. |
| 4 | Vol smile asymmetric on M1 (put skew); flatter on M3. |
| 5 | Greeks chart with non-trivial gamma on weekly tenors. |
| 6 | Cross-asset book shows VIX/GC/CL/FX with Greeks computed. |

## Closing

> Full smile, full term structure, full Greeks — across 1,000 positions
> and two asset classes — on the same engine the rest of the platform
> uses. No external risk system, no spreadsheet copy-paste.

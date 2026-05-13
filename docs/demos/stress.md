# Stress / limit-excession demo flow (~5 min)

For heads of risk, CROs, compliance officers, and anyone who buys risk
software because of the bad day, not the good one. Walks the audience
through a live limit breach and the workflow that follows.

## Setup

```bash
curl -sS -X POST \
  -H "X-Demo-Reset-Token: $DEMO_RESET_TOKEN" \
  "https://api.kinetixrisk.ai/api/v1/admin/demo-reset?scenario=stress"
```

The scenario opens with `stress-vol` already in breach. Confirm before
starting:

```bash
curl -sS https://api.kinetixrisk.ai/api/v1/risk/limits/breaches | jq '.[] | select(.bookId=="stress-vol")'
```

Should return two breaches: notional and concentration.

## Story

> Three books — two healthy, one already over the line. `stress-vol` is
> a vol-trading book with a heavy NVDA leg; it's blown through both its
> $35 M book notional and its 30 % single-name concentration limit. The
> next five minutes show what the platform does about it, from the
> first alert to the audit trail of the override decision.

## Click path

1. **Header.** Confirm **Stress** pill. The DataQualityIndicator should
   show **Limit breach: 2 active** in red.
2. **LimitsPanel.** Open the dashboard. Three rows — `stress-momentum`,
   `stress-credit`, `stress-vol`. The vol book row is red with both a
   notional and a concentration breach. Click into it.
3. **LimitBreachCard for `stress-vol` notional.** Show current $≈47 M,
   limit $35 M, breach age, severity HARD. Open the audit trail link
   — every limit-check event is hash-chained.
4. **PositionGrid for `stress-vol` → sort by Notional desc.** NVDA at
   ~$26.5 M, ~57 % of the book. Drill into NVDA position → shows the
   single-name concentration is the driver.
5. **StressedGreeksView (optional).** Run the parallel +10 % equity
   shock. The book P&L move should be visibly asymmetric because of the
   concentrated long-vol leg.
6. **AlertDrillDownPanel.** Show the alert that fired when the scenario
   loaded. Resolution choices: **Approve override / Cut position /
   Escalate**. Choosing escalate writes a new audit event — click
   through to show it landed.

## Expected screen states

| Step | What you should see |
|---|---|
| 1 | Header: **Stress** pill + red DataQualityIndicator. |
| 2 | LimitsPanel: 3 rows, `stress-vol` red with two breach pills. |
| 3 | LimitBreachCard: current > limit, severity HARD, audit-trail link works. |
| 4 | PositionGrid: NVDA row at top, ~57 % of book gross. |
| 5 | Stress shock shows asymmetric P&L on `stress-vol`. |
| 6 | Alert resolution writes an audit event; refresh shows it in the chain. |

## Closing

> One book in active breach, two healthy, every action audited. Even
> for a risk manager seeing the platform cold, the workflow from alert
> to resolution to audit-trail is the same five minutes you just
> watched. That's what a real audit story looks like.

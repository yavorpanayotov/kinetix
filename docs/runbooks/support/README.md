# Trader-Facing Support Runbooks

When a trader complaint comes in, find it here. Each runbook is a 15-minute resolution
path: it tells you exactly what to check, what commands to run, and when to escalate.
Open the runbook that matches the complaint, work top-to-bottom, and you should be at
a resolution or a well-formed escalation within 15 minutes.

---

## Quick-reference table

| Trader complaint | Runbook | Primary dashboard(s) | Primary CLI script |
|---|---|---|---|
| "My P&L is wrong / stuck / jumped without a trade" | [pnl-looks-wrong.md](pnl-looks-wrong.md) | `trade-lifecycle.json`, `risk-run-health.json` | `scripts/support/follow-trade.sh` |
| "VaR hasn't moved / my risk numbers are frozen" | [var-is-stale.md](var-is-stale.md) | `risk-run-health.json` | Loki queries (see runbook); `scripts/support/audit-chain-verify.sh` if chain integrity is suspected |
| "My position is missing / the trade went through but my book hasn't updated" | [position-missing.md](position-missing.md) | `trade-lifecycle.json` | `scripts/support/follow-trade.sh` |
| "Prices are frozen / the feed has dropped / VaR is using yesterday's prices" | [market-data-frozen.md](market-data-frozen.md) | `business-alerts.json` | `scripts/support/price-freshness.sh` |
| "A limit breach should have fired but I didn't get an alert" | [limit-breach-didnt-fire.md](limit-breach-didnt-fire.md) | `business-alerts.json` | Loki queries (see runbook; no script yet) |

---

## Dashboard URLs

| Dashboard | Grafana path |
|---|---|
| Trade Lifecycle | `/d/kinetix-trade-lifecycle` |
| Risk Run Health | `/d/kinetix-risk-run-health` |
| Business Alerts & Events | `/d/kinetix-business-alerts` |

All dashboards are at `https://grafana.kinetixrisk.ai`.

---

## CLI scripts

| Script | Purpose |
|---|---|
| `scripts/support/follow-trade.sh <tradeId>` | Follows a single trade through position-service, audit-service Loki, and the DLQ — the fastest way to determine whether a trade event is stuck or missing |
| `scripts/support/price-freshness.sh` | Dumps current `price_staleness_seconds` sorted worst-first and checks price-service pod status |
| `scripts/support/audit-chain-verify.sh` | Calls `/api/v1/audit/verify` and exits non-zero if `valid` is `false` — use when a `var-is-stale` investigation reveals a broken hash chain |

---

## When you can't resolve

Every runbook has its own **Escalation** table at the bottom — that is the first place to
look for who to call and what context to provide. As a general reminder:

- Never escalate a bare "it's broken." Always attach: symptom, timeline, what you have
  checked, your hypothesis, and relevant log lines or Prometheus query output.
- For P1 incidents (trading desk unable to book, risk numbers entirely absent, limit
  enforcement not firing), page the on-call engineer immediately via PagerDuty while you
  continue your own investigation in parallel.
- If an issue touches multiple runbooks (e.g. stale prices causing wrong P&L causing a
  missed limit breach), start with `market-data-frozen.md` — fix the root cause first,
  then re-verify the downstream symptoms.

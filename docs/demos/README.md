# Demo flow scripts

Five-minute click-path scripts for each Kinetix demo scenario. Each script is
self-contained — open the file, follow it line by line, finish in ~5 minutes.

Scenarios are loaded by hitting `/api/v1/admin/demo-reset?scenario=<id>` on
the gateway with the `X-Demo-Reset-Token` header. The nightly cron at 02:00
defaults to `multi-asset` so unattended demos keep working.

| Scenario | Buyer fit | Script |
|---|---|---|
| `multi-asset` | Generalist intro — 8 books across asset classes, baseline depth | [multi-asset.md](multi-asset.md) |
| `equity-ls` | Equity hedge fund / multi-strat pod | [equity-ls.md](equity-ls.md) |
| `stress` | Heads of risk, CROs, compliance | [stress.md](stress.md) |
| `options-book` | Derivatives desks, vol traders | [options-book.md](options-book.md) |

The `regulatory` profile is advertised by the API but rejected as
`SCENARIO_NOT_AVAILABLE` until the proper amend/cancel/novation domain
commands ship (see Phase 3 in `docs/plans/demo-review.md`).

## Cadence tuning before a live walkthrough

The demo-orchestrator drips synthetic trades every 90 s by default — fine for
background running but too slow to fill the blotter during a 5-minute
walkthrough. For live sessions, switch to a 30 s cadence by layering the
live-demo override (Compose or Helm) before starting the demo. The full
profile table — including the tighter limit-breach knobs
(`DEMO_BREACH_BOOK`, `DEMO_BREACH_VAR_FACTOR`) used for the early-breach beat —
lives in
[`demo-orchestrator/README.md`](../../demo-orchestrator/README.md#live-demo-vs-background-mode-tuning).

## Conventions used in the scripts

- **Setup** — the curl that loads the scenario. Wait for `200 OK` on every
  fan-out target before starting the demo (audit settles within 90 s).
- **Story** — one-paragraph framing for the buyer in the room.
- **Click path** — numbered steps. Each step names the UI surface so a
  presenter following the script blind can keep up.
- **Expected screen** — what the buyer should see. If it doesn't match,
  the demo data has drifted; flag it before continuing.
- **Closing** — one-line wrap-up that lands the pitch for the segment.

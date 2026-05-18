# ADR-0019: Official EOD/SOD Labeling with Promotion Governance

## Status
Accepted

## Context
Multiple VaR calculations run throughout the day (ad hoc, intraday, pre-close). Regulators and risk managers need to distinguish the "official" end-of-day (EOD) result from ad hoc runs. This official result feeds regulatory reporting and daily P&L attribution.

## Decision
Introduce `RunLabel` to classify each valuation run and `EodPromotionService` to govern the promotion of a completed run to "Official EOD" status.

**Run labels:** `ADHOC`, `SOD`, `INTRADAY`, `OVERNIGHT`, `PRE_CLOSE`, `OFFICIAL_EOD`, `SUPERSEDED_EOD`

**Promotion governance (`EodPromotionService`):**
- Only `COMPLETED` runs can be promoted
- A run cannot be promoted twice (`AlreadyPromoted` exception)
- The promoter cannot be the same person who triggered the run (four-eyes principle, `SelfPromotion` exception)
- Promoting a new run for the same book/date automatically supersedes the previous Official EOD
- Promotion emits an `OfficialEodPromotedEvent` (Kafka) and a `EodPromotedAuditEvent` (risk audit topic)
- Demotion is supported for corrections (`demoteFromOfficialEod`)
- Requires `PROMOTE_EOD_RUN` permission (granted to `RISK_MANAGER` and `ADMIN` roles)

## Applies when
- Adding a new run label, or any feature touching EOD selection.
- Writing code that promotes, demotes, or queries the official EOD run.
- Building a regulatory report, dashboard, or P&L attribution that consumes "the EOD result".

## Rules
- **DO** filter on `runLabel = OFFICIAL_EOD` when "the EOD result" is required. Never reach for "latest run of the day" as a substitute.
- **DO** enforce the four-eyes rule — the promoter's `userId` must differ from the run's originator. `EodPromotionService.promoteToOfficialEod` already checks this; don't bypass it.
- **DO** emit both `OfficialEodPromotedEvent` (Kafka) and an audit-chain entry on every promotion and demotion.
- **DO** check the `PROMOTE_EOD_RUN` permission at the gateway (ADR-0013) for any new promotion endpoint.
- **DO** treat supersession as automatic: promoting a new official EOD demotes the previous one to `SUPERSEDED_EOD` in the same transaction.
- **DON'T** promote a run that is not `COMPLETED`. The service throws — don't catch and retry.
- **DON'T** allow a run to be promoted twice. The `AlreadyPromoted` exception is the intended behaviour.
- **DON'T** add a new "promote silently" path. Demotion exists for corrections and must remain explicit and auditable.

## Consequences

### Positive
- Clear audit trail of which run was blessed as official and by whom
- Four-eyes principle prevents a single user from both running and approving a calculation
- Supersession logic ensures at most one Official EOD per book per date
- Events enable downstream systems (regulatory reporting, dashboards) to react to promotions

### Negative
- Adds operational ceremony — someone must explicitly promote the EOD run each day
- Supersession could surprise users if they promote without realising a prior EOD exists
- Demotion capability requires careful access control to prevent misuse

### Alternatives Considered
- **Automatic EOD**: The last run of the day is automatically marked as official. Simpler, but no human review — a bad late-day run would become the official result.
- **Time-window based**: Runs within a specific window (e.g., 16:00-16:30) are candidates. Too rigid — late market data or reruns would be excluded.
- **No official designation**: All runs are equal. Fails regulatory reporting requirements that demand a single authoritative daily result.

**Note (updated 2026-04-07):** Terminology updated to reflect the portfolio→book rename (V34). References to "portfolio" in the governance context now use "book".

# Vignette: Limits — boundaries are where risk systems fail

> A shorter walk through the same loop as the [flagship](counterparty-risk.md),
> on a feature small enough to read in one sitting:
> [`specs/limits.allium`](../../specs/limits.allium) (247 lines). The theme
> here is that the *interesting* decisions in a limits engine are all at the
> boundaries — and boundaries are precisely where a confident-but-wrong AI
> edit does the most damage. Each fix below is a boundary the engineer had
> to call correctly; the spec is where that call is recorded as the
> contract the tests then enforce.

## Three boundary calls

### `kx-fx9` — inclusive at the limit

`checkPositionLimit()` returns `WithinLimit` when `quantity <= limit`:
a 1M-share limit *accepts* a 1M-share position; **+1 share is the first
breach**. `Breach.overage = quantity - limit`; negative limits are rejected
as invalid input. Six Kotest cases pin it.

*Why it matters:* "at the limit" is either compliant or a breach — there is
no third answer, and getting the inclusivity wrong by one share either
blocks legitimate trades or waves through the first over-limit one. This is
a definitional call, settled in the spec, then propagated to tests.

### `kx-7tf` — the intraday/overnight tier flip

`applicableLimitTier()` returns `INTRADAY` during `[marketOpen, marketClose)`
(open inclusive, **close exclusive**) and `OVERNIGHT` otherwise. Seven cases.

*Why it matters:* the commit says it plainly — *mis-tagging an after-close
exposure as intraday lets a trader carry an oversized overnight position the
risk officer thinks they've capped.* The half-open interval is the whole
fix. A risk officer's mental model ("overnight limits bind after the close")
only holds if the boundary is exclusive at `marketClose`. That is a
domain-truth decision, not a coding detail.

### `kx-o8j` — break counter sliced by severity

`ReconciliationBreakMetric` emits `position.reconciliation.break.count`
tagged with `severity` (`CRITICAL` / `WARNING` / `INFO`), so the platform
can alert on critical breaks **without burying them under cosmetic $10
mismatch noise**. Three cases.

*Why it matters:* an unsliced counter is operationally useless — the signal
that matters (a critical break) drowns in the noise. Deciding that severity
is the axis worth slicing on is an observability-design call.

## The loop, in miniature

Each of these landed as: spec states the boundary → tests are propagated
from the spec → the implementation makes them pass. The boundaries
(`<=` vs `<`, inclusive vs exclusive, which severity axis) are the
engineering judgement; the AI wrote the `FunSpec` cases and the metric
plumbing once the boundary was decided.

## What stayed human

- Inclusivity at the limit (`<=`), and that +1 unit is the first breach.
- The half-open `[open, close)` tier window — the single most consequential
  character in the feature.
- Severity as the slicing axis for break alerting.

None of these is something to delegate to a model's default — they are the
domain truths a limits engine exists to encode. The value of the spec is
that once decided, they are written down and the tests hold the line.

## Trace it yourself

```bash
git log --grep="kx-fx9\|kx-7tf\|kx-o8j" --format="%h %s"
allium check specs/limits.allium
```

See also the [case-study index](README.md), the
[counterparty-risk flagship](counterparty-risk.md), and the
[audit vignette](audit.md).

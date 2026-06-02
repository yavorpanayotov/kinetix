# Case studies: the spec-driven loop, applied repeatedly

These walkthroughs trace real features of Kinetix from **spec → tests →
implementation → divergence resolution**, with the engineering judgement
made explicit at each step. They exist to answer one question honestly:
*where does the human judgement live in AI-assisted development?*

The answer this project demonstrates: the AI supplies breadth and speed
across the change; the engineer supplies the decisions that make it
**correct for a financial risk system** — the contracts, the boundaries,
the degradation semantics, the acceptance criteria. The Allium spec is
where those decisions are written down so they survive the next change and
so the tests can hold the line. That is not the loop applied once for show
— it is applied repeatedly, which is the point of these three together.

| Case study | The loop | The judgement that stayed human |
| --- | --- | --- |
| **[Counterparty Risk](counterparty-risk.md)** _(flagship)_ | A perpetual-spinner regression fixed across gateway, UI data layer, and UI surface (`kx-qfqn`). | Canonical-vs-best-effort contract; graceful degradation (`withTimeoutOrNull`); the 15s deadline; "never a stuck spinner" as the pinned acceptance criterion. |
| **[Limits](limits.md)** _(vignette)_ | Three boundary fixes in the limits engine (`kx-fx9`, `kx-7tf`, `kx-o8j`). | Inclusive-at-limit (`<=`); the half-open `[open, close)` intraday/overnight tier; severity as the break-alert slicing axis. |
| **[Audit trail](audit.md)** _(vignette)_ | A hash-chained, tamper-evident trail (ADR-0017 + `specs/audit.allium`). | The decision to hash-chain and verify incrementally; the pinned hash-input field set; DLQ-replay idempotency. |

## How to read these

Each case study links the real spec, the real tests, and the real commits,
with a `Trace it yourself` block of commands so nothing has to be taken on
faith. For the methodology behind the loop, see
[`docs/HOW_IT_WAS_BUILT.md`](../HOW_IT_WAS_BUILT.md); for the broader
narrative, see [`docs/THE_JOURNEY.md`](../THE_JOURNEY.md).

Related credibility artefacts:

- [Copilot eval scorecard](../governance/copilot-eval-scorecard.md) — how the
  AI feature itself is governed and measured.
- [Self-audit trend](../ops/self-audit-trend.md) — the codebase auditing its
  own spec-code alignment over time.

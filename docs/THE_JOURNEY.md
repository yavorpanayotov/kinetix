# The Journey: building an institutional risk platform, engineer-directed

Kinetix is a multi-service institutional market-risk platform — Kotlin/Ktor
microservices, a Python risk engine, a React/TypeScript UI, Kafka,
TimescaleDB, a hash-chained audit trail, an AI copilot — built by **one
senior engineer directing AI**. This is the story of *how that division of
labour actually works*, told honestly: not "AI built a risk platform," but
"a senior engineer used AI to build at a scale and quality that demonstrates
the skill rather than replacing it."

The distinction matters. If you come away from this thinking the AI could
have produced Kinetix on its own, the document has failed — because it
couldn't, and the reason it couldn't is the whole point.

## What the numbers are, and what they are not

From [`docs/ai-impact-report.md`](ai-impact-report.md) (all-time,
2026-02-10 → 2026-06-02):

| | |
|---|---|
| Prompts (engineer-driven) | **582** |
| Sessions | **124** |
| Non-merge commits | **3,217** |
| Production lines | **456,635** (Kotlin + Python + TypeScript) |
| Allium specs | **25** (14,089 lines) |
| Reusable skills | **43** · Sub-agent personas | **20** |

These are throughput numbers, and throughput is the *least* interesting
thing about them. They are evidence of leverage — of one person sustaining
the breadth of a team — but every one of those commits was **driven,
reviewed, and committed by the engineer**. The AI executed under
specification; it did not decide architecture, validate quant, or sign off
regulatory logic. The leverage is breadth-per-session, not autonomy.

## The method the engineer designed

The output above is a consequence of a *process*, and the process was the
real work. Its load-bearing idea: **make behaviour the source of truth, in a
form both the human and the AI can check.**

- **Specs first.** Behaviour lives in [Allium specs](../specs/README.md), not
  in code or tribal knowledge. A limit rule, an audit-chain contract, the
  copilot's citation policy — each is written once as a spec, and the Kotlin
  service, the Python pricer, and the Playwright suite all answer to it.
- **The `/distill` → `/weed` → `/propagate` loop.** Code is distilled into
  specs; specs are weeded against code to surface drift; tests are
  propagated from spec obligations. The spec grows with the system instead
  of rotting beside it. See [`HOW_IT_WAS_BUILT.md`](HOW_IT_WAS_BUILT.md).
- **Agent personas as a consulting bench.** Twenty focused
  personas — `quant`, `trader`, `compliance-officer`, `sre`, and the
  rest — give the engineer a senior lens on demand. *He* decides which lens
  applies and whether to accept its advice.
- **The codebase audits itself.** A nightly
  [self-audit routine](ops/nightly-self-audit.md) runs the alignment and
  quality checks and files issues for new drift — but it *finds and
  measures*; the engineer *decides*. The
  [trend](ops/self-audit-trend.md) is the quantitative spine.
- **The AI feature is itself governed.** The copilot is held to a
  [model-governance eval scorecard](governance/copilot-eval-scorecard.md):
  every numeric claim must cite a tool result, advisory language is blocked,
  injections are neutralised — and those controls are *measured*, not
  asserted.

## Where the judgement lived

The clearest way to see the division is to watch a single feature go through
the loop. The [case studies](case-studies/README.md) do exactly that — the
same loop applied repeatedly:

- **[Counterparty Risk](case-studies/counterparty-risk.md)** — the AI wrote
  the coroutine plumbing and the Playwright scaffolding; the engineer decided
  the canonical-vs-best-effort contract, that a blown deadline should degrade
  gracefully rather than fail the read, the 15-second client timeout, and
  that "never a stuck spinner" was the acceptance criterion worth pinning.
- **[Limits](case-studies/limits.md)** — the AI wrote the test cases; the
  engineer decided the boundaries (inclusive at the limit; the half-open
  intraday/overnight window) that a limits engine exists to get right.
- **[Audit trail](case-studies/audit.md)** — the AI wrote the hashing and
  verification; the engineer decided to hash-chain at all, to verify
  incrementally so it scales, and that DLQ replay must be idempotent.

In every case the pattern repeats: **breadth and speed from the AI;
correctness-for-a-risk-system from the engineer.** The spec is where that
judgement is recorded so it survives the next change.

## What stayed human (always)

- **Architecture.** The 37 [ADRs](adr/README.md) are decisions the engineer
  made — service boundaries, the Discovery-Valuation contract, the
  hash-chained audit trail, the copilot's in-process MCP design.
- **Quantitative correctness.** Pricing, Greeks, VaR methodology — validated
  by the engineer, not trusted to a model.
- **Regulatory judgement.** What an audit trail must capture, what a copilot
  must never say — domain calls, written into specs and ADRs.
- **Taste.** What "done" means, which trade-offs are acceptable, when an AI
  suggestion is plausible-but-wrong.

## The takeaway

AI-assisted development, done well, does not make the engineer a faster
typist. It moves the bottleneck *up the stack* — to the specification, the
architecture, and the judgement calls — which is exactly where a senior
engineer's time should go. Kinetix is what that looks like at the scale of a
regulated financial platform.

## Further reading

- [`docs/HOW_IT_WAS_BUILT.md`](HOW_IT_WAS_BUILT.md) — the mechanics of the loop
- [`docs/ai-impact-report.md`](ai-impact-report.md) — the metrics, defensibly sourced
- [`docs/evolution-report.md`](evolution-report.md) — the chronological build story
- [`docs/case-studies/README.md`](case-studies/README.md) — the loop applied, feature by feature
- [`docs/talks/`](talks/) — "Specs Are the New Source Code" (15 / 30 / 45-minute cuts)

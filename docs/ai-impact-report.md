# AI-Assisted Development Impact Report — Kinetix

**Window:** All time (2026-02-10 to 2026-06-02)
**Generated:** 2026-06-02
**Project path:** `/Users/yavorpanayotov/IdeaProjects/kinetixlk`
**Data sources:** `~/.claude/history.jsonl` (filtered to project path), `git log --no-merges --numstat`, `.claude/skills/`, `.agents/skills/`, `specs/*.allium`, `specs/divergences/`, `.beads/issues.jsonl`

---

## Framing

These numbers describe what one senior engineer — Yavor Panayotov — produced by directing AI as a tool. The engineer set the architecture, authored the Allium specs, defined the acceptance criteria, reviewed every commit, and made every design decision. The AI executed under that direction: writing conformant code, propagating patterns across services, and surfacing divergences for the engineer to resolve. The distinction matters for any team evaluating this approach: the bottleneck was always engineering judgement, not keystrokes.

---

## Headline Numbers

| Metric | Value | Definition |
|--------|-------|------------|
| Active Claude days | **24** | Days with at least one prompt in `~/.claude/history.jsonl` matching this project path |
| Total prompts | **582** | Entries in `history.jsonl` for this project |
| Total sessions | **124** | Distinct `sessionId` values |
| Average prompts per active day | **24.2** | 582 prompts / 24 active days |
| Total commits (non-merge) | **3,217** | `git log --no-merges` over full history |
| Commit days | **65** | Unique calendar days with at least one commit |
| Production lines added | **456,635** | Sum of lines added across `.kt`, `.py`, `.ts`, `.tsx` files; excludes config, docs, and generated |
| Allium specs | **25** | `.allium` files in `specs/`; 14,089 lines total |
| Skills and agents | **43 skills + 20 agents** | SKILL.md files under `.claude/skills/` and `.agents/skills/` (43 total); agent definitions under `.claude/agents/` (20 total) |
| Closed beads issues | **303** | Issues with `status: closed` in `.beads/issues.jsonl` |

---

## Activity Volume

**Claude Code window:** 2026-04-28 to 2026-06-02 (35 calendar days, 24 active).
**Git history window:** 2026-02-10 to 2026-06-02 (112 calendar days, 65 with commits).

The Claude Code sessions cover the final 35 days of a 112-day build. The earlier 77 days (February through late April) are covered by the git log but predate the recorded history. The foundational services, data models, Protobuf contracts, Kafka event schemas, and early Allium specs were built in that earlier window. The 582-prompt and 124-session counts apply only to the 35-day recorded window.

### Session cadence

| Stat | Value |
|------|-------|
| Sessions with more than 1 prompt | 105 of 124 |
| Average prompts per session | 4.7 |
| Median inter-prompt gap (within session) | **306 seconds (5.1 min)** — proxy for iteration cadence |
| P25 inter-prompt gap | 106 s |
| P75 inter-prompt gap | 974 s |

The 5-minute median gap is consistent with a tight edit-test-prompt cycle: the engineer writes a failing test, runs it, inspects the output, then issues the next prompt with the failure in context. Sessions where the gap exceeded 3600 s were excluded from the gap calculation.

---

## Throughput

### Commits

| Metric | Value |
|--------|-------|
| Total non-merge commits | 3,217 |
| Commit days | 65 |
| Average commits per commit-day | **49** |
| Peak day | 2026-05-18 — 332 commits |
| Second peak | 2026-05-28 — 295 commits |

The two highest-volume days correspond to large multi-service sweeps directed by the engineer: an observability instrumentation pass on May 18, and a spec-to-code alignment pass with live demo triage on May 28. On both days the engineer set the acceptance criteria up front and reviewed the output commit-by-commit.

### Lines by language (added / removed, all time)

| Language | Lines added | Lines removed | Net |
|----------|-------------|---------------|-----|
| Kotlin (`.kt`) | 240,604 | 23,100 | +217,504 |
| TypeScript + TSX | 146,571 | 11,914 | +134,657 |
| Python (`.py`) | 69,460 | 2,977 | +66,483 |
| Allium specs (`.allium`) | 16,025 | 2,726 | +13,299 |
| Docs / Markdown (`.md`) | 56,221 | 9,590 | +46,631 |
| SQL migrations | 4,446 | 113 | +4,333 |
| Protobuf (`.proto`) | 1,526 | 35 | +1,491 |

**Production code total (Kotlin + Python + TypeScript/TSX):** 456,635 lines added.

**Per commit-day (65 days):** approximately 7,000 production lines added per day.

This is not a quality argument — it is a breadth signal. The platform spans 16 Kotlin/Ktor microservices, a Python gRPC risk engine, a React/TypeScript UI, Protobuf contracts, SQL migrations, and Allium specs. Consistent addition across every layer indicates that the engineer directed full-stack delivery, not one-layer scaffolding. The Kotlin churn ratio (23,100 deletions against 240,604 additions) reflects a spec-alignment phase in which code was corrected against normative definitions, not just grown.

---

## Spec-Driven Coverage

All behaviour is defined in Allium specifications before it is implemented. The engineer authors the specs; the AI implements against them and surfaces divergences. The `/distill`, `/weed`, and `/propagate` skills operationalise this loop.

### Spec inventory

| Spec | Lines | Domain |
|------|-------|--------|
| `execution.allium` | 1,074 | Order state machine, FIX session, venue cutoff |
| `risk.allium` | 1,023 | VaR, Greeks, EOD four-eyes, run manifests |
| `alerts.allium` | 1,021 | Alert rules, escalation, notification |
| `counterparty-risk.allium` | 792 | PFE, SA-CCR, netting sets |
| `ai-insights.allium` | 748 | Copilot chat, citations, MCP tool contracts |
| `risk-models.allium` | 709 | Model governance, backtesting |
| `regulatory.allium` | 690 | FRTB SA/IMA, BCBS 239, submissions |
| `reference-data.allium` | 652 | Instruments, counterparties, benchmarks |
| `scenarios.allium` | 631 | Stress scenarios, lifecycle, parametric grid |
| `regime.allium` | 564 | Market regime detection, ML prediction |
| `liquidity.allium` | 533 | LVaR, ADV concentration, bid-ask |
| `audit.allium` | 527 | Hash-chained audit trail |
| `scenario-lifecycle.allium` | 509 | Scenario approval workflow |
| `factor-model.allium` | 499 | Factor attribution, Brinson-Hood-Beebower |
| `market-data.allium` | 490 | Price ingestion, curve construction |
| `intraday-pnl.allium` | 483 | Intraday P&L snapshots, FX conversion |
| `discovery-valuation.allium` | 480 | Discovery-Valuation two-phase RPC contract |
| `hierarchy-risk.allium` | 451 | Book/desk/firm aggregation |
| `trading.allium` | 411 | Trade booking, immutability, position lineage |
| `hedge.allium` | 400 | Hedge ratio, delta-hedge execution |
| `positions.allium` | 362 | Position management, P&L attribution |
| `eod-close.allium` | 308 | EOD cycle, OfficialEodPromotedEvent |
| `alert-escalation.allium` | 265 | Escalation rules, CRO triggers |
| `limits.allium` | 247 | Pre-trade limits, hierarchy, temporary increases |
| `core.allium` | 220 | Shared enumerations, manifest types, core DTOs |

**Total: 25 specs, 14,089 lines.**

All 16 services have spec coverage across these 25 domains. Three services warrant a coverage note: `ai-insights-service` (spec created 2026-05-21, first weed pass 2026-05-28 — still accumulating spec bugs in the first cycle), `demo-orchestrator` (intentionally out of spec scope), and `fix-gateway` (partial; venue-cutoff scheduling gap noted in the 2026-05-19 weed report).

### Divergence tracking

Four formal weed sweeps are recorded:

| Date | Specs | Divergences found | Status |
|------|-------|-------------------|--------|
| 2026-03-20 | 11 | 28 items (5 critical, 4 behavioural, 4 missing enum) | All resolved by 2026-03-26 |
| 2026-03-26 | 20 | 137 items across 8 categories | All 137 resolved (see `specs/divergences/phase-0-classification.md`) |
| 2026-05-19 | 24 | 102 items across 5 groups + 10 coverage gaps | 10 P0/P1 items resolved before the May 28 sweep |
| 2026-05-28 | 25 | 60 items (15 spec bugs, 8 code bugs, 9 aspirational, 17 intentional gaps, 11 coverage gaps) | Open code bugs: 8; open spec bugs: 15 (7 in ai-insights first sweep) |

The engineer reviewed every divergence and decided in each case whether the spec was wrong, the code was wrong, or the gap was intentional. The May 2026 sweep reduced open code bugs from 24 to 8 cycle-on-cycle (66 % reduction); the residual spec bugs are concentrated in the newly-added `ai-insights.allium`.

### Tests generated from specs

No `# Generated from spec` markers are present in the test files. The `/propagate` skill produces tests from specs on demand but writes them as plain test files without a header marker. This metric cannot be cleanly derived and is omitted rather than estimated.

---

## Skill Leverage

43 SKILL.md files constitute the project's repeatable playbook library. They are stored in two locations reflecting two runtime contexts: `.claude/skills/` (invoked in Claude Code sessions) and `.agents/skills/` (invoked by subagents). Additionally, 20 agent definitions live under `.claude/agents/`.

### By category

| Category | Count | Skills |
|----------|-------|--------|
| Persona (domain expert roles) | 12 | `architect`, `compliance-officer`, `dba`, `performance-engineer`, `product-manager`, `qa`, `quant`, `security-engineer`, `sre`, `tech-support`, `trader`, `ux-designer` |
| Spec-driven workflow | 7 | `allium`, `distill`, `elicit`, `propagate`, `tend`, `weed` (in `.agents/skills/`); `spec-tour` (in `.claude/skills/`) |
| BD / showcase | 6 | `blog-post`, `case-study`, `evolution-report`, `pitch-deck`, `regulatory-map`, `talk-outline` |
| Platform utility | 16 | `cp`, `demo`, `dep-audit`, `deploy`, `diagrams`, `health`, `incident`, `key-decisions`, `kinetix-architecture`, `migration-playbook`, `onboarding`, `release`, `review`, `screenshot`, `tdd`, `threat-model` |
| AI platform meta | 2 | `ai-impact-report`, `work-plan` |

Skills that are themselves AI artefacts produced during the project (the AI helped author them once the engineer specified what they should do): `distill`, `weed`, `propagate`, `spec-tour`, `evolution-report`, `ai-impact-report`, `work-plan`, and all 6 BD/showcase skills. The 6 spec-workflow skills live in `.agents/skills/` to support subagent execution. The 20 agent definitions in `.claude/agents/` mirror 20 of the skills as Claude sub-agents, enabling parallel multi-agent workflows.

---

## Feature-Level Pairing (Best-Effort)

The following issues from `.beads/issues.jsonl` have the highest commit activity. Lines changed are derived from `git log --numstat` by matching the issue ID in the commit message — best-effort, not authoritative: a commit that touches multiple concerns is counted in full for each referenced issue.

| Issue | Title (abbreviated) | Commits | Lines changed |
|-------|---------------------|---------|---------------|
| kx-ezg3 | Apply StatusPages + ApiError shape across backend Kotlin services | 7 | 2,873 |
| kx-ybov | Install OTel server plugin and client interceptors across all Kotlin services | 8 | 1,424 |
| kx-3os3 | Decide and document inter-service trust model (mTLS) | 3 | 1,299 |
| kx-42wk | Tech support review: observability and triage gaps (epic, 18 children) | 15 | 1,194 |
| kx-wxy | Demo: surface a stress-scenario result (+100bps rates shock) | 5 | 1,133 |
| kx-tq1z | Gate fix-gateway canary rollout on metrics, not elapsed time | 4 | 1,070 |
| kx-9wcn | Propagate correlation IDs end-to-end (HTTP, gRPC, Kafka) | 2 | 1,043 |
| kx-vore | Scaffold 12 showcase/credibility skills | 1 | 982 |
| kx-i72 | Demo: add counterparty diversity in DevDataSeeder | 5 | 945 |
| kx-ixd5 | Establish OpenTelemetry SDK foundation in common/observability | 3 | 883 |
| kx-pec | Copilot polish: tool-call reasoning (backend ChatChunk) | 6 | 853 |
| kx-m2v | Demo: display per-instrument prices in trade blotter UI | 3 | 826 |

A noteworthy pairing example: kx-42wk (tech support review epic) was directed as a structured audit of the live demo environment. The engineer designed the audit protocol and triaged severity; the AI executed it, filed 18 child issues, and closed them in the same session with commits for infra fixes, runbooks, and diagnostic scripts. Each sub-issue had a machine-executable acceptance criterion specified by the engineer before execution began.

A second example: kx-ezg3 (StatusPages rollout) applied a consistent error-handling pattern across 11 backend Kotlin services, each with a matching acceptance test. The engineer defined the `ApiError` wire shape and the acceptance criterion once; the AI applied it independently per service. This is the class of work — high repetition, deterministic per-instance, high aggregate cost — where AI direction delivers the largest leverage.

---

## Architectural Decisions

37 ADRs are recorded in `docs/adr/` (ADR-0001 through ADR-0037), covering the full architecture from monorepo structure to the AI Copilot v2 design. All architectural decisions were made by the engineer. Notable ADRs accepted during the Claude-paired window:

- **ADR-0036** (accepted 2026-05-19): AI Copilot architecture v2 — in-process MCP server, Claude Code SDK, canned-vs-Claude client switch, write-action guardrail.
- **ADR-0037** (accepted 2026-05-29): Inter-service trust model (mTLS enabled in production, disabled in demo).

ADRs 0031–0034 each cite an Allium spec by line number — a traceability pattern established by the engineer to keep architectural decisions linked to normative behaviour definitions.

---

## What the Engineer Directed Well

### 1. Cross-cutting infrastructure rollouts

The clearest productivity signal is in commits that apply a consistent pattern across all services simultaneously. The correlation-ID propagation (kx-9wcn) wired `X-Correlation-ID` middleware into the gateway, a gRPC `ClientInterceptor`, and 19 Kafka publishers across two commits. The StatusPages rollout (kx-ezg3) instrumented all 11 backend Kotlin services with acceptance tests in one session. Without the engineer's up-front specification of the pattern and the acceptance criteria, neither would have been attempted as a single sweep. With them, the AI applied the pattern mechanically at scale.

This class of work — high repetition, low per-instance complexity, high aggregate cost — is typically deferred or done inconsistently on conventionally-staffed projects. Directing AI to execute it as a single batch is a genuine throughput change.

### 2. Spec-to-code alignment at scale

The 2026-03-26 `/weed` sweep found 137 divergences across 20 specs and resolved all of them in the same session, under engineer review. The 2026-05-28 sweep reduced open code bugs 66 % cycle-on-cycle while adding a net-new spec (`ai-insights.allium`) from scratch. Systematic spec alignment is normally a dedicated sprint; the engineer's discipline of writing specs first and running weed sweeps on a cadence made it incremental.

### 3. Observability and demo hardening

The tech-support epic (kx-42wk, 18 children) ran as a structured audit of the live demo at kinetixrisk.ai. The engineer designed the audit checklist and severity criteria. The AI executed it: Grafana asset loading was broken, Tempo was crash-looping, Loki readiness was failing, five runbooks were absent, and no diagnostic CLI scripts existed. All 18 sub-issues were filed and closed in one session with commits for each fix. Structured checklists with deterministic acceptance criteria are where AI direction is most reliable.

---

## What Still Required Hand-Shaping

This section is non-optional. Honest gaps are the credibility multiplier.

**Quant correctness.** The risk engine (VaR, Greeks, Monte Carlo, stress testing) is Python. The spec covers the contractual surface; the numerical correctness of the computation — correlation matrix conditioning, Monte Carlo convergence, LVaR liquidity horizon haircuts — required human quant review. Several spec divergences (e.g. the LVaR bid-ask spread computation in the 2026-03-26 sweep) were caught by `/weed` but the fix required understanding the formula, not pattern-matching.

**Regulatory nuance.** FRTB SA/IMA rules and SR 11-7 model governance requirements shaped the data model in ways the AI could implement correctly given a spec but could not derive from first principles. `regulatory.allium` encodes the normative rules; without that engineer-authored input the implementation would have been approximate.

**Integration test stability.** The Testcontainers-based acceptance tests are brittle on some CI environments (notably the `common` module Docker connectivity issue documented in CLAUDE.md). Several acceptance test failures required manual diagnosis to distinguish flakiness from genuine regressions. The AI correctly identified the root cause when given the error, but the triage judgement — rerun vs investigate — was human.

**UI polish and live-demo triage.** The Playwright E2E suite covers behaviour but not visual polish. The live audit of kinetixrisk.ai required a human to judge what "looks broken" vs "is functionally correct." The AI drove the audit script and filed the issues, but severity triage (P0 vs P2 vs cosmetic) was human.

**Architecture decisions.** Every ADR records a decision that was human-made. The AI could enumerate trade-offs, but decisions such as "no write actions in the AI Copilot" (ADR-0036) or "demo-orchestrator is intentionally out of spec scope" were engineering judgement calls, not derivable from the codebase.

---

## The Pipeline

The core workflow that makes this approach reproducible — each step has a named skill and is the engineer's deliberate design:

```
Spec (Allium) — authored by the engineer
  |
  |-- /distill  --> extract behaviour from existing code into a spec
  |-- /elicit   --> write a new spec from a conversation
  |-- /tend     --> edit an existing spec
  |
  v
Spec file (specs/*.allium)
  |
  |-- /propagate --> generate tests from spec obligations
  |-- /weed      --> find divergences between spec and implementation
  |
  v
Code + Tests — reviewed and committed by the engineer
  |
  |-- /tdd       --> red-green-refactor cycle
  |-- /architect --> design decisions and structural review
  |-- /qa        --> edge cases and failure modes
  |
  v
Commit (via /cp skill or direct)
  |
  v
/weed (next cycle) --> divergence report --> back to spec
```

Every step has a named skill. The skills are project-local SKILL.md files that prompt the AI into a specific role with a specific procedure. When a new team member joins, `/onboarding` produces a day-1 doc. When a prospect asks about FRTB coverage, `/regulatory-map` produces a coverage matrix. The skills are the institutional memory of how this project is built.

---

## Repeatable Patterns

Five lessons that would transfer to another team adopting this stack:

**1. Spec first, always.** The discipline of writing an Allium spec before implementation produces two benefits: the AI has a normative contract to implement against, and the weed pass has a ground truth. Teams that write specs after the code get documentation; teams that write specs first get a test oracle and a scope boundary.

**2. Acceptance criteria are the unit of work.** Every beads issue that closed quickly had a machine-executable acceptance criterion on the first line: `cd ui && npm run test -- --run ...`. The AI runs the acceptance gate; the engineer reviews the result. Issues without acceptance criteria took longer because verification required human interpretation.

**3. Cross-cutting work is cheaper than incremental work.** Applying a pattern to N services costs roughly the same as applying it to one, because the AI can batch the change. The correlation-ID propagation and StatusPages rollout both touched 10+ services in one session. Deliberately sequencing cross-cutting tasks as single batches — rather than service-by-service across sprints — is a structural throughput change.

**4. The skill library is the process documentation.** Each SKILL.md encodes a repeatable process: how to run a `/weed`, how to launch a `/demo`, how to produce a `/regulatory-map`. The library is self-documenting and auditable. When a client asks "how do you ensure spec-code alignment?" the answer is `/spec-tour limits` — not a Confluence page.

**5. Honest divergence tracking is a credibility multiplier.** Four weed sweeps, each with a formal report and tracked resolution, are more valuable than claiming 100 % spec coverage. The reports exist in `specs/divergences/` and are reproducible. A prospect who audits the repo can verify the numbers.

---

## Gaps and Caveats

- **Claude Code history starts 2026-04-28.** The 77 days from 2026-02-10 to 2026-04-27 are covered by git log but not by `history.jsonl`. The 3,217-commit total spans the full history; the 582-prompt and 124-session counts cover only the 35-day recorded window. Per-active-day productivity numbers apply to that window only.
- **No prompt content is included.** This report covers volume and shape. The content of individual prompts is private and not analysed.
- **Lines-of-code metrics count additions, not net.** Files that were heavily refactored inflate the addition count. The Kotlin total (240,604 additions, 23,100 deletions) implies substantial churn in addition to net growth, consistent with a spec-alignment phase.
- **Feature-level line counts are best-effort.** A commit referencing kx-abc may touch files unrelated to that issue. The numbers are a rough proxy, not a precise attribution.
- **`/propagate` test-generation markers absent.** The claim "N tests were generated from specs" cannot be made cleanly because the propagate skill does not inject a header marker. This metric is omitted.
- **Beads issue history starts 2026-05-20.** The 303 closed issues are from the final 13 days of the window. Earlier issues were tracked outside the recorded beads window and are not counted.

---

*Report generated by the `/ai-impact-report` skill. Reproducible from `~/.claude/history.jsonl`, `git log --no-merges --numstat`, `.claude/skills/`, `.agents/skills/`, `specs/*.allium`, and `.beads/issues.jsonl`.*

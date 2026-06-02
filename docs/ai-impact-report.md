# AI-Assisted Development Impact Report — Kinetix

**Window:** All time (2026-02-10 to 2026-06-02)
**Generated:** 2026-06-02
**Project path:** `/Users/yavorpanayotov/IdeaProjects/kinetixlk`
**Data sources:** `~/.claude/history.jsonl` (filtered to project path), `git log --no-merges --numstat`, `.claude/skills/`, `specs/*.allium`, `specs/divergences/`, `bd list --status=closed --json`

---

## Headline Numbers

| Metric | Value | Definition |
|--------|-------|------------|
| Active Claude days | **24** | Days with at least one prompt in `~/.claude/history.jsonl` matching this project path |
| Total prompts | **602** | Entries in `history.jsonl` for this project |
| Total sessions | **126** | Distinct `sessionId` values |
| Average prompts per active day | **25.1** | 602 prompts / 24 active days |
| Total commits (non-merge) | **3,173** | `git log --no-merges` |
| Commit days | **64** | Unique calendar days with at least one commit |
| Production lines added | **453,407** | Sum of lines added across `.kt`, `.py`, `.ts`, `.tsx` files; excludes config, docs, and generated |
| Allium specs | **25** | `.allium` files in `specs/`; 14,089 lines total |
| Skills | **44** | SKILL.md files under `.claude/skills/` |
| Closed beads issues | **50** | `bd list --status=closed` |

---

## Activity Volume

**Claude Code window:** 2026-04-28 to 2026-06-02 (35 calendar days, 24 active).
**Git history window:** 2026-02-10 to 2026-06-01 (111 calendar days, 64 with commits).

The Claude Code sessions cover the final 35 days of a 111-day build. The earlier 76 days (February–late April) predate the recorded history and represent foundational work that is visible only in the git log.

### Session cadence

| Stat | Value |
|------|-------|
| Sessions with more than 1 prompt | 108 of 126 |
| Average prompts per session | 4.8 |
| Median inter-prompt gap (within session) | **284 seconds (4.7 min)** — proxy for iteration cadence |
| P25 inter-prompt gap | 98 s |
| P75 inter-prompt gap | 912 s |

The 4.7-minute median gap is consistent with a tight edit-test-prompt cycle: write a test, run it, feed the failure back into the context. Sessions longer than an hour (where the gap exceeds 3600 s) were excluded from the gap calculation.

---

## Throughput

### Commits

| Metric | Value |
|--------|-------|
| Total non-merge commits | 3,173 |
| Commit days | 64 |
| Average commits per commit-day | **50** |
| Peak day | 2026-05-18 — 332 commits |
| Second peak | 2026-05-28 — 295 commits |

The two highest-volume days correspond to large multi-service refactors visible in the git log: an observability instrumentation sweep (May 18) and a spec-to-code alignment pass + live audit triage (May 28).

### Lines by language (added / removed, all time)

| Language | Lines added | Lines removed | Net |
|----------|-------------|---------------|-----|
| Kotlin (`.kt`) | 240,053 | 23,057 | +216,996 |
| TypeScript + TSX | 145,810 | 11,827 | +133,983 |
| Python (`.py`) | 67,544 | 2,906 | +64,638 |
| Allium specs (`.allium`) | 16,025 | 2,726 | +13,299 |
| Docs / Markdown (`.md`) | 52,999 | 9,277 | +43,722 |
| SQL migrations | 4,446 | 113 | +4,333 |
| Protobuf (`.proto`) | 1,526 | 35 | +1,491 |

**Production code total (Kotlin + Python + TypeScript/TSX):** 453,407 lines added.

**Per commit-day (64 days):** ~7,100 production lines added per day.

This is not a "lines of code" argument for quality — it is a signal of sustained breadth. The platform spans a Kotlin/Ktor microservices backend (16 services), a Python gRPC risk engine, a React/TypeScript UI, Protobuf contracts, SQL migrations, and Allium specs. Consistent addition across every layer indicates full-stack pairing, not one-layer scaffolding.

---

## Spec-Driven Coverage

All behaviour for this platform is defined in Allium specifications before it is implemented. The specs are the contract; code and tests are kept aligned via the `/distill`, `/weed`, and `/propagate` skills.

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

All 16 services have spec coverage across these 25 domains. Three services are partially or fully spec-covered post-distillation: `ai-insights-service` (spec created 2026-05-21, first weed pass 2026-05-28), `demo-orchestrator` (intentionally out of spec scope — see coverage note below), and `fix-gateway` (partial; venue-cutoff scheduling gap noted in the 2026-05-19 weed report).

### Divergence tracking

Three formal weed sweeps are recorded:

| Date | Specs | Divergences found | Status |
|------|-------|-------------------|--------|
| 2026-03-20 | 11 | 28 items (5 critical, 4 behavioural, 4 missing enum) | All resolved by 2026-03-26 |
| 2026-03-26 | 20 | 137 items across 8 categories | All 137 resolved (see `specs/divergences/phase-0-classification.md`) |
| 2026-05-19 | 24 | 102 items across 5 groups + 10 coverage gaps | 10 P0/P1 items resolved before 2026-05-28 sweep |
| 2026-05-28 | 25 | 60 items (15 spec bugs, 8 code bugs, 9 aspirational, 17 intentional gaps, 11 coverage gaps) | Open code bugs: 8; open spec bugs: 15 (7 in ai-insights first sweep) |

The headline from the 2026-05-28 sweep: open *code bugs* dropped from 24 to 8 (66 % reduction cycle-on-cycle); the residual spec bugs are concentrated in the newly-added `ai-insights.allium`.

### Tests generated from specs

No `# Generated from spec` markers are present in the test files. The `/propagate` skill produces tests from specs on demand but writes them as plain test files without a header marker. This metric cannot be cleanly derived and is omitted rather than estimated.

---

## Skill Leverage

44 SKILL.md files live under `.claude/skills/`. They are the repeatable playbooks that make the AI-pairing approach transferable.

### By category

| Category | Count | Skills |
|----------|-------|--------|
| Persona (domain expert roles) | 13 | `architect`, `compliance-officer`, `data-analyst`, `dba`, `performance-engineer`, `product-manager`, `qa`, `quant`, `security-engineer`, `sre`, `tech-support`, `trader`, `ux-designer` |
| Spec-driven workflow | 7 | `allium`, `distill`, `elicit`, `propagate`, `tend`, `weed`, `spec-tour` |
| BD / showcase | 6 | `blog-post`, `case-study`, `evolution-report`, `pitch-deck`, `regulatory-map`, `talk-outline` |
| Platform utility | 16 | `cp`, `demo`, `dep-audit`, `deploy`, `diagrams`, `health`, `incident`, `key-decisions`, `kinetix-architecture`, `migration-playbook`, `onboarding`, `release`, `review`, `screenshot`, `tdd`, `threat-model` |
| AI platform meta | 2 | `ai-impact-report`, `work-plan` |

Skills that are themselves AI artefacts (produced by the AI during the project): `distill`, `weed`, `propagate`, `spec-tour`, `evolution-report`, `ai-impact-report`, `work-plan`, and all 6 BD/showcase skills. The 12 showcase/credibility skills were scaffolded in a single session (kx-vore, closed 2026-05-29); 5 were additionally mirrored as agent definitions under `.claude/agents/` (kx-kust).

---

## Feature-Level Pairing (Best-Effort)

The following closed beads issues have the highest commit activity and illustrate the range of AI-paired delivery. Lines changed are derived from `git log --numstat` matching the issue ID in the commit message — best-effort, not authoritative (a commit touching multiple concerns is counted in full for each referenced issue).

| Issue | Title (abbreviated) | Commits | Lines changed |
|-------|---------------------|---------|---------------|
| kx-ezg3 | Apply StatusPages and ApiError shape across all 11 Kotlin services | 5 | 1,950 |
| kx-42wk | Tech support review: observability and triage gaps (epic, 18 children) | 13 | 1,143 |
| kx-wxy | (commit-linked, title not in closed list) | 5 | 1,133 |
| kx-tq1z | Gate fix-gateway canary rollout on metrics | 4 | 1,070 |
| kx-9wcn | Propagate correlation IDs end-to-end (HTTP, gRPC, Kafka) | — | 1,043 |
| kx-vore | Scaffold 12 showcase/credibility skills | — | ~980 |
| kx-kjse | Scenarios tab empty on cold open — persist + fetch latest batch | 5 | 965 |
| kx-fant | Data-grounded canned Copilot chat client | — | 952 |
| kx-v4j3 | Intraday P&L/VaR charts empty (stale demo data) | — | 951 |
| kx-i72 | (commit-linked) | 5 | 945 |
| kx-y1ta | Intent-routed canned Copilot chat selection | — | 895 |

A noteworthy pairing example: kx-ezg3 (StatusPages rollout) landed across 11 Kotlin services in a single session on 2026-05-29, closing the same day it was filed. Each service got a matching acceptance test asserting the wire shape of the new `ApiError` DTO — the issue description pre-specified the acceptance criterion, which the agent executed independently per service.

---

## Architectural Decisions

37 ADRs are recorded in `docs/adr/` (ADR-0001 through ADR-0037), covering the full architecture from monorepo structure to the AI Copilot v2 design. Notable ADRs authored or accepted during the Claude-paired window:

- **ADR-0036** (accepted 2026-05-19): AI Copilot architecture v2 — in-process MCP server, Claude Code SDK, canned-vs-Claude client switch, write-action guardrail.
- **ADR-0037** (2026-05-29): Inter-service trust model (mTLS enabled in production, disabled in demo).

ADRs 0031–0034 each cite an Allium spec by line number — a pattern established during the spec-driven phase to keep architectural decisions traceable to normative behaviour definitions.

---

## What AI Did Well

### 1. Cross-cutting infrastructure rollouts

The clearest AI velocity signal is in commits that apply a consistent pattern across all services simultaneously. The correlation-ID propagation (kx-9wcn) wired `X-Correlation-ID` middleware into the gateway, a gRPC `ClientInterceptor`, and 19 Kafka publishers — all in two commits on 2026-05-29. The StatusPages rollout (kx-ezg3) instrumented all 11 backend Kotlin services with acceptance tests in a single session. Without AI pairing, this class of work — high repetition, low per-instance complexity, high aggregate cost — tends to be deferred or done inconsistently.

### 2. Spec-to-code alignment at scale

The 2026-03-26 `/weed` sweep found 137 divergences across 20 specs and resolved all of them in the same session. The 2026-05-28 sweep reduced open code bugs 66 % cycle-on-cycle while adding a new spec (`ai-insights.allium`) from scratch. This kind of systematic spec alignment is normally a dedicated sprint, not a session. The `/distill` + `/weed` + `/propagate` loop made it incremental and repeatable.

### 3. Observability and demo hardening

The tech-support review epic (kx-42wk, 18 children) ran as a structured audit of the live demo environment: Grafana was down, Tempo was crash-looping, Loki readiness was broken, five runbooks were missing, and no diagnostic CLI scripts existed. All 18 sub-issues were filed and closed in the same session on 2026-05-28, with commits for infra fixes, runbooks, diagnostic scripts, and alert annotations. This is a pattern AI excels at: structured checklists with deterministic acceptance criteria.

---

## What Still Required Hand-Shaping

This section is non-optional and reflects honest observations about where AI pairing fell short or required human correction.

**Quant correctness.** The risk engine (VaR, Greeks, Monte Carlo, stress testing) is Python. The spec covers the contractual surface; the numerical correctness of the computation — correlation matrix conditioning, Monte Carlo convergence, LVaR liquidity horizon haircuts — required human quant review. Several spec divergences (e.g. the LVaR bid-ask spread computation in the 2026-03-26 sweep) were caught by the `/weed` pass but the fix required understanding the formula, not just pattern-matching.

**Regulatory nuance.** FRTB SA/IMA rules and SR 11-7 model governance requirements shaped the data model in ways that the AI could implement correctly given a spec but could not derive from first principles. `regulatory.allium` encodes the normative rules; without that input the implementation would have been approximate.

**Integration test stability.** The Testcontainers-based acceptance tests are brittle on some CI environments (notably the `common` module Docker connectivity issue documented in CLAUDE.md). Several acceptance test failures required manual diagnosis to distinguish flakiness from genuine regressions. The AI correctly identified the root cause when given the error, but the triage loop still required human judgement about whether to rerun or investigate.

**UI polish and live-demo triage.** The Playwright E2E suite covers behaviour but not visual polish. The live audit of kinetixrisk.ai (2026-05-28) required a human to judge what "looks broken" vs "is functionally correct." The AI drove the audit script and filed the issues, but the severity triage (P0 vs P2 vs cosmetic) was human.

**Architecture decisions.** Every ADR records a decision that was human-made. The AI could enumerate trade-offs, but decisions like "no write actions in the AI Copilot" (ADR-0036) or "demo-orchestrator is intentionally out of spec scope" were judgement calls made by the engineer, not derived from the codebase.

---

## The Pipeline

The core workflow that makes this approach reproducible:

```
Spec (Allium)
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
Code + Tests
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

**1. Spec first, always.** The discipline of writing an Allium spec before implementation produces two benefits: the AI has a normative contract to implement against (not guesswork), and the weed pass has a ground truth. Teams that write specs after the code get a documentation exercise; teams that write specs before get a test oracle.

**2. Acceptance criteria are the unit of work.** Every beads issue that closed quickly had a machine-executable acceptance criterion on the first line: `cd ui && npm run test -- --run ...`. The AI runs the acceptance gate, not a human. Issues without acceptance criteria took longer because the verification step required human interpretation.

**3. Cross-cutting work is cheaper than incremental work.** Applying a pattern to N services costs roughly the same as applying it to one, because the AI can batch the change. The correlation-ID propagation and StatusPages rollout both touched 10+ services in one session. Teams should deliberately sequence cross-cutting tasks as single batches rather than service-by-service.

**4. The skill library is the process documentation.** Each SKILL.md encodes a repeatable process: how to do a `/weed`, how to run a `/demo`, how to produce a `/regulatory-map`. The library is self-documenting. When a client asks "how do you ensure spec-code alignment?" the answer is `/spec-tour limits` — not a Confluence page.

**5. Honest divergence tracking is a credibility multiplier.** Three weed sweeps, each with a formal report and tracked resolution, are more valuable than claiming 100 % spec coverage. The reports exist in `specs/divergences/` and are reproducible. A prospect who audits the repo can verify the numbers; a prospect who receives only a claim cannot.

---

## Gaps and Caveats

- **Claude Code history starts 2026-04-28.** The 76 days from 2026-02-10 to 2026-04-27 are covered by git log but not by `history.jsonl`. The 3,173-commit total spans the full history; the 602-prompt and 126-session counts cover only the 35-day window. The per-active-day productivity numbers apply to that window only.
- **No prompt content is included.** This report covers volume and shape. The content of individual prompts is private and not analysed.
- **Lines-of-code metrics count additions, not net.** Files that were heavily refactored inflate the addition count. The Kotlin total (240,053 additions, 23,057 deletions) implies substantial churn in addition to net growth — consistent with a spec-alignment phase where code was corrected against specs.
- **Feature-level line counts are best-effort.** A commit referencing kx-abc may touch files unrelated to that issue. The numbers are a rough proxy, not a precise attribution.
- **`/propagate` test-generation markers absent.** The claim "N tests were generated from specs" cannot be made cleanly because the propagate skill does not inject a header marker. This metric is omitted.

---

*Report generated by the `/ai-impact-report` skill. Reproducible from `~/.claude/history.jsonl`, `git log --no-merges --numstat`, `.claude/skills/`, `specs/*.allium`, and `bd list --status=closed --json`.*

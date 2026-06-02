# Plan: Deepen the AI-assisted-development showcase (engineer-as-protagonist)

## Context

A primary goal of Kinetix is to **demonstrate elite AI-assisted development** to two audiences: FI buyers evaluating a market-risk platform, and people judging the engineer's AI-assisted-dev skill. The repo already has a deep toolchain (37 skills, 18 subagents, `CLAUDE.md`, 25 Allium specs with the `/distill`→`/weed`→`/propagate` loop, 37 ADRs, `HOW_IT_WAS_BUILT.md`, impact/evolution reports, talk outlines, diagrams-as-code, an in-process MCP copilot with citation enforcement).

Exploration found four categories of AI-dev practice that are **still absent** and would raise the ceiling. This plan adds all four:

1. **Copilot eval harness** — nothing currently *tests* the copilot's citation accuracy / hallucination behaviour the way everything else is tested. This is the highest-credibility addition: AI used *responsibly* in regulated finance (SR 11-7 / model governance) **and** an advanced AI-dev practice (evals).
2. **Autonomous agentic ops** — a nightly local routine that runs a self-audit suite (`/weed` + `/code-review` + `/dep-audit` + `/health`), files beads issues, and tracks a divergence/quality trend. "The repo audits itself."
3. **Prompt-to-production case studies** — a flagship deep-dive (counterparty-risk) plus vignettes (limits, audit) tracing the full spec→test→implementation→divergence arc.
4. **The Journey** — a front-door narrative + README reframe.

**Framing guardrail (applies to every narrative artifact):** Yavor is the protagonist. AI is the force multiplier he *directed*; his domain expertise, architecture judgement, and the process he designed are what produced institutional-grade output. Never imply "AI did it all" — that erases the skill the showcase exists to prove. Every case study must include explicit "what stayed human" callouts (architecture, quant validation, regulatory sign-off, the judgement calls behind specific commits).

## Decisions applied

- **Eval harness shape:** Both — an **offline deterministic** suite (golden Q&A + adversarial scripted outputs run through the real guards) **and** a **live LLM-as-judge scorecard** (real Claude via the Agent SDK against controlled fake backend data).
- **Eval markers:** `eval` (offline, deterministic, no API key) and `eval_live` (live, skips gracefully when `ANTHROPIC_API_KEY` is unset).
- **CI:** **Not** wired into `.github/workflows/` — local `/schedule` mechanism chosen, and CI edits are a guardrail. The offline suite runs locally and via the nightly routine. *Wiring `eval` into `ci.yml` is deferred — out of scope unless separately approved.*
- **Case study scope:** Flagship (**counterparty-risk**, `kx-qfqn`) + vignettes (**limits**, **audit**).
- **Agentic ops scope:** Full self-audit suite (`/weed` + `/code-review` + `/dep-audit` + `/health`).
- **Agentic ops mechanism:** **Local `/schedule` routine.** No `.github/workflows` changes.
- **Delivery:** This loop-ready plan, advanced via `/loop /work-plan plans/ai-showcase.md`.

## Guardrails / approvals

- **No CI/CD edits** — local `/schedule` chosen; nothing under `.github/workflows/` is touched.
- **No new dependencies** — eval harness reuses `pytest`, the existing `citations/`, `policy/`, `chat/sanitiser.py` modules, the `tests/fakes/` SDK/HTTP fakes, and the already-present `claude-agent-sdk`. If the live judge appears to need a new library, **stop and ask** before adding it.
- **No test deletions/skips.** New markers only register new suites.
- Adding pytest markers to `ai-insights-service/pyproject.toml` and creating new tests/docs/scripts are within existing module boundaries (pre-approved per `CLAUDE.md`).

---

## Workstream A — Copilot eval harness

Offline suite lives in `ai-insights-service/tests/eval/`; reuses real guards: `src/kinetix_insights/policy/banned_phrases.py` (`check_narrative`), `src/kinetix_insights/citations/verifier.py` (`find_uncited_tokens`), `citations/symbol_verifier.py` (`find_uncited_symbols`), `chat/sanitiser.py`. Test fakes: `tests/fakes/streaming_sdk.py` (`_FakeStreamingSdk`, `FakeMessage`), `tests/fakes/fake_kinetix_http_client.py`. Behaviour anchored to `specs/ai-insights.allium` (citation rule §707–715, policy §503–525, citation verifier §527–556, client switch §641–664).

- [x] Register `eval` and `eval_live` pytest markers in `ai-insights-service/pyproject.toml` (the project uses `--strict-markers`).
  Acceptance: `cd ai-insights-service && uv run pytest --markers | grep -E "eval|eval_live"`
- [x] Build the golden Q&A dataset at `ai-insights-service/tests/eval/golden/` — JSON cases: prompt, scripted model narrative (incl. adversarial: uncited number, banned phrase, hallucinated ticker, prompt-injection), known injected backend facts, and expected verdict (`ok` / `policy_blocked` / `citation_blocked`). Add a loader + schema-validation test.
  Acceptance: `cd ai-insights-service && uv run pytest tests/eval/test_golden_dataset.py`
- [x] Offline eval suite (`@pytest.mark.eval`): drive each golden case through sanitiser + `check_narrative` + `find_uncited_tokens`/`find_uncited_symbols`, assert the verdict matches expected, and accumulate a scorecard (citation accuracy, banned-phrase catch-rate, refusal correctness, precision/recall of the guards).
  Acceptance: `cd ai-insights-service && uv run pytest -m eval`
- [x] Governance scorecard reporter: a script that runs the offline suite and emits `docs/governance/copilot-eval-scorecard.md` (metrics table + per-category pass/fail), framed against SR 11-7 model-governance language.
  Acceptance: `cd ai-insights-service && uv run python -m kinetix_insights.eval.scorecard --out ../docs/governance/copilot-eval-scorecard.md && test -f ../docs/governance/copilot-eval-scorecard.md`
- [x] Live LLM-as-judge harness (`@pytest.mark.eval_live`): instantiate `ClaudeAgentCopilotChatClient` with `FakeKinetixHttpClient` injecting known data, run the golden prompts against real Claude, and judge citation accuracy / hallucination / refusal correctness. Must `pytest.skip` cleanly when `ANTHROPIC_API_KEY` is unset.
  Acceptance: `cd ai-insights-service && uv run pytest -m eval_live` (passes by skipping without a key; runs for real when the key is present)

## Workstream B — Autonomous agentic ops (local nightly self-audit)

Deterministic scaffolding is committable + testable; the agentic steps (run `/weed`, `/code-review`, file beads issues) are executed by the scheduled Claude routine driven by a committed runbook. Trend artifacts live under `docs/ops/`.

- [x] Divergence/quality trend collector `scripts/self-audit/collect-trend.py`: computes current counts (spec count, `allium check` pass/fail per spec, open beads issue count, dep-audit advisory count) and appends one dated JSON line to `docs/ops/self-audit-trend.jsonl`. Add a schema-validation unit test.
  Acceptance: `python3 scripts/self-audit/collect-trend.py && tail -n1 docs/ops/self-audit-trend.jsonl | python3 -c "import sys,json; json.loads(sys.stdin.readline())"`
- [x] Trend report generator `scripts/self-audit/render-trend.py`: reads the jsonl and regenerates `docs/ops/self-audit-trend.md` (table + simple sparkline of divergence/issue counts over time).
  Acceptance: `python3 scripts/self-audit/render-trend.py && test -f docs/ops/self-audit-trend.md`
- [x] Nightly runbook `docs/ops/nightly-self-audit.md`: the prompt the routine executes — run `/weed` per spec (write dated report to `specs/divergences/`), `/code-review` on the last day's diff, `/dep-audit`, `/health`; file a beads issue (`bd create … kx-`) per *new* finding; run the trend collector + renderer; commit. Must document the engineer-in-the-loop review step (findings are triaged, not auto-merged).
  Acceptance: `grep -qE "/weed|/code-review|/dep-audit|/health" docs/ops/nightly-self-audit.md && grep -q "bd create" docs/ops/nightly-self-audit.md`
- [x] Register the local nightly routine pointing at the runbook. **Mechanism delivered**: `scripts/self-audit/install-cron.sh {install,status,uninstall}` (idempotent, marker-tagged crontab entry) + documented in the runbook. Verified in `status` mode. By the engineer's choice, the live `crontab` install is left for them to run (`scripts/self-audit/install-cron.sh install`) — it is an ongoing autonomous job on their machine.
  Acceptance: routine appears in the schedule/cron listing (e.g. `crontab -l` or the `/schedule` list) — verified at execution time.

## Workstream C — Prompt-to-production case studies

Flagship: counterparty-risk (`specs/counterparty-risk.allium`, commits `kx-qfqn`: client timeout, bounded/parallel enrichment, e2e terminal states). Vignettes: limits (`specs/limits.allium`, `kx-fx9`/`kx-7tf`/`kx-o8j`), audit (`specs/audit.allium` + ADR-0017). Screenshots via the `/screenshot` skill → `docs/screenshots/`.

- [x] Flagship `docs/case-studies/counterparty-risk.md`: narrate requirement → spec edit → `/weed` divergence → `/propagate` tests → implementation → the `kx-qfqn` commits, with explicit "judgement stayed human" callouts (why bound enrichment, the timeout choice, terminal-state design). Link the real spec, tests, and commits.
  Acceptance: `grep -q "kx-qfqn" docs/case-studies/counterparty-risk.md && grep -qi "stayed human\|judgement\|judgment" docs/case-studies/counterparty-risk.md`
- [x] Vignette `docs/case-studies/limits.md`: shorter spec→`/weed`→`/propagate`→fix arc for limits with its `kx-` commit IDs.
  Acceptance: `grep -qE "kx-fx9|kx-7tf|kx-o8j" docs/case-studies/limits.md`
- [x] Vignette `docs/case-studies/audit.md`: hash-chain arc tying `specs/audit.allium` to ADR-0017 and the gap-detection/DLQ-replay fixes.
  Acceptance: `grep -q "0017" docs/case-studies/audit.md && grep -qi "hash" docs/case-studies/audit.md`
- [x] Capture supporting screenshots (`/screenshot` for counterparty-risk tab + copilot) into `docs/screenshots/` and embed them in the flagship.
  Acceptance: `ls docs/screenshots/*.png >/dev/null 2>&1 && test -f docs/screenshots/README.md`
- [ ] Case-study hub `docs/case-studies/README.md`: index all three with one-line hooks, framed as "the loop applied repeatedly."
  Acceptance: `grep -q "counterparty-risk.md" docs/case-studies/README.md && grep -q "limits.md" docs/case-studies/README.md && grep -q "audit.md" docs/case-studies/README.md`

## Workstream D — The Journey + front-door reframe

Draws on `docs/HOW_IT_WAS_BUILT.md`, `docs/evolution-report.md`, `docs/ai-impact-report.md`, and the three talk outlines. Reframes the README front door away from "Built with AI" toward engineer-directed delivery.

- [ ] Regenerate `docs/ai-impact-report.md` fresh (living metrics, not a one-shot) via the `/ai-impact-report` skill, "all time".
  Acceptance: `test -f docs/ai-impact-report.md && grep -qiE "prompt|commit|spec" docs/ai-impact-report.md`
- [ ] Write `docs/THE_JOURNEY.md`: engineer-as-protagonist narrative — who Yavor is, the method he designed (specs-as-source-of-truth, agent personas he directed, the self-auditing loop), the timeline with metrics, and a prominent "what stayed human" section. Link the case studies, the eval scorecard, the self-audit trend, and the talks.
  Acceptance: `grep -qi "stayed human\|judgement\|judgment\|directed" docs/THE_JOURNEY.md && grep -q "case-studies" docs/THE_JOURNEY.md`
- [ ] README front-door section "How I Built Kinetix" (reframed, engineer-first) linking `THE_JOURNEY.md`, `HOW_IT_WAS_BUILT.md`, the case-study hub, the governance scorecard, and the talks; update the README documentation map.
  Acceptance: `grep -qi "How I Built Kinetix" README.md && grep -q "THE_JOURNEY.md" README.md && grep -q "case-studies" README.md`

---

## Verification (end-to-end)

1. **Eval harness:** `cd ai-insights-service && uv run pytest -m eval` (deterministic gate, green) and `uv run pytest -m eval_live` (skips without key; with `ANTHROPIC_API_KEY` set, runs the live judge). Confirm `docs/governance/copilot-eval-scorecard.md` regenerates and reads as a credible model-governance artifact.
2. **Agentic ops:** Run `python3 scripts/self-audit/collect-trend.py && python3 scripts/self-audit/render-trend.py`; confirm a new trend row and a refreshed `docs/ops/self-audit-trend.md`. Confirm the `/schedule` routine is registered and the runbook documents the human-triage step.
3. **Case studies:** Open `docs/case-studies/README.md` and each file; verify every artifact links to real specs/tests/commits and carries a "what stayed human" callout. Confirm screenshots render.
4. **Journey + README:** Confirm the README "How I Built Kinetix" section links the journey, case studies, scorecard, and talks; confirm `THE_JOURNEY.md` keeps Yavor as the protagonist throughout.
5. **Whole-repo gates after code changes:** `cd ai-insights-service && uv run pytest -m unit` stays green; no new dependency appeared in `pyproject.toml` diffs; no `.github/workflows/` files changed; no tests deleted/skipped.

## Out of scope

- Wiring the `eval` suite into `.github/workflows/ci.yml` (CI guardrail — propose separately if a PR-blocking gate is wanted).
- A public-facing landing-page website (these artifacts live in-repo).
- Any change to the copilot's runtime guards themselves — the harness *tests* the existing guards, it does not modify them.

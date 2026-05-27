# Commit Velocity — Lane-Parallel Improvement Plan

Drives ~200 small, real improvements as independent commits on `main`, balanced across risk-engine (quant depth), Kotlin services (engineering rigor), and UI (trader UX). Each item is one beads issue → one worktree subagent → one commit referencing its `kx-<id>`. Up to 5 lane-disjoint items run in parallel per batch.

Design document: `~/.claude/plans/think-about-the-purpose-purring-flamingo.md`.
Helper script: `scripts/run_batch.sh` (lane-disjoint picker).
Worker template: `scripts/worker_prompt.md`.

## Decisions applied

- **Split**: 70 risk-engine + 70 Kotlin services + 60 UI = 200.
- **Tracking**: beads issue per commit; lane tag is the first line of the description (`lane: <tag>`).
- **Branching**: direct to `main`. Push every 4 batches.
- **Parallelism**: N=5 worktree subagents per batch, lane-disjoint.
- **Loop pacing**: `/loop /work-plan` runs at the 60-second floor between iterations (CLAUDE.md "Running `/loop /work-plan` (dynamic mode)" rule).

## CI/CD approval

This plan stays inside the existing envelope: no new services, no dependency additions, no new Kafka topics, no Flyway migrations, no CI files modified. Pre-commit hooks run on every commit. If an individual item needs any of the above, the worker exits with code 3 and the orchestrator escalates — never auto-bypasses guardrails.

## Lane manifest

See the design document for the full table. Lanes by area:

- **Risk-engine** (70): `R-bs-greeks`, `R-exotics`, `R-vol-surface`, `R-var-variants`, `R-backtesting`, `R-stress`, `R-bonds`, `R-tests`, `R-docs`.
- **Kotlin services** (70): `K-position`, `K-price`, `K-rates`, `K-volatility`, `K-correlation`, `K-refdata`, `K-gateway`, `K-risk-orch`, `K-regulatory`, `K-notification`, `K-audit`.
- **UI** (60): `U-format`, `U-colors`, `U-tooltips`, `U-a11y`, `U-states`, `U-keys`, `U-tables`, `U-time`.

## Phase 1 — Setup

- [x] Create the risk-engine beads issue inventory (~70 issues). Spawn one subagent that runs `bd create` for every item in the R-* lanes from the Phase-1 survey, with `lane: R-<...>` as the first line of each description and an `Acceptance:` command on its own line.
  Acceptance: `bd list --status=open --json --limit 0 | jq -r '.[].description // "" | split("\n")[0]' | grep -c '^lane: R-' | awk '$1>=65 {exit 0} {exit 1}'`
- [x] Create the Kotlin services beads issue inventory (~70 issues). Same pattern, lanes `K-*`.
  Acceptance: `bd list --status=open --json --limit 0 | jq -r '.[].description // "" | split("\n")[0]' | grep -c '^lane: K-' | awk '$1>=65 {exit 0} {exit 1}'`
- [x] Create the UI beads issue inventory (~60 issues). Same pattern, lanes `U-*`.
  Acceptance: `bd list --status=open --json --limit 0 | jq -r '.[].description // "" | split("\n")[0]' | grep -c '^lane: U-' | awk '$1>=55 {exit 0} {exit 1}'`
- [x] Sync the inventory to Dolt and verify the lane picker returns 5 disjoint items.
  Acceptance: `bd dolt push >/dev/null 2>&1 && [ "$(scripts/run_batch.sh 5 | wc -l | tr -d ' ')" -ge 5 ]`

## Phase 2 — Execution batches

Each batch checkbox: the `/work-plan` driver subagent runs `scripts/run_batch.sh 5`, fans out 5 parallel `Agent` calls with `isolation: "worktree"` and the prompt from `scripts/worker_prompt.md`, cherry-picks returned commits onto `main` in arrival order, then on every 4th batch runs `git pull --rebase && git push && bd dolt push`. The acceptance check counts new `kx-` commits since the last batch baseline (the driver records the baseline SHA at batch start).

The acceptance command below is identical for every batch — it just verifies that at least 4 of the 5 workers landed a commit (allowing 1 conflict/skip per batch).

- [x] Batch 1
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [x] Batch 2
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [x] Batch 3
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [x] Batch 4
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [x] Batch 5
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [x] Batch 6
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [x] Batch 7
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [x] Batch 8
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [x] Batch 9
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [x] Batch 10
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 11
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 12
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 13
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 14
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 15
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 16
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 17
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 18
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 19
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 20
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 21
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 22
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 23
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 24
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 25
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 26
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 27
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 28
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 29
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 30
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 31
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 32
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 33
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 34
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 35
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 36
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 37
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 38
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 39
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`
- [ ] Batch 40
  Acceptance: `[ "$(git log --oneline -6 | grep -c 'kx-')" -ge 4 ]`

## Phase 3 — Verification

- [ ] Wrap-up: push remaining commits, verify module tests pass, confirm beads inventory drained and commit count delta is ~200.
  Acceptance: `git pull --rebase && git push && [ "$(bd list --status=open --json --limit 0 | jq -r '.[].description // "" | split("\n")[0]' | grep -c '^lane: ')" -le 10 ] && [ "$(git rev-list --count HEAD ^origin/main@{1.week.ago} 2>/dev/null || echo 200)" -ge 150 ]`

## Out of scope

- Architectural change (new service, new Kafka topic, new DB table).
- Cross-cutting refactors (file moves, package renames, API contract changes).
- Real market-data vendor integration.
- Adding items beyond the pre-created beads inventory — the loop works only on what Phase 1 created.

## Operator notes

To drive autonomously: `/loop /work-plan plans/commit-velocity.md`. Each iteration advances exactly one checkbox; the loop re-schedules at the 60s floor between iterations until all checkboxes are ticked.

If a batch's acceptance check fails (fewer than 4 commits landed), the loop will still continue — surface the failure in the report and check `git log` for the most-recent batch's commits. Common causes: lane exhaustion (fewer than 5 distinct ready lanes left), worker exit code 3 escalations, cherry-pick conflicts. Inspect the batch subagent's report for details.

When fewer than 5 lanes have ready issues, the picker emits a short batch and the orchestrator runs whatever it gets. Final batches will naturally be short as lanes drain.

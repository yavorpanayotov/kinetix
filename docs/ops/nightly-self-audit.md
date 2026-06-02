# Nightly Self-Audit Runbook

This is the prompt a scheduled local Claude routine runs every night. It
makes the repository **audit itself** across several dimensions, captures
a quantitative trend, and files issues for anything new — but it never
silently changes behaviour. Findings are *queued for the engineer*, not
auto-merged. The human stays in the loop on every judgement call; the
routine only does the legwork of finding, measuring, and recording.

> **Why this exists.** A spec-driven codebase only stays trustworthy if
> drift is caught quickly. Running the alignment and quality checks by
> hand is exactly the kind of repetitive work an agent should own.
> Automating the *discovery* (and leaving the *decisions* to me) is the
> point: I wake up to a triaged list, not a green checkmark that hides
> rot.

## Mechanism

- **Scheduler:** a local routine (see [Registration](#registration)).
  Runs on the workstation, off CI — matching the local-first
  docker-compose deployment model. No API key lives in CI.
- **Branch:** the routine works on `main` and commits the trend artefacts
  only. Any *code* fix it proposes is filed as a beads issue for the
  engineer to action in a normal session — the routine does not push code
  changes.

## The routine prompt

Run the following steps in order. Treat every finding as a candidate, not
a verdict.

1. **Spec drift — `/weed`.** For each spec under `specs/`, run `/weed` in
   check mode and write a dated report to
   `specs/divergences/<YYYY-MM-DD>/`. Do not auto-resolve divergences;
   classify each as spec-bug / code-bug / intentional-gap so the morning
   triage is fast.

2. **Recent changes — `/code-review`.** Run `/code-review` over the last
   24 hours of diffs on `main` (`git log --since=midnight`). Capture
   correctness and reuse findings; do not apply fixes.

3. **Dependencies — `/dep-audit`.** Run `/dep-audit` across all modules.
   Note any new advisories or outdated pins.

4. **Service health — `/health`.** Run `/health` against the local stack
   if it is up; record which services and Kafka consumers are healthy. If
   the stack is down, record "stack offline" and continue — do not start
   it.

5. **File issues for *new* findings.** For each finding not already
   tracked, create a beads issue, e.g.
   `bd create --title="<scope>: <summary>" --description="<finding + location + suggested next step>" --type=bug --priority=2`.
   Deduplicate against open issues first (`bd list --status=open --json`)
   so the same drift is not filed twice. Tag the source check in the
   description (weed / code-review / dep-audit / health).

6. **Capture the trend.** Run the quantitative collector and renderer:

   ```bash
   python3 scripts/self-audit/collect-trend.py
   python3 scripts/self-audit/render-trend.py
   ```

   This appends one row to `docs/ops/self-audit-trend.jsonl` and
   regenerates `docs/ops/self-audit-trend.md`
   (see [the trend report](self-audit-trend.md)).

7. **Commit the artefacts.** Commit only the trend files and any new
   divergence reports:

   ```bash
   git add docs/ops/self-audit-trend.jsonl docs/ops/self-audit-trend.md specs/divergences/
   git commit -m "chore(self-audit): nightly trend + divergence sweep"
   ```

   Then push `main`. Do **not** commit code changes — those are the
   engineer's call.

## Morning triage (the human-in-the-loop step)

The engineer reviews the filed issues and the trend delta:

- Rising `allium_errors`/`allium_warnings` → spec or code drift to resolve
  via the normal `/distill`→`/weed`→`/propagate` loop.
- New `/code-review` findings → fix, dismiss with a reason, or convert to
  a tracked issue.
- New `/dep-audit` advisories → schedule the bump (dependency changes need
  explicit approval per `CLAUDE.md`).

Nothing in this runbook merges a behavioural change. The agent finds and
measures; the engineer decides. That division is the whole point.

## Registration

Register the routine to run nightly (example — adjust the time to taste):

```bash
# Via the /schedule skill (local routine), pointing at this runbook:
/schedule "0 6 * * *" run the nightly self-audit in docs/ops/nightly-self-audit.md
```

Confirm it is registered with the scheduler's list command (e.g.
`crontab -l` or the `/schedule` list view). The routine is local and
durable; it survives restarts and needs no CI secret.

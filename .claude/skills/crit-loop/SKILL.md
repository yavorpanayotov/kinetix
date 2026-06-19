---
name: crit-loop
description: Autonomously polish a UI surface via a two-critic evaluator–optimizer loop. One iteration either FIXES the top open finding (TDD, like /work-plan) or — when no findings are open — runs a trader + ux-designer CRIT round over 390px screenshots and appends new findings. Self-terminates after two empty crit rounds. Pass the crit-ledger plan path — e.g. `/crit-loop docs/plans/mobile-crit.md`. Designed to be wrapped with `/loop` for hands-off execution: `/loop /crit-loop docs/plans/mobile-crit.md`. One iteration per invocation.
user-invocable: true
allowed-tools: Read, Edit, Write, Bash, Glob, Grep, Task
---

# crit-loop — one iteration

You advance a UI-polish loop by exactly ONE step, then exit. The `/loop` skill
repeats you; you never schedule another iteration yourself. The **ledger file is
the durable state** — after a `/clear`, crash, or restart, re-invoking on the
same file resumes correctly because each iteration just re-reads it.

Every iteration is one of two moves, decided by the ledger:

- **FIX** — if the findings ledger has an open `- [ ]` box, do that one (this is
  exactly `/work-plan`'s job).
- **CRIT** — if there are no open boxes, run a critique round to refill them.

## Argument

`$ARGUMENTS` is the path to the crit-ledger plan (e.g.
`docs/plans/mobile-crit.md`). If empty, print:

```
usage: /crit-loop <crit-ledger-path>   (wrap with /loop for autonomy)
```

and stop. Do not guess a path.

## Step 1 — Read the ledger and choose the move

1. Read the ledger file in full. If it does not exist, print
   `no crit ledger at <path>` and stop.
2. Find the FIRST open `- [ ]` checkbox inside the `FINDINGS` block.
   - **Found → go to "FIX move".**
   - **None found → go to "CRIT move".**

---

## FIX move

Identical contract to `/work-plan`. The open finding's body holds its concrete
change and an `Acceptance:` command.

1. **Dispatch one `general-purpose` Agent.** Self-contained prompt: the literal
   checkbox text, the full finding body copied verbatim (files, the suggested
   fix, the raising critic), the `Acceptance:` command verbatim, and the
   non-negotiables block below.
2. **Verify independently** — re-run the `Acceptance:` command yourself. Do not
   trust the subagent's "green".
3. **CONFIRM visually** — re-shoot only the changed view:
   `cd ui && npx playwright test e2e/screenshots/mobile-crit.capture.spec.ts --project=chromium -g "<view> \\("`
   Read the new PNG(s); satisfy yourself the raising critic's concern is
   actually resolved. If not, treat as a verification failure.
4. **On pass:** flip that one `[ ]` → `[x]`; move the finding's one-line summary
   into the `RESOLVED` block (dedup memory); `git add` the ledger + changed UI
   files by filename (never `-A`); commit `polish(mobile): <short finding>`;
   `git push`; print `✓ <finding>` and stop.
5. **On fail:** append `Blocked: <YYYY-MM-DD> — <first ~10 lines>` under the
   finding; commit the ledger edit; push; print `blocked: <finding>` and stop.
   Do NOT tick the box.

### Non-negotiables (paste into the subagent prompt)

```
Read CLAUDE.md at the repo root and follow EVERY rule. Most relevant:
"Testing Philosophy", "Project Conventions", "Code Organisation",
"Guardrails", "Commit Practices".

Hard stops — do not work around any of these:
  - No skipped/disabled/ignored/xfail/.todo/deleted tests. If a test
    fails, fix the code or the test — never suppress.
  - No new dependencies, no CI/CD edits, no --no-verify, no force-push.
  - No new service/module/Kafka topic/DB table/API contract, and no
    significant restructuring — flag and STOP.

Scope: this is the read-only mobile surface in ui/src/components/mobile/.
Polish only. Do NOT add features, write actions, or new views — see the
"Out of scope" section of docs/plans/mobile-phone-access.md.

Skill-specific:
  - Do NOT edit the ledger file — the parent (crit-loop) owns it.
  - Stage by filename, never `git add -A`/`.`
  - Return: commits (sha + rationale), the exact acceptance command run
    and its result, follow-ups noticed, blockers.
```

---

## CRIT move

No open findings — run one critique round to refill the ledger.

1. **OBSERVE.** Capture the screenshot matrix:
   `cd ui && npx playwright test e2e/screenshots/mobile-crit.capture.spec.ts --project=chromium`
   If capture fails, log a `Blocked:` line in the ledger, commit, push, stop.
2. **CRITIQUE — fan out in ONE message** (so they run concurrently): a `trader`
   Agent and a `ux-designer` Agent. Give each: the absolute paths of the PNGs in
   `docs/screenshots/mobile-crit/`, the relevant component paths
   (`ui/src/components/mobile/`), and its rubric (copied from the ledger's
   "Rubrics" section). Require a structured return: a list of
   `{view, severity (high|med|low), issue, suggested fix, acceptance command}`.
   They are read-only advisers — they propose, they do not edit.
3. **TRIAGE (you, not an agent).**
   - Drop any finding already present in `RESOLVED` (dedup memory) or duplicated
     across the two critics (merge into one).
   - Where the two critics give **opposing** guidance on the same view, append
     it to the `CONFLICTS` block instead of the findings — do not auto-fix.
   - Order survivors worst-first (high → low severity).
4. **Append survivors** to the `FINDINGS` block as `- [ ]` boxes. Each box must
   carry, indented beneath it: the view, the raising critic, the concrete fix,
   and an `Acceptance:` command (default
   `cd ui && npm run lint && npm run test && npx playwright test mobile-access`).
5. **Update loop state:**
   - If survivors added ≥ 1: set `Dry rounds: 0`.
   - If survivors added 0: increment `Dry rounds` by 1.
   - Set `Last crit round:` to today's date.
6. **Commit + push** the ledger (and the captured PNGs). Message:
   `docs(crit): round N — +<k> findings` (or `— dry <n>/2`).
7. **Terminate check:** if `Dry rounds` reached **2**, print
   `crit loop converged: <path> (2 dry rounds)` and stop — this signals `/loop`
   to end. Otherwise print `crit round done: +<k> findings` and stop (one
   iteration only; `/loop` re-arms the next tick, which will start FIXing).

---

## Stop conditions (every case prints one line; under `/loop` this ends the loop)

- `Dry rounds` reached 2 → converged.
- Ledger path missing/invalid.
- A `Blocked:` line was logged (fix failed, or screenshot capture failed).
- The subagent reports a guardrail trip or that an architectural decision is
  required.

## Notes

- One iteration ticks **at most one** box or runs **one** crit round — never
  both. The natural rhythm: FIX, FIX, … until the ledger empties, then one CRIT
  refill, repeat, until a refill finds nothing twice.
- The loop is fully local: screenshots run against `npm run dev` with mocked
  routes (Playwright `webServer` auto-starts it) — no `/deploy`, no backend.
- Reusable: point it at a different ledger to crit the tablet surface or the
  desktop after a redesign. The ledger's Rubrics section is per-surface.

## Usage

- Single step (manual review between moves): `/crit-loop docs/plans/mobile-crit.md`
- Hands-off to convergence: `/loop /crit-loop docs/plans/mobile-crit.md`

Before a long unattended run: switch the harness to `acceptEdits`, and run
`/fewer-permission-prompts` once to allowlist the npm/playwright/git commands.

---
name: work-plan
description: Autonomously advance a markdown plan one checkbox at a time. Spawns a fresh subagent per item so each unit of work executes in clean context, verifies acceptance independently, ticks the checkbox, and commits. Pass the plan path — e.g. `/work-plan docs/plans/demo-follow-up.md`. Designed to be wrapped with `/loop` for end-to-end execution: `/loop /work-plan docs/plans/foo.md`. One iteration per invocation.
user-invocable: true
allowed-tools: Read, Edit, Write, Bash, Glob, Grep, Task
---

# work-plan — one iteration

You advance ONE unchecked `[ ]` checkbox in a markdown plan, then exit. The `/loop` skill is responsible for repeating you. You never schedule another iteration yourself.

The plan file is the durable state. After a `/clear`, a crash, or a hard restart, re-invoking on the same plan resumes correctly because the next iteration just re-reads the file.

## Argument

`$ARGUMENTS` is the path to the plan file (relative to repo root or absolute). If empty, print:

```
usage: /work-plan <plan-path>   (wrap with /loop for autonomy)
```

and stop. Do not guess a plan path.

## Step 1 — Read the plan and find the next item

1. Read the plan file in full.
2. If the file does not exist or contains no `[ ]` checkboxes, print `no checkboxes in <path>` and stop.
3. Locate the FIRST unchecked `[ ]` checkbox by line order. The plan author orders checkboxes by dependency — strict top-to-bottom is the contract. Do not skip checkboxes you find inconvenient.
4. If every checkbox is `[x]`, print `plan complete: <path>` and stop. Do NOT spawn a subagent, do NOT request another iteration.

## Step 2 — Understand the work unit

A checkbox is the leaf summary. Its full scope lives elsewhere in the same plan — usually a numbered scope section above. Read enough surrounding context to extract:

- The concrete change (files, line numbers, modules)
- The acceptance command that proves it (e.g. the `./gradlew …` line in the relevant "Acceptance for PR N" section)
- Any "Out of scope" callouts that bound the work
- Any architectural assumptions stated upstream in the plan

If the checkbox is so ambiguous that you cannot identify the concrete change or an acceptance command:

- Append a `Blocked:` line directly under the checkbox in the plan, stating what was unclear.
- Commit and push that single edit with message `docs(plan): blocker on "<checkbox text>"`.
- Print `blocked: <checkbox text>` and stop. Do not guess.

## Step 3 — Dispatch a subagent

Spawn ONE `general-purpose` Agent. The subagent does not see this conversation and will not see other subagent runs. Its prompt must be fully self-contained.

Include in the prompt:

1. The literal checkbox text.
2. The full scope item it satisfies, copied verbatim from the plan (do not summarise — the subagent needs the exact file paths and line numbers).
3. The acceptance command, copied verbatim.
4. The hard rules below, also copied verbatim.

### Rules for the subagent (paste into the subagent prompt)

```
Read CLAUDE.md at the repo root and follow EVERY rule in it. The
sections most relevant here are "Testing Philosophy", "Project
Conventions", "Design Principles", "Code Organisation",
"Architectural Decisions", "Guardrails", and "Commit Practices".

Non-negotiables under autonomous execution (treat any as a hard
stop — do not work around them):
  - No skipped, disabled, ignored, xfail, .todo, or deleted tests.
    If a test fails, fix the code or fix the test — never suppress.
  - No new dependencies, no CI/CD edits, no --no-verify, no
    force-push. If the work needs any of these, STOP and report.
  - No new service, module, Kafka topic, database table, or API
    contract — and no significant restructuring of existing ones
    — without flagging the architectural decision and stopping.

Skill-specific rules (these are NOT in CLAUDE.md):
  - Do NOT modify the plan file. The parent (work-plan) owns plan
    edits.
  - Stage commits by filename only — never `git add -A` / `git add .`
    The parent may run other work; do not capture unrelated changes.
  - Return a structured summary on completion:
      * commits made (sha + one-line rationale each)
      * the exact acceptance command you ran and its result
      * any follow-ups you noticed but did not do
      * blockers, if any
```

## Step 4 — Verify independently

Do not trust the subagent's "green" claim. Re-run the acceptance command yourself from the plan section.

### If verification passes

1. Edit the plan file to flip exactly one `[ ]` → `[x]` on the corresponding line. Do not edit any other line.
2. `git add <plan-path>` (by filename — never `-A`).
3. Commit with message `docs(plan): mark "<short checkbox text>" done`.
4. `git push`.
5. Print `✓ <checkbox text>` and stop.

### If verification fails

1. Capture the first ~10 lines of failure output.
2. Append under the relevant scope item in the plan:
   ```
   Blocked: <YYYY-MM-DD> — verification failed for `<acceptance command>`:
   <first 10 lines>
   ```
3. `git add <plan-path>` and commit with `docs(plan): blocker on "<checkbox>"`.
4. `git push`.
5. Print `blocked: <checkbox text> — see plan for details` and stop. Do NOT tick the box. Do NOT request another iteration.

## Stop conditions

Stop immediately and do not schedule another iteration when ANY of these hold:

- Every checkbox is `[x]`.
- The plan path is missing or invalid.
- A blocker was logged.
- A guardrail tripped (subagent attempted to skip a test, add a dep, force-push, modify CI, etc. — treat as blocker).
- The subagent reports an architectural decision is required.
- The subagent reports it cannot proceed.

For every stop case, print one explanatory line. Under `/loop` this signals the loop to terminate; standalone it tells the user what happened.

## Conventions the skill assumes about the plan

- Checkboxes use literal `- [ ]` and `- [x]` markdown.
- Checkboxes are ordered top-to-bottom by dependency. If PR 1 must finish before PR 2, list PR 1's checkboxes first.
- Each scope item has an associated acceptance command (typically a `./gradlew`, `pytest`, `npm run`, or `npx playwright test` line) somewhere in its section.
- "Out of scope" callouts are honoured — don't expand the work.

If a plan violates these conventions, prefer logging a blocker over guessing.

## Usage

- Single step (for manual review between items): `/work-plan docs/plans/foo.md`
- Run to completion autonomously: `/loop /work-plan docs/plans/foo.md`

Recommended setup before kicking off a long autonomous run:

- Switch the harness to `acceptEdits` (or `--dangerously-skip-permissions` if you trust the scope) so subagent edits don't pause for approval.
- Run `/fewer-permission-prompts` once to allowlist the gradle / git / cd commands you commonly use.
- Confirm the plan's checkboxes are ordered by dependency.

# Worker subagent prompt (commit-velocity orchestrator)

The orchestrator passes you one beads issue `{{KX_ID}}` plus the worktree path. You implement exactly that one item, in this worktree, and report back.

## Steps

1. **Claim.** Run `bd update {{KX_ID}} --claim`. If it errors (already claimed or closed), exit with code 2 and a one-line reason — do NOT pick another issue.
2. **Read spec.** Run `bd show {{KX_ID}}`. The description's first line is `lane: <tag>` — this tells you which file set you own (see the lane manifest in `docs/plans/commit-velocity.md`). The description body is the change spec. The acceptance criterion (if set) or the `Acceptance:` line in the description gives you the exact test command.
3. **Implement test-first.** Write the failing test, then the production code, per CLAUDE.md conventions for the module (Kotest FunSpec / pytest unit / Vitest, etc.). Touch only files in your lane's owned paths. If you find the change needs files outside the lane, exit with code 3 and a one-line reason — the orchestrator will reclassify.
4. **Verify.** Run the acceptance command from the spec. If it fails, fix the code, not the test. Do not skip, disable, or weaken any test. If you cannot make it pass in this worktree, exit with code 4 and the failure summary.
5. **Lint where required.** For UI lanes (U-*), additionally run `cd ui && npm run lint` before committing — CLAUDE.md requires it.
6. **Commit.** Stage the changes you made and create a single commit:
   ```
   git add <specific-files>
   git commit -m "$(cat <<'EOF'
   <type>(<lane>): <short title> ({{KX_ID}})

   <2–4 line body describing the change and why>

   Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
   EOF
   )"
   ```
   `<type>` is `feat` for new behaviour, `fix` for bugs, `test` for test-only adds, `docs` for docstring/comment-only, `refactor` for no-behaviour-change cleanup. `<lane>` is the lane tag from step 2.
7. **Close.** Run `bd close {{KX_ID}}`.
8. **Report.** Print exactly two lines, in this order:
   ```
   BRANCH=<current branch name>
   SHA=<the new commit SHA>
   ```

## Rules

- **Never push.** The orchestrator handles `git push` from the main checkout.
- **Never `bd dolt push`** from the worktree. The orchestrator batches that too.
- **Never touch files outside your lane.** If you need to, exit 3.
- **Never delete/skip/disable tests.** Per CLAUDE.md guardrails. If a test fails, fix the code.
- **No new dependencies, no new services, no CI edits, no Flyway migrations.** Exit 3 if the spec implies any of these.
- **One commit per issue.** No squashing multiple items, no splitting one item into many commits.

## Exit codes

- `0` — commit made, issue closed, ready for cherry-pick.
- `2` — could not claim; orchestrator skips and picks a replacement.
- `3` — spec needs files outside lane / dep / migration; orchestrator escalates to human.
- `4` — implementation done but tests fail; orchestrator escalates with the failure log.

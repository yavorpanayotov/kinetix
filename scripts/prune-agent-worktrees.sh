#!/usr/bin/env bash
# prune-agent-worktrees.sh — safely clean up finished Claude agent worktrees.
#
# The Agent tool creates ephemeral git worktrees under .claude/worktrees/ and is
# supposed to remove them when an agent finishes "unchanged". In practice many
# survive — accumulating hundreds of `git worktree` admin entries that slow down
# every worktree operation. This script reclaims them WITHOUT ever touching a
# worktree that is still in use.
#
# Safety model (a worktree is only removed when ALL hold):
#   1. It lives under .claude/worktrees/ (never the main tree or external clones).
#   2. It is NOT locked. The Agent tool locks a worktree to its live process
#      (`git worktree lock --reason "claude agent <id> (pid N)"`), so a lock means
#      an agent may still be running — we skip it.
#   3. It has no uncommitted changes. `git worktree remove` (without --force)
#      refuses a dirty worktree, so this is enforced by git itself; we never pass
#      --force. Branches are preserved either way — only the working directory is
#      removed, so no committed work is lost.
#
# It also runs `git worktree prune` to drop admin entries whose directory is
# already gone.
#
# Usage:
#   scripts/prune-agent-worktrees.sh           # dry run — report only
#   scripts/prune-agent-worktrees.sh --apply   # actually remove eligible worktrees
#
# Exits 0 on success. Designed to be safe to run at any time, including while
# other agents are active.

set -euo pipefail

APPLY=false
[[ "${1:-}" == "--apply" ]] && APPLY=true

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

echo "== git worktree prune (drop entries whose directory is already gone) =="
if $APPLY; then
  git worktree prune -v
else
  git worktree prune -n -v || true
fi

# Build the set of currently-locked worktree paths (these are skipped).
locked_paths="$(git worktree list --porcelain \
  | awk '/^worktree /{p=$2} /^locked/{print p}')"

removed=0
skipped_locked=0
skipped_dirty=0

# Iterate registered worktrees under .claude/worktrees/ only.
while IFS= read -r line; do
  [[ "$line" == worktree\ * ]] || continue
  wt="${line#worktree }"
  case "$wt" in
    "$repo_root"/.claude/worktrees/*) ;;   # in scope
    *) continue ;;                          # main tree / external clone — skip
  esac

  if grep -Fxq "$wt" <<<"$locked_paths"; then
    skipped_locked=$((skipped_locked + 1))
    continue
  fi

  # Dirty check: any uncommitted change means an agent left work in progress.
  if [[ -n "$(git -C "$wt" status --porcelain 2>/dev/null)" ]]; then
    skipped_dirty=$((skipped_dirty + 1))
    echo "  skip (uncommitted changes): ${wt##*/}"
    continue
  fi

  if $APPLY; then
    # No --force: git still refuses if it considers the tree dirty, a final guard.
    if git worktree remove "$wt" 2>/dev/null; then
      echo "  removed: ${wt##*/}"
      removed=$((removed + 1))
    else
      echo "  skip (git refused): ${wt##*/}"
    fi
  else
    echo "  would remove: ${wt##*/}"
    removed=$((removed + 1))
  fi
done < <(git worktree list --porcelain)

verb=$([ "$APPLY" = true ] && echo "removed" || echo "eligible")
echo "== summary: ${removed} ${verb}, ${skipped_locked} skipped (locked/live), ${skipped_dirty} skipped (uncommitted) =="
$APPLY || echo "(dry run — re-run with --apply to remove)"

#!/usr/bin/env bash
# run_batch.sh — pick up to N beads issues from disjoint lanes for a parallel batch.
#
# Convention: every issue created by the commit-velocity orchestrator carries
# its lane as the first line of its description, formatted exactly as:
#   lane: <tag>
# where <tag> is one of the entries in docs/plans/commit-velocity.md (e.g. R-bs-greeks,
# K-position, U-format, …).
#
# Usage: scripts/run_batch.sh [N]   # default N=5
#
# Output: one "<kx-id>\t<lane>" line per selected issue, picked greedily from the
# top of `bd ready` such that no two share a lane. Exits 0 even when fewer than N
# items are available — the orchestrator handles short batches.

set -euo pipefail

N="${1:-5}"

if ! command -v bd >/dev/null 2>&1; then
  echo "run_batch.sh: bd CLI not on PATH" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "run_batch.sh: jq not on PATH" >&2
  exit 1
fi

# Pull every ready issue, extract id + lane (first-line "lane: <tag>" from description).
# Issues without a `lane:` prefix are skipped — they belong to other workflows.
bd ready --json 2>/dev/null \
  | jq -r '
      .[]
      | . as $i
      | ($i.description // "")
      | split("\n")[0]
      | capture("^lane:\\s*(?<tag>[A-Za-z0-9_\\-]+)")?
      | select(. != null)
      | $i.id + "\t" + .tag
    ' \
  | awk -v n="$N" '
      !seen[$2]++ { print; if (++c == n) exit }
    '

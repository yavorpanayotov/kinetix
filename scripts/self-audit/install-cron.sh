#!/usr/bin/env bash
# Register (or remove) the nightly self-audit as a LOCAL cron routine.
#
# This installs a single, idempotent crontab entry that runs Claude Code
# headlessly against docs/ops/nightly-self-audit.md every night. It is
# local by design — no CI, no API key in a pipeline, survives reboots via
# the OS scheduler — matching the local-first docker-compose model.
#
# Usage:
#   scripts/self-audit/install-cron.sh install   # add/refresh the entry
#   scripts/self-audit/install-cron.sh uninstall  # remove the entry
#   scripts/self-audit/install-cron.sh status     # show the current entry
#
# Environment overrides:
#   SELF_AUDIT_SCHEDULE   cron schedule (default: "0 6 * * *" — 06:00 daily)
#   CLAUDE_BIN            path to the claude CLI (default: "claude")
#
# The entry is tagged with a marker comment so install/uninstall are
# idempotent and never touch unrelated crontab lines.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MARKER="# kinetix-self-audit"
SCHEDULE="${SELF_AUDIT_SCHEDULE:-0 6 * * *}"
CLAUDE_BIN="${CLAUDE_BIN:-claude}"
RUNBOOK="docs/ops/nightly-self-audit.md"

# The command cron runs. We cd into the repo, then run Claude headlessly
# with a prompt that points at the runbook. acceptEdits lets it write the
# trend/divergence artefacts and commit; it still files code fixes as
# issues rather than merging them (per the runbook).
read -r -d '' CRON_CMD <<EOF || true
cd ${REPO_ROOT} && ${CLAUDE_BIN} -p "Run the nightly self-audit exactly as specified in ${RUNBOOK}." --permission-mode acceptEdits >> ${REPO_ROOT}/docs/ops/.self-audit-cron.log 2>&1
EOF

CRON_LINE="${SCHEDULE} ${CRON_CMD} ${MARKER}"

current_crontab() {
  crontab -l 2>/dev/null || true
}

without_marker() {
  current_crontab | grep -vF "${MARKER}" || true
}

case "${1:-status}" in
  install)
    { without_marker; echo "${CRON_LINE}"; } | crontab -
    echo "installed nightly self-audit cron (${SCHEDULE}):"
    crontab -l | grep -F "${MARKER}"
    ;;
  uninstall)
    without_marker | crontab -
    echo "removed nightly self-audit cron entry (if it was present)"
    ;;
  status)
    if current_crontab | grep -qF "${MARKER}"; then
      echo "nightly self-audit cron is registered:"
      current_crontab | grep -F "${MARKER}"
    else
      echo "nightly self-audit cron is NOT registered"
      echo "run: scripts/self-audit/install-cron.sh install"
    fi
    ;;
  *)
    echo "usage: $0 {install|uninstall|status}" >&2
    exit 2
    ;;
esac

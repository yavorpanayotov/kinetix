#!/usr/bin/env bash
#
# audit-chain-verify.sh — verify the audit-service hash chain end-to-end.
#
# Usage:
#   scripts/support/audit-chain-verify.sh            # default last 1000 events
#   scripts/support/audit-chain-verify.sh --count 5000
#
# Walks the audit chain from the most recent entry backwards N entries
# (default 1000) and verifies every link — each event's `previousHash`
# must equal the preceding event's `recordHash`. Prints PASS / FAIL with
# the offending entry id on the first broken link. See ADR-0017.
#
# Env vars: GATEWAY_URL (default https://api.kinetixrisk.ai).
# Required tools: curl, jq.
# Exit codes: 0 PASS, 1 usage error, 2 runtime error, 3 FAIL (chain broken).

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-https://api.kinetixrisk.ai}"
DEFAULT_COUNT=1000
PAGE_SIZE=1000

usage() {
    cat <<'USAGE'
audit-chain-verify.sh — verify the audit-service hash-chain integrity

Walks the audit chain from the most recent event backwards N entries
(default 1000) and verifies every link by checking that each event's
`previousHash` equals the preceding event's `recordHash`. Prints PASS
on success or FAIL with the offending entry id on the first break.

Usage:
  audit-chain-verify.sh                Verify the last 1000 entries
  audit-chain-verify.sh --count N      Verify the last N entries
  audit-chain-verify.sh -h | --help    Print this help

Environment:
  GATEWAY_URL  Gateway base URL (default https://api.kinetixrisk.ai)

Exit codes:
  0  PASS (chain valid)
  1  usage error
  2  runtime error (network failure, missing tool)
  3  FAIL (chain broken)
USAGE
}

require_tool() {
    local tool="$1"
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "error: required tool '$tool' is not installed" >&2
        echo "  install via 'brew install $tool' (macOS) or 'apt-get install $tool' (Debian/Ubuntu)" >&2
        exit 2
    fi
}

fetch_page() {
    local after_id="$1"
    local limit="$2"
    if ! curl -sS --fail-with-body \
        "${GATEWAY_URL}/api/v1/audit/events?afterId=${after_id}&limit=${limit}" 2>&1; then
        echo "error: audit events query failed at ${GATEWAY_URL}" >&2
        exit 2
    fi
}

main() {
    local count="$DEFAULT_COUNT"

    while [ $# -gt 0 ]; do
        case "$1" in
            -h|--help)
                usage
                exit 0
                ;;
            --count)
                shift
                if [ $# -eq 0 ] || ! [[ "$1" =~ ^[0-9]+$ ]] || [ "$1" -eq 0 ]; then
                    echo "error: --count requires a positive integer" >&2
                    exit 1
                fi
                count="$1"
                shift
                ;;
            *)
                echo "error: unknown argument '$1'" >&2
                usage >&2
                exit 1
                ;;
        esac
    done

    require_tool curl
    require_tool jq

    echo "audit chain verify  gateway=${GATEWAY_URL}  window=${count} most-recent events"
    echo "----------------------------------------------------------------"

    # Page forward from id=0 streaming each batch into the sliding window.
    # Keep at most `count` rows tail-end.
    local after_id=0
    local tmp
    tmp=$(mktemp)
    trap 'rm -f "$tmp"' EXIT

    local total_seen=0
    while :; do
        local body
        body=$(fetch_page "$after_id" "$PAGE_SIZE")
        local batch_size
        batch_size=$(printf '%s' "$body" | jq 'length')
        if [ "$batch_size" -eq 0 ]; then
            break
        fi
        total_seen=$((total_seen + batch_size))
        # Append id\tpreviousHash\trecordHash rows.
        printf '%s' "$body" \
            | jq -r '.[] | [(.id|tostring), (.previousHash // ""), (.recordHash // "")] | @tsv' \
            >> "$tmp"
        # Trim file to last `count` lines so memory/disk stays bounded.
        if [ "$(wc -l < "$tmp")" -gt "$count" ]; then
            tail -n "$count" "$tmp" > "${tmp}.trim"
            mv "${tmp}.trim" "$tmp"
        fi
        # Advance cursor to last id seen in this batch.
        after_id=$(printf '%s' "$body" | jq -r '.[-1].id')
        if [ "$batch_size" -lt "$PAGE_SIZE" ]; then
            break
        fi
    done

    local window_size
    window_size=$(wc -l < "$tmp" | tr -d ' ')

    if [ "$window_size" -eq 0 ]; then
        echo "PASS (no audit events present — chain is trivially valid)"
        echo "events_seen=${total_seen}  verified=0"
        exit 0
    fi

    # Walk forward, verifying that current.previousHash == prior.recordHash.
    local prior_hash=""
    local first=1
    local verified=0
    local fail_id=""
    while IFS=$'\t' read -r id prev cur; do
        if [ "$first" -eq 1 ]; then
            first=0
            prior_hash="$cur"
            verified=$((verified + 1))
            continue
        fi
        if [ "$prev" != "$prior_hash" ]; then
            fail_id="$id"
            break
        fi
        prior_hash="$cur"
        verified=$((verified + 1))
    done < "$tmp"

    if [ -n "$fail_id" ]; then
        echo "FAIL  offending_entry_id=${fail_id}  verified_before_break=${verified}  window=${window_size}"
        exit 3
    fi

    echo "PASS  verified=${verified}  window=${window_size}  events_seen=${total_seen}"
    exit 0
}

main "$@"

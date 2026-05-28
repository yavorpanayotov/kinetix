#!/usr/bin/env bash
#
# follow-trade.sh — consolidated timeline for one trade across the platform.
#
# Usage:
#   scripts/support/follow-trade.sh <trade_id>
#
# Pulls audit events for the trade from the gateway audit proxy
# (`/api/v1/audit/events?tradeId=...`), then queries Loki for log lines
# mentioning the trade ID, and prints a single chronological timeline.
#
# Env vars: GATEWAY_URL (default https://api.kinetixrisk.ai),
#           LOKI_URL (default http://localhost:3100), LOG_HOURS (default 24).
# Required tools: curl, jq.
# Exit codes: 0 success, 1 usage error, 2 runtime error.

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-https://api.kinetixrisk.ai}"
LOKI_URL="${LOKI_URL:-http://localhost:3100}"
LOG_HOURS="${LOG_HOURS:-24}"

usage() {
    cat <<'USAGE'
follow-trade.sh — chronological timeline for a single trade

Queries audit-service (via gateway) for hash-chained entries referencing
the trade, then Loki for any log lines mentioning the trade ID, and
prints one consolidated timeline. Read-only — no Kafka writes.

Usage:
  follow-trade.sh <trade_id>
  follow-trade.sh -h | --help

Environment:
  GATEWAY_URL  Gateway base URL (default https://api.kinetixrisk.ai)
  LOKI_URL     Loki base URL    (default http://localhost:3100)
  LOG_HOURS    Look-back window for Loki in hours (default 24)

Exit codes:
  0  success
  1  usage error
  2  runtime error (network failure, missing tool)
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

fetch_audit() {
    local trade_id="$1"
    local encoded
    encoded=$(jq -rn --arg q "$trade_id" '$q|@uri')
    if ! curl -sS --fail-with-body \
        "${GATEWAY_URL}/api/v1/audit/events?tradeId=${encoded}&limit=1000" 2>/dev/null; then
        echo "warn: gateway audit query failed at ${GATEWAY_URL}" >&2
        echo "[]"
    fi
}

fetch_loki() {
    local trade_id="$1"
    # Loki LogQL: search across any container log for the trade ID.
    local logql="{job=~\".+\"} |= \"${trade_id}\""
    local end_ns start_ns
    end_ns=$(date +%s)000000000
    start_ns=$(( $(date +%s) - LOG_HOURS * 3600 ))000000000
    local encoded
    encoded=$(jq -rn --arg q "$logql" '$q|@uri')
    if ! curl -sS --fail-with-body --get \
        --data "query=${encoded}" \
        --data "start=${start_ns}" \
        --data "end=${end_ns}" \
        --data "limit=500" \
        --data "direction=forward" \
        "${LOKI_URL}/loki/api/v1/query_range" 2>/dev/null; then
        echo "warn: Loki query failed at ${LOKI_URL}" >&2
        echo '{"data":{"result":[]}}'
    fi
}

normalise_audit() {
    # Emit one TSV row per event: timestamp, source, summary
    jq -r '
        if type == "array" then .
        else (.events // .data // []) end
        | .[]
        | [
            (.receivedAt // .timestamp // ""),
            "audit",
            "\(.eventType // "?") id=\(.id // "?") side=\(.side // "-") qty=\(.quantity // "-") px=\(.priceAmount // "-") \(.priceCurrency // "") user=\(.userId // "-")"
          ]
        | @tsv
    '
}

normalise_loki() {
    # Each result has values: [[timestampNs, line], ...]
    jq -r '
        (.data.result // [])
        | map(.values // [])
        | add // []
        | .[]
        | [ (.[0] | tonumber / 1000000000 | strftime("%Y-%m-%dT%H:%M:%SZ")),
            "loki",
            (.[1] | gsub("\t"; " ") | .[0:240]) ]
        | @tsv
    '
}

main() {
    if [ $# -eq 0 ]; then
        usage
        exit 1
    fi
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            echo "error: unknown flag '$1'" >&2
            usage >&2
            exit 1
            ;;
    esac

    require_tool curl
    require_tool jq

    local trade_id="$1"
    echo "timeline for trade_id=${trade_id}"
    echo "gateway=${GATEWAY_URL}  loki=${LOKI_URL}  lookback=${LOG_HOURS}h"
    echo "----------------------------------------------------------------"

    local audit_rows loki_rows
    audit_rows=$(fetch_audit "$trade_id" | normalise_audit || true)
    loki_rows=$(fetch_loki "$trade_id" | normalise_loki || true)

    local combined
    combined=$(printf '%s\n%s\n' "$audit_rows" "$loki_rows" | grep -v '^$' || true)
    if [ -z "$combined" ]; then
        echo "(no audit events or log lines found for ${trade_id})"
        return 0
    fi

    # Sort by ISO timestamp ascending, then format columns.
    printf '%-25s %-6s %s\n' "TIMESTAMP" "SOURCE" "DETAIL"
    printf '%s\n' "$combined" \
        | sort -k1,1 \
        | awk -F'\t' '{ printf "%-25s %-6s %s\n", $1, $2, $3 }'
}

main "$@"

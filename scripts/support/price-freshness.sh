#!/usr/bin/env bash
#
# price-freshness.sh — answer the trader's question "how fresh is <symbol>?"
#
# Usage:
#   scripts/support/price-freshness.sh AAPL.NASDAQ
#   scripts/support/price-freshness.sh --all
#
# Reads the Prometheus `price_staleness_seconds{instrument_id=...}` gauge
# emitted by price-service and the latest price timestamp from the gateway
# `GET /api/v1/prices/{instrumentId}/latest` endpoint.
#
# Env vars: PROM_URL (default http://localhost:9090), GATEWAY_URL
#           (default https://api.kinetixrisk.ai).
# Required tools: curl, jq.
# Exit codes: 0 success, 1 usage error, 2 runtime error.

set -euo pipefail

PROM_URL="${PROM_URL:-http://localhost:9090}"
GATEWAY_URL="${GATEWAY_URL:-https://api.kinetixrisk.ai}"
TOP_N=20

usage() {
    cat <<'USAGE'
price-freshness.sh — probe price freshness for one or all instruments

Usage:
  price-freshness.sh <instrument_id>   Show staleness + last gateway timestamp
  price-freshness.sh --all             Show the 20 stalest instruments
  price-freshness.sh -h | --help       Print this help

Environment:
  PROM_URL      Prometheus base URL (default http://localhost:9090)
  GATEWAY_URL   Gateway base URL (default https://api.kinetixrisk.ai)

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

prom_query() {
    local query="$1"
    local encoded
    encoded=$(jq -rn --arg q "$query" '$q|@uri')
    curl -sS --fail-with-body "${PROM_URL}/api/v1/query?query=${encoded}" 2>&1 || {
        echo "error: Prometheus query failed at ${PROM_URL}" >&2
        return 2
    }
}

show_all() {
    local body
    if ! body=$(prom_query "topk(${TOP_N}, price_staleness_seconds)"); then
        exit 2
    fi
    local count
    count=$(printf '%s' "$body" | jq -r '.data.result | length' 2>/dev/null || echo 0)
    if [ "$count" -eq 0 ]; then
        echo "No price_staleness_seconds samples in Prometheus at ${PROM_URL}."
        echo "(Is the price-service scrape target up? Have any prices been ingested?)"
        return 0
    fi
    printf '%-32s %18s\n' "INSTRUMENT" "STALENESS_SEC"
    printf '%s' "$body" \
        | jq -r '.data.result[] | "\(.metric.instrument_id // "<unknown>")\t\(.value[1])"' \
        | sort -k2 -n -r \
        | awk -F'\t' '{ printf "%-32s %18s\n", $1, $2 }'
}

show_one() {
    local instrument="$1"
    local body staleness
    if ! body=$(prom_query "price_staleness_seconds{instrument_id=\"${instrument}\"}"); then
        exit 2
    fi
    staleness=$(printf '%s' "$body" | jq -r '.data.result[0].value[1] // "n/a"')
    echo "instrument:           ${instrument}"
    echo "prometheus_staleness: ${staleness}s"

    local gateway_body
    if gateway_body=$(curl -sS --fail-with-body \
        "${GATEWAY_URL}/api/v1/prices/${instrument}/latest" 2>/dev/null); then
        local ts price
        ts=$(printf '%s' "$gateway_body" | jq -r '.timestamp // .asOf // "n/a"')
        price=$(printf '%s' "$gateway_body" | jq -r '.price // .value // "n/a"')
        echo "gateway_last_update:  ${ts}"
        echo "gateway_last_price:   ${price}"
    else
        echo "gateway_last_update:  <gateway request failed at ${GATEWAY_URL}>"
    fi
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
        --all)
            require_tool curl
            require_tool jq
            show_all
            ;;
        -*)
            echo "error: unknown flag '$1'" >&2
            usage >&2
            exit 1
            ;;
        *)
            require_tool curl
            require_tool jq
            show_one "$1"
            ;;
    esac
}

main "$@"

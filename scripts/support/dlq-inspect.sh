#!/usr/bin/env bash
#
# dlq-inspect.sh — read-only inspector for a Kafka dead-letter topic.
#
# Usage:
#   scripts/support/dlq-inspect.sh trades.lifecycle.dlq
#   scripts/support/dlq-inspect.sh trades.lifecycle.dlq --peek 5
#
# Lists parked messages on a `.dlq` topic via kcat including headers
# (failure reason, original offset, retry count). Inspection only — never
# replays or deletes. Topic must end in `.dlq` per the platform's
# `<topic>.dlq` convention (see common/RetryableConsumer.kt).
#
# Env vars: KAFKA_BROKER (default localhost:9092).
# Required tools: kcat.
# Exit codes: 0 success, 1 usage error, 2 runtime error.

set -euo pipefail

KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"
PEEK_COUNT=""

usage() {
    cat <<'USAGE'
dlq-inspect.sh — inspect parked messages on a DLQ topic (read-only)

Usage:
  dlq-inspect.sh <topic>             List all messages on the topic
  dlq-inspect.sh <topic> --peek N    Show only the first N messages
  dlq-inspect.sh -h | --help         Print this help

The topic argument must end in `.dlq` — this script will not run against
non-DLQ topics. Headers (failure reason, original offset, retry count)
are printed alongside the message body. Nothing is replayed or deleted.

Environment:
  KAFKA_BROKER  Kafka bootstrap server (default localhost:9092)

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
        echo "  install via 'brew install $tool' (macOS) or 'apt-get install kafkacat' (Debian/Ubuntu)" >&2
        exit 2
    fi
}

inspect_topic() {
    local topic="$1"
    # kcat -C consumer mode, -e exit after EOF, -o beginning to read from start.
    # -f format string: offset / partition / key / headers / value, tab-separated.
    local format='offset=%o\tpartition=%p\tkey=%k\theaders=%h\tvalue=%s\n'
    local cmd=(kcat -b "$KAFKA_BROKER" -t "$topic" -C -e -o beginning -q -f "$format")

    if [ -n "$PEEK_COUNT" ]; then
        cmd+=(-c "$PEEK_COUNT")
    fi

    if ! "${cmd[@]}"; then
        echo "error: kcat failed against broker ${KAFKA_BROKER} for topic ${topic}" >&2
        exit 2
    fi
}

main() {
    if [ $# -eq 0 ]; then
        usage
        exit 1
    fi

    local topic=""
    while [ $# -gt 0 ]; do
        case "$1" in
            -h|--help)
                usage
                exit 0
                ;;
            --peek)
                shift
                if [ $# -eq 0 ] || ! [[ "$1" =~ ^[0-9]+$ ]]; then
                    echo "error: --peek requires a positive integer" >&2
                    exit 1
                fi
                PEEK_COUNT="$1"
                shift
                ;;
            -*)
                echo "error: unknown flag '$1'" >&2
                usage >&2
                exit 1
                ;;
            *)
                if [ -n "$topic" ]; then
                    echo "error: only one topic may be specified" >&2
                    exit 1
                fi
                topic="$1"
                shift
                ;;
        esac
    done

    if [ -z "$topic" ]; then
        echo "error: topic argument is required" >&2
        usage >&2
        exit 1
    fi

    case "$topic" in
        *.dlq) ;;
        *)
            echo "error: refusing to inspect non-DLQ topic '${topic}' — name must end in '.dlq'" >&2
            exit 1
            ;;
    esac

    require_tool kcat

    echo "broker=${KAFKA_BROKER}  topic=${topic}  mode=read-only"
    if [ -n "$PEEK_COUNT" ]; then
        echo "limit=${PEEK_COUNT}"
    fi
    echo "----------------------------------------------------------------"
    inspect_topic "$topic"
}

main "$@"

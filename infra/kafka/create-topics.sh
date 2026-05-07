#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
KAFKA_TOPICS="${KAFKA_TOPICS_CMD:-/opt/kafka/bin/kafka-topics.sh}"
# DEV default: RF=1. Production: set REPLICATION_FACTOR=3 with a 3-broker cluster.
REPLICATION="${REPLICATION_FACTOR:-1}"

# Wait for broker to be ready before creating topics.
wait_for_broker() {
  local max_attempts=30
  local attempt=1
  while [ $attempt -le $max_attempts ]; do
    if $KAFKA_TOPICS --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; then
      echo "Broker ready after $attempt attempt(s)"
      return 0
    fi
    echo "Waiting for broker (attempt $attempt/$max_attempts)..."
    sleep 2
    attempt=$((attempt + 1))
  done
  echo "ERROR: Broker not ready after $max_attempts attempts"
  exit 1
}

wait_for_broker

# Create a topic with the given name and partition count.
create_topic() {
  local name="$1"
  local partitions="$2"
  echo "Creating topic: $name (partitions=$partitions, replication=$REPLICATION)"
  $KAFKA_TOPICS \
    --bootstrap-server "$BOOTSTRAP" \
    --create \
    --if-not-exists \
    --topic "$name" \
    --partitions "$partitions" \
    --replication-factor "$REPLICATION"
}

# Create a topic with extra --config overrides (e.g. retention.ms).
create_topic_with_config() {
  local name="$1"
  local partitions="$2"
  shift 2
  echo "Creating topic: $name (partitions=$partitions, replication=$REPLICATION, configs: $*)"
  local args=(
    --bootstrap-server "$BOOTSTRAP"
    --create
    --if-not-exists
    --topic "$name"
    --partitions "$partitions"
    --replication-factor "$REPLICATION"
  )
  for cfg in "$@"; do
    args+=(--config "$cfg")
  done
  $KAFKA_TOPICS "${args[@]}"
}

# ── Core topics ──────────────────────────────────────────────────────
create_topic "trades.lifecycle"   3
create_topic "price.updates"     6
create_topic "risk.results"      3

# ── Risk ─────────────────────────────────────────────────────────────
create_topic "risk.anomalies"      3
create_topic "risk.audit"          3
create_topic "risk.pnl.intraday"   3
create_topic "risk.regime.changes" 1

# ── Limits ───────────────────────────────────────────────────────────
create_topic "limits.breaches"     3

# ── Rates ────────────────────────────────────────────────────────────
create_topic "rates.yield-curves" 3
create_topic "rates.risk-free"    3
create_topic "rates.forwards"    3

# ── Reference data ───────────────────────────────────────────────────
create_topic "reference-data.dividends"      3
create_topic "reference-data.credit-spreads" 3

# ── Market data ──────────────────────────────────────────────────────
create_topic "volatility.surfaces"   3
create_topic "correlation.matrices"  3

# ── Governance audit ─────────────────────────────────────────────────
create_topic "governance.audit"     3

# ── FIX gateway (ADR-0035) ───────────────────────────────────────────
# Inbound FIX 35=8/9/j events from venues. Partitioned by clOrdID for ordering;
# 30-day retention extends the 7-day default to support MiFID II RTS 28 / MAR
# audit-replay windows. fix_gateway.fix_message_log is the authoritative replay
# source for events older than 30 days.
create_topic_with_config "execution.reports" 12 \
  "retention.ms=2592000000" \
  "cleanup.policy=delete"

# ── Dead-letter queues (same REPLICATION_FACTOR as regular topics) ────
create_topic "trades.lifecycle.dlq"  1
create_topic "price.updates.dlq"    1
create_topic "risk.results.dlq"     1
create_topic "risk.anomalies.dlq"   1
create_topic "limits.breaches.dlq"  1
create_topic "governance.audit.dlq" 1

echo ""
echo "Topics created:"
$KAFKA_TOPICS --bootstrap-server "$BOOTSTRAP" --list

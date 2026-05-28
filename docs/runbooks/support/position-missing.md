# My position is not showing / a trade isn't in my book

Audience: L1 support, traders, operations.
Last revised: 2026-05-28

---

## Symptom

A trader reports that a trade they booked is not appearing in the Positions view, or that the
position quantity is incorrect (e.g. a 10,000-lot trade is missing but the original 5,000-lot
position is still showing). Variations include: "my position is gone," "the trade went through
but the book hasn't updated," or "I can see the trade ID in the confirmation but the position
is wrong."

---

## 30-second triage

- Ask the trader for the `tradeId` from their booking confirmation. Open the **Trade
  Lifecycle** dashboard (`/d/kinetix-trade-lifecycle`), set the `Book` filter to the affected
  book, and look at the **Recent Trade Events** panel. The trade should appear as a
  `TRADE_BOOKED` event. If it is absent, the event never reached audit-service.
- Check the **Audit Event Errors** stat panel (top-right of the Trade Lifecycle dashboard).
  A non-zero value (red) means audit-service is failing to process trade events — this will
  cause positions to lag.
- Run a quick DLQ check on `trades.lifecycle.dlq`. Any messages there mean trade events have
  been permanently shed after retry exhaustion, and those positions will never update without
  manual intervention.

---

## Investigation

### Step 1 — Confirm the trade exists in position-service

**Action:**

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/books/<bookId>/trades" \
  -H "Authorization: Bearer <TOKEN>" | jq '.[] | select(.tradeId == "<tradeId>")'
```

**What to look for:** The trade object with `status: BOOKED`, `instrumentId`, `quantity`,
`side`, and `tradedAt` timestamp. If the trade is absent, the position-service database does
not have it.

**Action — Query the database directly if the API returns nothing:**

```bash
kubectl exec -n kinetix deploy/kinetix-postgresql -- \
  psql -U kinetix -d kinetix -c "
    SELECT trade_id, portfolio_id, instrument_id, quantity, side, traded_at, created_at
    FROM trade_events
    WHERE trade_id = '<tradeId>';
  "
```

If the row is absent from `trade_events`, the trade was never persisted. Proceed to step 2.

---

### Step 2 — Check whether the trade event was published to trades.lifecycle

Position-service publishes a `TRADE_BOOKED` event to the `trades.lifecycle` Kafka topic
immediately after persisting a trade. If the trade is in the database but not reflected in the
position, the Kafka publish may have failed or the consumer may be lagging.

**Action (Loki) — Search for the booking log entry in position-service:**

```
{service_name="position-service"} |= `<tradeId>` | json
```

**What to look for:** A log line confirming the trade was persisted and the Kafka message was
published. If you see a database insert log but no Kafka publish log, the producer failed
silently — check position-service error logs.

**Action — Check consumer group lag for trades.lifecycle:**

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --describe --group position-service
```

Also check audit-service:

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --describe --all-groups | grep trades.lifecycle
```

**What to look for:** Lag greater than zero on `trades.lifecycle` for any consumer group
(position-service, audit-service). Lag means events are queued but not yet processed.

---

### Step 3 — Check the trades.lifecycle DLQ

**Action:**

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic trades.lifecycle.dlq \
    --from-beginning --max-messages 20
```

**What to look for:** Any message whose payload contains the `tradeId` in question. If found,
the trade event exhausted its retries when being processed by a consumer (usually audit-service
or a downstream consumer) and was parked in the DLQ. The event is not lost but will not be
processed until manually replayed.

**What it means:** Read the full payload and identify the consumer that rejected it. The most
common cause is a deserialisation error (event schema mismatch after a deployment) or a
database constraint violation in the consumer. Fix the root cause, then drain the DLQ
following the procedure in `docs/runbooks/zero-downtime-deployment.md`.

---

### Step 4 — Verify the audit chain entry for the trade

**Action (audit API):**

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/audit/events?tradeId=<tradeId>" \
  -H "Authorization: Bearer <TOKEN>" | jq .
```

**What to look for:** An audit event with `eventType: TRADE_BOOKED`, the correct `bookId`,
`tradeId`, and a `sequenceNumber`. The presence of this record confirms that audit-service
consumed the `trades.lifecycle` event and the trade is in the immutable audit chain.

If this record is absent but the trade is in the `trade_events` table, the audit event was
not consumed — check the `risk.audit` DLQ (audit-service consumes trade events published
there by the risk-orchestrator pipeline; the `trades.lifecycle` topic is consumed directly by
audit-service as well).

**Action (Loki) — Correlate via correlationId on Trade Lifecycle dashboard:**

Paste the `correlationId` (from the booking confirmation or the trader's browser network log)
into the **Correlation ID** textbox variable on the **Trade Lifecycle** dashboard. The
**correlationId Drill-through** panel at the bottom of the dashboard will show the full
journey of the trade across every service.

---

### Step 5 — Check position aggregation

If the trade exists in `trade_events` and the audit chain confirms it, but the position in
the UI shows the wrong quantity, the position aggregation may be stale or incorrect.

**Action:**

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/books/<bookId>/positions" \
  -H "Authorization: Bearer <TOKEN>" | jq '.[] | select(.instrumentId == "<instrumentId>")'
```

Compare the `quantity` and `unrealisedPnl` values against what the trader expects. If the
position exists but the quantity is wrong, there may be an amendment or cancellation event
that the trader is unaware of — check the audit trail for `TRADE_AMENDED` or
`TRADE_CANCELLED` events for this instrument.

---

## Common root causes

| Root cause | Presentation | Fix path |
|---|---|---|
| Trade booking API returned an error the trader ignored | Trade absent from `trade_events` | Trader must re-submit; provide the rejection reason from gateway logs |
| Kafka producer failure after DB insert | Trade in `trade_events` but no Kafka message; consumer lag = 0 | Restart position-service if Kafka producer is stuck; trade will be republished |
| Consumer lag on `trades.lifecycle` | Trade published but position/audit not yet updated | Monitor lag; should self-resolve within minutes; check for `KafkaConsumerStalled` alert |
| Trade event in `trades.lifecycle.dlq` | DLQ non-empty; trade missing from audit | Fix root cause; replay DLQ via zero-downtime-deployment runbook DLQ drain procedure |
| Trade amended/cancelled after booking | Position quantity different from booked quantity | Show trader the audit trail for all events on the instrument in the affected book |

---

## Resolution

For lag: monitor the consumer lag panel (Consumer Groups dashboard or `kafka_consumergroup_lag`
PromQL) and wait for it to drain. If lag has been at zero for 10 minutes and the position is
still missing, proceed to DLQ investigation.

For DLQ events: after fixing the root cause, replay following the DLQ drain procedure in
`docs/runbooks/zero-downtime-deployment.md`. After replay, confirm:

1. Lag returns to zero within 2 minutes.
2. The position appears in the UI.
3. The audit chain has the `TRADE_BOOKED` event.

---

## Escalation

| Condition | Escalate to | Context to provide |
|---|---|---|
| Trade absent from `trade_events` despite trader receiving a booking confirmation | **position-service** team | tradeId, bookId, timestamp, HTTP response code from booking endpoint |
| `trades.lifecycle.dlq` has messages that cannot be replayed cleanly | **position-service** team | DLQ payload, consumer error log, schema version if relevant |
| `KafkaConsumerStalled` alert firing for `trades.lifecycle` | **platform/kafka** team | Consumer group name, lag count, last committed offset timestamp |
| Audit chain broken (`valid: false` from `/api/v1/audit/verify`) | **audit-service** team | Verify response, sequence number of last valid record |

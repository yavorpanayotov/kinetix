# My P&L looks wrong

Audience: L1 support, risk managers, senior traders.
Last revised: 2026-05-28

---

## Symptom

A trader or risk manager reports that the unrealised P&L or total P&L shown in the UI does
not match their expectation. This typically surfaces as "my P&L is stuck," "my P&L jumped
without a trade," or "the P&L doesn't reflect the trade I just booked."

---

## 30-second triage

- Open the **Risk Run Health** dashboard (`/d/kinetix-risk-run-health`). Check the **VaR
  Runs** stat panel (top left) and **Failed Runs** stat panel (top right). If Failed Runs is
  non-zero or VaR Runs is zero for the current hour, the risk pipeline is the problem —
  see Investigation step 3.
- Open the **Trade Lifecycle** dashboard (`/d/kinetix-trade-lifecycle`), set the `Book`
  filter to the affected book, and check the **Trades Booked** stat. If the trade the trader
  is asking about is not reflected here, the trade has not been persisted — see Investigation
  step 1.
- Check the **Audit Event Errors** stat panel on the Trade Lifecycle dashboard. Any non-zero
  value (red background) means audit-service failed to consume a trade event, which can break
  the confirmed-booking signal the UI depends on.

---

## Investigation

### Step 1 — Confirm the trade was booked

**Action:** Query position-service for the trade directly.

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/books/<bookId>/trades" \
  -H "Authorization: Bearer <TOKEN>" | jq '.[] | select(.tradeId == "<tradeId>")'
```

**What to look for:** The trade must appear with `status: BOOKED`. If it is absent, the trade
was not persisted — the trader's booking request either failed or was rejected.

**What it means:** If absent, check the gateway response that the trader received. Ask them
for the `correlationId` from the UI (visible in browser dev tools or in the trade confirmation
dialog). Then proceed to the correlationId drill-through below.

---

### Step 2 — Verify the trade event reached audit-service

**Action (Loki):** Search the audit-service log stream for the trade event.

```
{service_name="audit-service"} |= `Audit event persisted` | json | tradeId=`<tradeId>`
```

Run this in Grafana Explore (datasource: Loki) or via the **correlationId Drill-through**
panel at the bottom of the **Trade Lifecycle** dashboard after pasting the `correlationId`.

**What to look for:** A log line with `eventType=TRADE_BOOKED` and the expected `tradeId` and
`bookId`. Note the timestamp — this is when the trade was confirmed in the audit chain.

**What it means:** If the line is absent, the `trades.lifecycle` Kafka message was not
consumed by audit-service. Check the consumer group lag (step 2b) and DLQ (step 2c).

**Action (2b) — Consumer group lag:**

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --describe --group position-service
```

Look at the lag on `trades.lifecycle`. Any lag above a few hundred messages after trade
booking means position-service is processing slowly or has stalled.

**Action (2c) — DLQ check:**

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic trades.lifecycle.dlq \
    --from-beginning --max-messages 20
```

Messages here mean trade events exhausted retries and were parked. Read the payload —
a deserialisation error or a database constraint violation is the usual cause.

---

### Step 3 — Check the latest risk revaluation timestamp

The P&L in the UI is derived from the most recent VaR calculation result stored in the
risk-orchestrator. If no calculation has run since the trade was booked, the P&L will not
reflect that trade.

**Action (Prometheus):** In Grafana, run the following PromQL query to find the age of the
last successful calculation for the affected book.

```promql
time() - max by (book_id) (timestamp(risk_var_calculation_total{book_id="<bookId>"}))
```

**What to look for:** A value above 3600 seconds means no VaR calculation has completed in
the last hour. The `RiskRunStale` alert fires at this threshold.

**Action (Loki) — Failed runs for this book:** On the **Risk Run Health** dashboard, use the
`Book` filter to scope the **Failed Runs Over Time** panel to the affected book. Expand any
log lines in the **Recent Failed Runs** panel (bottom of the dashboard) to see the error
details and `correlationId`.

---

### Step 4 — Check price freshness used by the last calculation

Even if a VaR calculation ran, it may have used stale prices, producing a P&L that does not
reflect current market moves.

**Action (Prometheus):**

```promql
max by (instrument_id) (price_staleness_seconds{instrument_id=~"<instrumentId>"})
```

**What to look for:** Any value above 60 seconds triggers the `PriceStale` alert; above 300
seconds triggers `PriceSeverelyStale`. If the instrument in the trader's position is stale,
the P&L calculation was based on an old price.

**Action — Confirm the price used in the last run:**

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/risk/var/<bookId>" \
  -H "Authorization: Bearer <TOKEN>" | jq '.priceTimestamp, .stale'
```

A `stale: true` field in the response means the risk-orchestrator served from its circuit
breaker cache rather than running a fresh calculation.

---

### Step 5 — Check the positions table directly

**Action (psql):**

```bash
kubectl exec -n kinetix deploy/kinetix-postgresql -- \
  psql -U kinetix -d kinetix -c "
    SELECT trade_id, instrument_id, quantity, price_amount, traded_at
    FROM trade_events
    WHERE portfolio_id = '<bookId>'
    ORDER BY traded_at DESC
    LIMIT 20;
  "
```

**What to look for:** The trade the trader booked should appear with the correct quantity and
price. If the `quantity` or `price_amount` is wrong, there is a data integrity problem —
escalate to position-service.

---

## Common root causes

| Root cause | Presentation | Fix path |
|---|---|---|
| Trade not booked (booking API error) | Trade absent from `/api/v1/books/<bookId>/trades` | Trader must re-submit; check gateway logs for the rejection reason |
| Trade event stuck in `trades.lifecycle.dlq` | Audit event absent; DLQ has messages | Investigate DLQ payload, fix the cause, replay via the DLQ drain procedure in the deployment runbook |
| VaR calculation not run since the trade | P&L unchanged after booking; `RiskRunStale` alert firing | Trigger a manual recalculation (see Resolution) |
| Stale prices used in last calculation | `PriceStale` / `PriceSeverelyStale` alert firing | Fix price-service / upstream feed; re-run calculation after prices refresh |
| Risk-engine circuit breaker open | `stale: true` on VaR response; `ServiceDown` alert for risk-engine | Restore risk-engine, reset circuit breaker by successful calculation |

---

## Resolution

**Trigger a manual VaR recalculation for the affected book:**

```bash
curl -s -X POST "https://api.kinetixrisk.ai/api/v1/risk/var/<bookId>" \
  -H "Authorization: Bearer <RISK_MANAGER_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"calculationType": "PARAMETRIC", "confidenceLevel": "CL_95", "timeHorizonDays": 1}'
```

A successful response returns the new VaR value and P&L breakdown. The `stale` field should
be `false`. The UI will reflect the new P&L on next refresh.

If the calculation returns `503 Service Unavailable`, the risk-engine circuit breaker is open
— escalate to the risk-engine team.

---

## Escalation

| Condition | Escalate to | Context to provide |
|---|---|---|
| Trade absent from position-service after confirmed booking | **position-service** team | trade ID, bookId, timestamp of booking, correlationId, gateway response code |
| DLQ has trade lifecycle messages that cannot be deserialised | **position-service** team | DLQ message payload, error log from audit-service |
| Risk-engine returning errors; circuit breaker open | **risk-engine** team | Failed run log lines, gRPC error details, book ID |
| Price data stale for more than 5 minutes | **market-data** team | `instrument_id`, current `price_staleness_seconds` value, upstream feed status |
| P&L incorrect after a correct calculation with fresh prices | **risk-orchestrator** team | VaR response body, position snapshot, price snapshot used in the run |

Do not close the ticket until you have confirmed that the P&L shown in the UI matches a
calculation that used fresh prices and reflects all current positions.

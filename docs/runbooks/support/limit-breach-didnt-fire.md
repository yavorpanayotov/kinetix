# A limit breach should have fired but I didn't get an alert

Audience: L1 support, risk managers, compliance.
Last revised: 2026-05-28

---

## Symptom

A risk manager or compliance officer reports that a trade was booked that should have
triggered a limit breach alert — notional, concentration, or VaR — but no alert appeared
in the UI notifications panel or was received via any other delivery channel. Alternatively,
a hard limit should have blocked the trade entirely but the trade went through. Typical
phrasing: "the trade went through even though we're over limit," "I should have been alerted
but wasn't," or "the breach fired but I never saw the notification."

This investigation has two distinct paths:

1. **Was the breach detected at all?** (position-service limit check → `limits.breaches` Kafka topic)
2. **Was the notification delivered?** (notification-service consumer → in-app delivery)

---

## 30-second triage

- Open the **Business Alerts & Events** dashboard (`/d/kinetix-business-alerts`). Check the
  **Limit Breaches** stat panel (top left). If it shows a non-zero count for the timeframe
  when the trade was booked, the breach was detected and a notification was raised — the
  problem is notification delivery, not detection.
- If the **Limit Breaches** panel shows zero for that window, either the limit check did not
  run (position-service issue) or the event was not published to `limits.breaches`.
- Check the **Limit Breaches Over Time (by severity)** time-series panel. Filter by the
  affected book using the `Book` dropdown variable. A gap in the chart at the time of the
  trade confirms no breach event was recorded.

---

## Investigation

### Step 1 — Confirm whether the breach was detected

The limit check runs synchronously in position-service during trade booking. When a HARD
limit is breached, position-service publishes a `LimitBreachEvent` to the `limits.breaches`
Kafka topic and returns HTTP 422 to the caller. When a SOFT limit is breached, the trade is
accepted but a warning is included in the response.

**Action — Check the booking response:**

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/books/<bookId>/trades/<tradeId>" \
  -H "Authorization: Bearer <TOKEN>" | jq '{status, warnings}'
```

If the trade was blocked by a HARD limit, it would not exist in the book — the booking
endpoint returned 422. Ask the trader whether they received a `422 Unprocessable Entity`
response, or whether the trade was accepted (200/201).

**Action (Loki) — Search position-service logs for the breach:**

```
{service_name="position-service"} |= `<tradeId>` | json
```

**What to look for:** A log line containing `LimitBreachException` or a line showing the
`limitType`, `currentValue`, and `limitValue` fields for the breach. If you see a `Booking
trade:` log line but no breach log, the limit check either was not configured for this book
or the check passed.

---

### Step 2 — Confirm the breach event was published to limits.breaches

Even if position-service detected a breach, the Kafka event might not have been published
due to a producer error.

**Action (Loki) — Check position-service Kafka producer:**

```
{service_name="position-service"} |~ `limits.breaches|LimitBreachEvent|limitBreachEventPublisher` | json
```

**Action — Check notification-service log for the breach arrival:**

```
{service_name="notification-service"} |= `Limit breach alert` | json | bookId=`<bookId>`
```

**What to look for:** A log line with the format:

```
Limit breach alert: bookId=<bookId> limitType=<limitType> severity=<CRITICAL|WARNING|INFO> message=<...>
```

If this log line exists in notification-service, the breach was received and processed. If
absent, the event never arrived at notification-service — check the `limits.breaches`
consumer group lag (step 3).

---

### Step 3 — Check consumer group lag on limits.breaches

**Action:**

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --describe --group notification-service-limit-breach-group
```

**What to look for:** Lag on `limits.breaches`. Lag above zero means notification-service
is behind processing breach events. Check when the lag started climbing — if it correlates
with the trade booking time, notification-service was slow or stalled when the breach fired.

**Action — DLQ check:**

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic limits.breaches.dlq \
    --from-beginning --max-messages 10
```

Messages in `limits.breaches.dlq` mean notification-service exhausted its retries processing
a breach event. The alert was never delivered. Read the payload to confirm whether the
affected `tradeId`/`bookId` is present.

---

### Step 4 — Confirm in-app delivery in notification-service

If the breach log line appears in notification-service but the alert was not visible in the
UI, the problem is at the in-app delivery layer.

**Action (Prometheus) — Check delivery failure counter:**

```promql
increase(notification_inapp_delivery_failures_total[1h])
```

A non-zero value means notification-service failed to persist the alert event to the database
for the UI to poll. Compare the timestamp to the trade booking time.

**Action (Prometheus) — Check delivered counter:**

```promql
increase(notification_inapp_messages_delivered_total{severity="CRITICAL"}[1h])
```

If this counter did not increment at the time of the breach but the Loki log line appeared,
the in-app persist failed.

**Action — Query the notifications API directly:**

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/notifications/alerts" \
  -H "Authorization: Bearer <TOKEN>" | jq '.[] | select(.bookId == "<bookId>" and .limitType == "<limitType>")'
```

If the alert is present in the API response but the trader did not see it in the UI, the
issue is a UI rendering or session problem, not a backend delivery problem.

**Action (Loki) — notification-service errors around breach time:**

```
{service_name="notification-service"} |~ `ERROR|WARN|exception|failed` | json
```

Filter by time to the 5 minutes around the breach.

---

### Step 5 — Verify the limit definition is configured

If no breach event was ever published and no log line appears in position-service, the limit
may not be configured for this book and limit type.

**Action:**

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/books/<bookId>/limits" \
  -H "Authorization: Bearer <RISK_MANAGER_TOKEN>" | jq '.'
```

**What to look for:** A limit definition for the expected `limitType` (e.g. `NOTIONAL`,
`VAR_95`, `CONCENTRATION`) with a non-null `hardLimit` or `softLimit` value. If absent, the
limit was never configured — this is a business configuration issue, not a system fault.

---

## Common root causes

| Root cause | Presentation | Fix path |
|---|---|---|
| Limit not configured for this book/type | No breach log in position-service; limit absent from `/api/v1/books/<bookId>/limits` | Risk manager must configure the limit via the UI or API |
| SOFT limit breached (trade allowed through) | Trade booked; `warnings` in booking response; notification-service shows INFO/WARNING breach | Expected behaviour; confirm with risk manager whether a HARD limit was intended |
| position-service Kafka producer failure | Breach log in position-service but no Loki line in notification-service; `limits.breaches.dlq` may have messages | Restart position-service producer; replay DLQ after fix |
| notification-service consumer stalled | Lag on `limits.breaches` for group `notification-service-limit-breach-group`; `KafkaConsumerStalled` alert | Restart notification-service; drain lag |
| In-app delivery DB failure | Loki line in notification-service but `notification_inapp_delivery_failures_total` rising | Check Postgres connectivity for notification-service; `DatabaseConnectionSaturation` alert |
| Alert present in API but not visible in UI | Alert returned by `/api/v1/notifications/alerts` but not rendered | UI session/cache issue; trader should refresh or re-login |

---

## Resolution

**If the event is in `limits.breaches.dlq`:** drain it after confirming notification-service
is healthy:

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic limits.breaches.dlq \
    --from-beginning --timeout-ms 5000 | \
kubectl exec -i -n kinetix deploy/kinetix-kafka -- \
  kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic limits.breaches
```

After replay, verify the breach alert appears via `GET /api/v1/notifications/alerts`.

**If the limit was not configured:** work with the risk manager to add the limit definition.
Re-run the trade (or simulate it via the what-if endpoint) to confirm the breach fires
correctly after configuration.

---

## Escalation

| Condition | Escalate to | Context to provide |
|---|---|---|
| Breach detected but event not published to `limits.breaches` | **position-service** team | tradeId, bookId, limitType, position-service log lines around booking time, Kafka producer error |
| `limits.breaches.dlq` accumulating; notification-service cannot process events | **notification-service** team | DLQ payload, error log from notification-service, consumer lag history |
| `notification_inapp_delivery_failures_total` rising; DB errors in notification-service | **notification-service** team | Prometheus counter value, Loki error lines, Postgres connection pool metrics |
| HARD limit breach: trade went through without being blocked | **position-service** team (P1) | tradeId, bookId, limitType, limit definition configuration, booking HTTP response code |

Note: the `LimitBreached` Prometheus alert described in some design documents does not yet
exist in `deploy/observability/alert-rules.yml`. The primary detection signal for missed
breach notification is the notification-service Loki log line (`Limit breach alert: ...`)
and the **Limit Breaches** panel on the **Business Alerts & Events** dashboard. This gap is
tracked for a future alerting improvement.

TODO: `notification_inapp_delivery_failures_total > 0` alert rule does not yet exist in
`alert-rules.yml` — a future improvement should add it. See kx-42wk.16 (Wire runbook_url
annotations into alert-rules.yml) for the related plan.

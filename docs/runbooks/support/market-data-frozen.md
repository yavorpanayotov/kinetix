# Market data looks frozen / prices not updating

Audience: L1 support, traders, risk managers.
Last revised: 2026-05-28

---

## Symptom

A trader reports that prices in the UI have not changed for an unusually long time, or that
the "last updated" timestamp next to a price is several minutes or more in the past. Typical
phrasing: "prices are frozen," "the feed has dropped," "my FX rates haven't moved all
morning," or "VaR is using yesterday's prices." This may be reported alongside a stale P&L
complaint (see `pnl-looks-wrong.md`).

---

## 30-second triage

- In Grafana, run the following PromQL query. Any result above zero confirms staleness:

  ```promql
  max by (instrument_id) (price_staleness_seconds) > 60
  ```

  If the `PriceStale` or `PriceSeverelyStale` alerts are already firing in the Alerting view,
  the platform has already detected the problem — go to step 1.

- Check the **price-service** pod is up:

  ```bash
  kubectl get pods -n kinetix -l app=kinetix-price-service
  ```

  If it is in `CrashLoopBackOff` or not `Running`, the entire feed is down — skip to
  step 3.

- Ask the trader: is it all instruments frozen, or just specific ones? A single-instrument
  freeze points to an upstream source problem; a complete freeze points to price-service or
  the ingestion path.

---

## Investigation

### Step 1 — Identify which instruments are stale and by how much

**Action (Prometheus):** In Grafana Explore (datasource: Prometheus), run:

```promql
sort_desc(price_staleness_seconds)
```

This returns the current staleness in seconds for every instrument tracked by price-service,
sorted worst-first. The `instrument_id` label identifies the affected instrument(s).

**Thresholds:**
- `price_staleness_seconds > 60` for 5 minutes → `PriceStale` alert (WARNING)
- `price_staleness_seconds > 300` for 5 minutes → `PriceSeverelyStale` alert (CRITICAL)

**What it means:** If only a handful of instruments are stale, the problem is likely at the
upstream feed for those instruments (e.g. a specific data vendor feed). If all instruments
are stale simultaneously, price-service itself or its ingestion pipeline is the problem.

---

### Step 2 — Check when the last price was ingested for a specific instrument

**Action:** Query the `market_data` table in the price-service database.

```bash
kubectl exec -n kinetix deploy/kinetix-postgresql -- \
  psql -U kinetix -d kinetix -c "
    SELECT instrument_id, price_amount, price_currency, timestamp, source
    FROM market_data
    WHERE instrument_id = '<instrumentId>'
    ORDER BY timestamp DESC
    LIMIT 5;
  "
```

**What to look for:** The most recent `timestamp`. If it is more than a minute old during
trading hours, price-service has stopped receiving or persisting updates for this instrument.
The `source` field tells you which upstream feed last provided a price.

**Action — Check via the price API:**

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/prices/<instrumentId>" \
  -H "Authorization: Bearer <TOKEN>" | jq '.price, .timestamp, .source'
```

Compare the `timestamp` in the response to the current time.

---

### Step 3 — Check price-service health and logs

**Action:**

```bash
kubectl logs -n kinetix deployment/kinetix-price-service --tail=100
```

**What to look for:**

- Connection errors to upstream feed endpoints (e.g. `Connection refused`, `timeout`,
  `UnknownHostException`).
- Kafka producer errors if price-service publishes to `price.updates` — a stalled producer
  means downstream consumers (risk-orchestrator cache) will also be stale.
- Database write errors — if price-service cannot persist to `market_data`, the
  `price_staleness_seconds` gauge will climb even if prices are arriving at the service.

**Action (Loki):**

```
{service_name="price-service"} |~ `ERROR|WARN|exception` | json
```

Scan for errors in the last 15 minutes. A burst of connection errors at a specific timestamp
identifies when the feed dropped.

---

### Step 4 — Check the price.updates Kafka topic consumer lag

Risk-orchestrator consumes the `price.updates` topic to maintain its internal price cache.
If it is lagging, the risk calculations will use stale prices even after price-service
recovers.

**Action:**

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --describe --group risk-orchestrator
```

**What to look for:** Lag on `price.updates`. Any lag above zero here means risk-orchestrator
has not yet consumed the latest prices. Monitor — it should drain within seconds once
price-service is healthy.

**Action — DLQ check:**

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic price.updates.dlq \
    --from-beginning --max-messages 5
```

Messages here indicate price events were rejected by a downstream consumer (likely a
deserialisation error after a schema change).

---

### Step 5 — Correlate with risk calculations

If prices have been stale, the VaR calculations that ran during that window used stale inputs.
Identify the window by checking when `price_staleness_seconds` exceeded the threshold and when
it returned to zero. Any risk run during that window produced figures based on stale prices.

**Action:** Cross-reference the staleness window with the **VaR Run Rate** panel on the
**Risk Run Health** dashboard (`/d/kinetix-risk-run-health`). If runs occurred during the
stale window, notify the risk manager and trigger a fresh calculation once prices recover (see
`var-is-stale.md` Resolution section for the re-run command).

---

## Common root causes

| Root cause | Presentation | Fix path |
|---|---|---|
| Upstream feed connection dropped | All instruments from one source stale; connection errors in price-service logs | Investigate network path to the feed; restart price-service after feed restores |
| price-service pod crashed | All `price_staleness_seconds` climbing; `ServiceDown` alert firing | Restart pod (`kubectl rollout restart`); check for OOM or config error |
| Database write failure in price-service | Prices arriving at service but `market_data` not updated; DB errors in logs | Check Postgres connectivity and disk space; `DatabaseConnectionSaturation` alert |
| Specific instrument delisted / feed removed | Single instrument stale; all others healthy | Confirm with the data vendor; remove or replace the feed source |
| Kafka producer backing up | `price.updates` producer errors in logs; downstream consumer using old cache | Check Kafka broker health; restart price-service producer |

---

## Resolution

**After the feed recovers:** price-service will automatically resume publishing price updates.
The `price_staleness_seconds` gauge will drop as new prices arrive and the metric is
recalculated.

Monitor in Grafana Explore:

```promql
max by (instrument_id) (price_staleness_seconds{instrument_id=~"<affectedInstruments>"})
```

Wait until all affected instruments return to below 60 seconds before confirming the issue
is resolved.

**If risk calculations ran during the stale window:** trigger a fresh VaR calculation for all
affected books once prices have recovered (see `var-is-stale.md` for the re-run curl command).
Notify the risk manager of the affected window so they can assess whether any risk decisions
were made on stale data.

**If price-service needs a restart:**

```bash
kubectl rollout restart deployment/kinetix-price-service -n kinetix
kubectl rollout status deployment/kinetix-price-service -n kinetix
```

After restart, verify `price_staleness_seconds` is declining within 60 seconds.

---

## Escalation

| Condition | Escalate to | Context to provide |
|---|---|---|
| Feed connection failures persisting after price-service restart | **market-data** team | Which instruments/sources affected, error messages from price-service logs, staleness duration |
| price-service OOMKilled or crash-looping | **platform** team | Pod restart count, memory usage before crash, Loki error lines |
| `price.updates.dlq` accumulating; risk-orchestrator cache not refreshing | **risk-orchestrator** team | DLQ payload sample, lag count, which instruments affected |
| Stale prices used in completed VaR runs | **risk manager** for affected books | Books affected, staleness window (start/end times), risk run IDs from the Risk Run Health dashboard |

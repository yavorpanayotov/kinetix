# My VaR is stale / not updating

Audience: L1 support, risk managers.
Last revised: 2026-05-28

---

## Symptom

A risk manager reports that the VaR figure on the dashboard has not changed for an extended
period, or that the timestamp next to the VaR value is older than expected. Typical phrasing:
"VaR hasn't moved all morning," "the VaR is the same as yesterday," or "my risk numbers look
frozen." The UI shows either an old timestamp or a `stale: true` indicator on the VaR card.

---

## 30-second triage

- In Grafana, open the **Risk Run Health** dashboard (`/d/kinetix-risk-run-health`). Check
  the top row: **VaR Runs** should be non-zero for the current hour; **Failed Runs** should
  be zero (green background). A non-zero Failed Runs count (red background) confirms the
  pipeline is erroring.
- Check the **VaR Latency p95** stat panel. A value above 15s (red) means calculations are
  running but are extremely slow — likely a risk-engine resource issue rather than a full
  outage.
- Check whether the `RiskRunStale` or `RiskCalculationSlow` alerts are firing in the
  Grafana Alerting view. `RiskRunStale` fires when no completed calculation has been recorded
  for a book in over one hour.

---

## Investigation

### Step 1 — Confirm no calculation has completed recently

**Action (Prometheus):** Query the age of the last successful VaR run for the affected book.

```promql
time() - max by (book_id) (timestamp(risk_var_calculation_total{book_id="<bookId>"}))
```

**What to look for:** A value above 3600 (seconds) means the `RiskRunStale` alert threshold
has been crossed. A value of `NaN` means no calculation has ever been recorded for this book
in the current Prometheus retention window — the book may be new or the metric series may
have been reset by a pod restart.

**Action — Check the current VaR response:**

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/risk/var/<bookId>" \
  -H "Authorization: Bearer <TOKEN>" | jq '{varValue: .varValue, calculatedAt: .calculatedAt, stale: .stale}'
```

A `stale: true` response means the risk-orchestrator served the VaR from its in-memory
circuit breaker cache because the last call to the risk-engine failed or timed out.

---

### Step 2 — Check for failed runs in audit-service

The primary failure signal for VaR runs is the `RISK_CALCULATION_FAILED` governance audit
event persisted by audit-service. There is currently no Prometheus counter for this signal
(see note at end of runbook); Loki is the authoritative source.

**Action (Loki):** In Grafana Explore or on the **Risk Run Health** dashboard's **Recent
Failed Runs** panel (bottom of the dashboard), run:

```
{service_name="audit-service"} |= `Governance audit event persisted` |= `eventType=RISK_CALCULATION_FAILED` | json | bookId=`<bookId>`
```

**What to look for:** Log lines with structured fields `bookId`, `correlationId`, and
`details` — the `details` field contains the exception message from the failed calculation.
Note the timestamp: if there is a cluster of failures at a specific time, check what changed
at that time (deployment, market data interruption, portfolio size spike).

**Action (audit chain verification):**

```bash
curl -s "https://api.kinetixrisk.ai/api/v1/audit/verify" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | jq .
```

Expected: `{ "valid": true, "checkedRecords": <N> }`. A broken chain (`valid: false`) is a
separate P1 incident — escalate immediately to audit-service.

---

### Step 3 — Check risk-orchestrator logs for gRPC errors

Failed VaR runs are almost always caused by a gRPC error from the risk-engine: timeout,
resource exhaustion, or a model error on a specific position.

**Action (Loki):**

```
{service_name="risk-orchestrator"} |= `RISK_CALCULATION_FAILED` | json
```

Also search for gRPC-specific errors:

```
{service_name="risk-orchestrator"} |~ `DEADLINE_EXCEEDED|UNAVAILABLE|StatusRuntimeException` | json
```

**What to look for:** `DEADLINE_EXCEEDED` means the risk-engine took longer than the gRPC
deadline — typically caused by Monte Carlo simulation with too many paths, or by a very large
position count. `UNAVAILABLE` means the risk-engine pod is down or not responding.

**Action (Prometheus) — Check calculation latency trend:**

```promql
histogram_quantile(0.95, sum(rate(risk_var_calculation_duration_seconds_bucket[5m])) by (le))
```

If p95 is climbing toward the `RiskCalculationSlow` threshold (30s), the risk-engine is
struggling but not fully failing. Check risk-engine CPU and memory.

---

### Step 4 — Check risk-engine pod health

**Action:**

```bash
kubectl get pods -n kinetix -l app=kinetix-risk-engine
kubectl logs -n kinetix deployment/kinetix-risk-engine --tail=50
```

**What to look for:** Pods in `CrashLoopBackOff` or `OOMKilled` state. Python OOM is the
most common cause of risk-engine failures when portfolio size increases suddenly — the Monte
Carlo simulation allocates large numpy arrays proportional to position count times path count.

**Action (Prometheus) — Python process memory:**

```promql
process_resident_memory_bytes{job="risk-engine"} / 1e9
```

Values above ~3 GB on a 4 GB limit container indicate memory pressure.

---

### Step 5 — Check the risk.audit DLQ

If the audit-service is not recording `RISK_CALCULATION_FAILED` events, check whether the
`risk.audit` Kafka topic's DLQ is accumulating messages.

**Action:**

```bash
kubectl exec -n kinetix deploy/kinetix-kafka -- \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic risk.audit.dlq \
    --from-beginning --max-messages 10
```

**What it means:** Messages in `risk.audit.dlq` mean governance audit events from
risk-orchestrator were not consumed by audit-service, which means the failure history is
incomplete. Drain the DLQ after resolving the root cause.

---

## Common root causes

| Root cause | Presentation | Fix path |
|---|---|---|
| Risk-engine pod OOMKilled | `OOMKilled` in `kubectl get pods`; gRPC `UNAVAILABLE` in orchestrator logs | Increase risk-engine memory limit; reduce Monte Carlo path count; escalate to risk-engine team |
| gRPC deadline exceeded on large portfolio | `DEADLINE_EXCEEDED` in orchestrator logs; p95 latency climbing | Increase gRPC timeout configuration or reduce simulation paths; escalate to risk-engine team |
| Scheduled job missed (SOD window) | `hourly_var_summary` shows zero `job_count` for current hour | Trigger manual recalculation; check deployment runbook for SOD blackout window |
| Circuit breaker open | `stale: true` on VaR response; 5+ consecutive failed gRPC calls | Restore risk-engine; first successful calculation resets the circuit breaker automatically |
| Risk-engine not deployed / pod not ready | `ServiceDown` alert firing for risk-engine | Check pod status; redeploy following the zero-downtime deployment runbook |

---

## Resolution

**Trigger a manual on-demand VaR calculation:**

```bash
curl -s -X POST "https://api.kinetixrisk.ai/api/v1/risk/var/<bookId>" \
  -H "Authorization: Bearer <RISK_MANAGER_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"calculationType": "PARAMETRIC", "confidenceLevel": "CL_95", "timeHorizonDays": 1}'
```

Monitor the response. If `stale: false` and a `varValue` is returned, the calculation
succeeded and the UI will update. If you receive `503 Service Unavailable`, the circuit
breaker is still open — the risk-engine must be restored first.

After a successful calculation, re-check the **Risk Run Health** dashboard. The **Failed
Runs** counter should have stopped growing and **VaR Runs** should increment.

---

## Escalation

| Condition | Escalate to | Context to provide |
|---|---|---|
| Risk-engine pod OOMKilled or crash-looping | **risk-engine** team | Pod restart count, OOM timestamp, current portfolio size for affected book |
| gRPC `DEADLINE_EXCEEDED` persisting after pod is healthy | **risk-engine** team | p95 latency trend, book ID, number of positions, Monte Carlo path config |
| `valid: false` on audit chain verification | **audit-service** team | Verify response body, timestamp of last valid record |
| `risk.audit.dlq` accumulating; audit failures persisting | **audit-service** team | DLQ message count and payload sample |

---

## Observability note

There is currently no Prometheus counter for `RISK_CALCULATION_FAILED` events — the metric
`risk_calculation_failed_total` is defined in `alert-rules.yml` as a commented-out rule
pending a counter being emitted by risk-orchestrator. Until that counter exists, the primary
failure signal is in audit-service Loki logs (`eventType=RISK_CALCULATION_FAILED`) and in
the `risk.audit` Kafka topic. The `RiskRunStale` alert (based on
`risk_var_calculation_total` timestamp) is the best real-time proxy for "is VaR running?"
See issue tracker for the planned `risk_calculation_failed_total` counter addition.

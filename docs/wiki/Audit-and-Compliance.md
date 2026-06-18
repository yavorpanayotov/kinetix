# Audit and Compliance

Kinetix is designed under the assumption that every significant action must be **reconstructible** and **tamper-evident**, with controls strong enough to satisfy a model-risk audit. Three mechanisms work together:

1. **Hash-chained immutable audit trail** — every significant event is hashed into an append-only chain.
2. **Run manifests** — every risk calculation captures inputs and is replayable bit-for-bit.
3. **Four-eyes governance** — preparer cannot be approver for high-impact actions (EOD promotion, regulatory submission, scenario approval, model promotion).

## Hash-chained audit trail

ADR: [0017](https://github.com/yavorpanayotov/kinetix/blob/main/docs/adr/0017-hash-chained-audit-trail.md)
Service: [`audit-service/`](https://github.com/yavorpanayotov/kinetix/tree/main/audit-service)

### Chain protocol

Each audit event row stores:

```
record_hash     CHAR(64)   -- SHA-256(payload || previous_hash)
previous_hash   CHAR(64)   -- the previous row's record_hash
sequence_number BIGINT     -- monotonic, strictly increasing
event_payload   JSONB      -- canonical event body
event_type      VARCHAR
correlation_id  UUID
created_at      TIMESTAMPTZ
```

Adding event `N`:

1. Read the most recent row's `record_hash` (this is `previous_hash`).
2. Canonicalise the event payload (key-sorted JSON, no whitespace).
3. Compute `record_hash = SHA-256(payload || previous_hash)`.
4. Insert with `sequence_number = max(seq) + 1`.

### Concurrent writes — `pg_advisory_xact_lock`

Implementation: `AuditHasher.kt`, `ExposedAuditEventRepository.kt`

Two concurrent writers reading the same `previous_hash` would fork the chain. To prevent this without serialising every write across unrelated subjects, the repository acquires a `pg_advisory_xact_lock` keyed on the chain ID for the duration of the transaction:

```kotlin
exec("SELECT pg_advisory_xact_lock(?)", chainKey)
// read previous_hash
// compute record_hash
// insert
// commit releases the lock
```

This gives us:

- Strict serialisation **per chain** (chains can be partitioned, e.g. per service or per subject)
- No global write lock — independent chains progress in parallel
- Forks become structurally impossible

### Verification

A verifier can replay the chain from genesis:

```
expected_hash = SHA-256(payload[N] || expected_hash_prev)
assert expected_hash == record_hash[N]
```

A break anywhere in the chain is provable. Verification tooling lives alongside the audit-service repository code.

### Retention

- TimescaleDB hypertable, partitioned by `created_at`
- 7-year retention policy
- Continuous aggregates for query performance (count-by-type, count-by-actor)
- Compression on partitions older than 30 days

### What gets chained

| Category | Events |
|---|---|
| Trades | book, amend, cancel, settle |
| Risk runs | start, complete, failure, promote to OFFICIAL_EOD |
| Model governance | submit, validate, approve, retire |
| Limits | create, update, breach, temporary increase request/approval |
| Scenarios | create, submit, approve, retire, execute |
| Regulatory | calculation, submission preparation, submission approval, submission |
| Auth | login, role grant, role revoke |

Two production Kafka topics ship audit events:

- `risk.audit` — risk-orchestrator → audit-service
- `governance.audit` — regulatory-service → audit-service
- `kinetix.audit.chain` — internal audit-service chain progression

## Run manifests

ADR: [0018](https://github.com/yavorpanayotov/kinetix/blob/main/docs/adr/0018-run-reproducibility-via-manifests.md)

Every risk calculation captures a manifest sufficient to **replay the run bit-for-bit**:

```
RunManifest {
  runId, parentRunId
  valuationTimestamp
  positionSetSnapshot   -- hash + reference
  marketDataSnapshot {
    prices, yieldCurves, forwardCurves, volSurfaces, correlationMatrices
  }
  monteCarloSeed
  codeVersion           -- git SHA
  modelVersions         -- per asset class
  inputHash, outputHash
}
```

This unlocks:

- **Reproducibility** — give the manifest, get the same answer
- **Compare runs** — diff two manifests to explain "why did VaR move?"
- **Historical backtesting** — replay historical portfolios with current code, or current portfolios with historical models
- **Audit demand** — regulator asks "show me how you computed yesterday's VaR" → manifest + chained audit event + git SHA

## EOD promotion governance

ADR: [0019](https://github.com/yavorpanayotov/kinetix/blob/main/docs/adr/0019-official-eod-labeling-with-promotion-governance.md)

Scheduled VaR runs land with the label `SCHEDULED`. They are **not** official until explicitly promoted.

```
SCHEDULED → COMPLETED → OFFICIAL_EOD
                     └→ REJECTED
```

Rules:

- Only `COMPLETED` runs (all positions valued, no errors) are eligible for promotion
- Promotion is a separate, audited action
- Four-eyes rule: the user who initiated the run cannot promote it
- Promoted runs are immutable — they cannot be demoted, only superseded
- Reports and regulatory submissions reference `OFFICIAL_EOD` runs only

This eliminates a class of race conditions where end-of-day reports could disagree depending on which scheduled run finished last.

## Four-eyes approval workflows

The four-eyes principle (preparer ≠ approver) applies to:

| Workflow | Preparer role | Approver role |
|---|---|---|
| EOD promotion | TRADER / RISK_MANAGER | RISK_MANAGER (different user) |
| Regulatory submission | COMPLIANCE | COMPLIANCE (different user) |
| Scenario approval | RISK_MANAGER | RISK_MANAGER (different user) |
| Model promotion (draft → validated → approved) | QUANT | RISK_MANAGER |
| Temporary limit increase | TRADER / RISK_MANAGER | RISK_MANAGER |

Implementation lives in the **regulatory-service** (model governance + submissions) and **position-service** (limit increases). Every transition is audit-chained.

## Model governance

Model registry with four-stage lifecycle:

```
DRAFT → VALIDATED → APPROVED → RETIRED
              ↓         ↓
          REJECTED   SUPERSEDED
```

Each transition is audit-chained with:

- Model identifier and version
- Test pack results (backtesting, sensitivity)
- Reviewer comments
- Effective date for production use

Models cannot be used in production unless `APPROVED`.

## Regulatory submissions

Source: [`regulatory-service/`](https://github.com/yavorpanayotov/kinetix/tree/main/regulatory-service)

Submission lifecycle:

```
PREPARED → REVIEWED → APPROVED → SUBMITTED → ACKNOWLEDGED
                  ↓
              REJECTED
```

- **PREPARED:** compliance officer assembles outputs (FRTB capital, backtesting results, exposure reports)
- **REVIEWED:** second compliance officer (four-eyes) reviews
- **APPROVED:** transitions to ready-to-send
- **SUBMITTED:** payload pushed to regulator endpoint or marked for manual upload
- **ACKNOWLEDGED:** receipt logged

Export templates: CSV and XBRL.

## Backtesting

VaR backtests are auto-run and persisted. Basel traffic-light evaluation:

| Exceptions / 250 days | Zone | Capital multiplier impact |
|---|---|---|
| 0–4 | Green | No multiplier change |
| 5–9 | Yellow | Multiplier scales 0.40 → 0.85 |
| 10+ | Red | Multiplier +1.00; investigation triggered |

Statistical tests:

- **Kupiec POF** — unconditional coverage (right number of exceptions)
- **Christoffersen Independence** — exceptions should not cluster
- **Christoffersen Combined** — both conditions together

Each backtest is audit-chained with the result and any model investigation that follows.

## Correlation IDs

ADR: [0022](https://github.com/yavorpanayotov/kinetix/blob/main/docs/adr/0022-correlation-id-propagation.md)

Every audit row stores the `correlation_id` of the originating request. From an audit row, you can:

1. Find the originating UI click (Kafka header + HTTP access log)
2. Trace through the orchestration pipeline (Tempo span tree)
3. Find the related run manifest
4. Find sibling audit rows from the same workflow

This is what makes "show me everything that happened because user X clicked Submit at 14:32:01" a one-query investigation.

## What auditors and regulators get

A capability summary suitable for sharing:

- **Trade-level audit:** every booking, amendment, cancellation chained with hash and correlation ID
- **Risk-run reproducibility:** any historical VaR result can be replayed bit-for-bit from manifest
- **Model governance:** four-stage lifecycle, four-eyes promotion, full audit
- **Regulatory submissions:** four-eyes approval, XBRL templates, audit-chained
- **Backtest results:** Kupiec + Christoffersen with Basel traffic-light zones, persisted and chained
- **Limit changes:** every change to limits and every breach chained
- **Code version:** every risk run captures the git SHA of the engine that produced it
- **Retention:** 7 years on TimescaleDB hypertables with compression

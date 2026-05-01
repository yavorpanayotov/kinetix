# ADR-0035: Fix-Gateway Service Extraction

## Status
Proposed

## Context

`position-service` currently mixes two responsibilities:

1. **Position and order state management** — book a trade, update positions, hold the order book, persist fills, manage netting sets, expose REST endpoints for the UI and gateway.
2. **FIX protocol gymnastics** — `FIXExecutionReportProcessor` parses `ExecutionReport` (35=8) messages, emits `BookTradeCommand` events, and (via planned work) needs to send `OrderCancel` (35=F), correlate `OrderCancelReject` (35=9), manage FIX session state, and handle MsgType-specific edge cases (rejects, replaces, partial fills, busts).

The two responsibilities have very different non-functional profiles:

| | Position state | FIX protocol |
|---|---|---|
| Failure mode | Persistent rollback | Session re-establish |
| Test infrastructure | Postgres + Kafka | FIX simulator (QuickFIX/J) |
| Deploy cadence | Tied to risk model + pricing | Tied to venue protocol changes |
| Latency budget | ms (gateway-bound) | sub-ms (venue connectivity) |
| Owner | Quant/risk engineering | Execution / trading-tech |
| Statefulness | Postgres-backed | Session log + sequence numbers |

The audit item A-13 (`ExpireDayOrder`) exposed this split. Implementing day-order expiry needs:

- A new `TimeInForce` field on `Order` (state — fits position-service)
- A recurring scheduler with venue-specific cutoff times (state-adjacent — fits position-service)
- A FIX `OrderCancel` (35=F) emitter with `OrderCancelReject` (35=9) correlation handling (protocol — does **not** fit position-service)

Doing all three inside `position-service` would entangle FIX session lifecycle, sequence-number management, and venue connectivity with the position store. Adding the FIX-cancel emitter directly to `position-service` would also make every position-service deployment a FIX-session restart, which is operationally unacceptable for execution.

A third concern: the platform will eventually need additional venue-side capabilities — order placement, replace-cancel-replace flows, drop-copy ingestion, post-trade affirmation/allocation messaging (35=J/35=N), and venue-specific oddities (e.g. NASDAQ ITCH/OUCH, LSE Millennium, TSE arrowhead). All of these belong in a single, focused FIX/venue gateway, not sprinkled across services.

## Decision

Introduce a new microservice, **`fix-gateway`**, that owns all FIX/venue-protocol concerns. `position-service` retains all order/position state and converses with `fix-gateway` via gRPC and Kafka.

### Service boundaries

**`fix-gateway` owns:**

- FIX session establishment, logon/logout, sequence number management (per venue).
- Outbound message construction: `NewOrderSingle` (35=D), `OrderCancelRequest` (35=F), `OrderCancelReplaceRequest` (35=G).
- Inbound message parsing: `ExecutionReport` (35=8), `OrderCancelReject` (35=9), `BusinessMessageReject` (35=j).
- Venue-side correlation between our `clOrdID` and the venue's `OrderID`.
- Venue cutoff registry (NYSE 16:00 ET, NASDAQ 16:00 ET, LSE 16:30 GMT, TSE 15:00 JST, HKEX 16:00 HKT — all five wired at launch per A-13 decision).
- Venue trading-calendar awareness (cutoffs respect venue holidays, half-days, observed-DST).
- Drop-copy session ingestion (future).

**`position-service` retains:**

- `Order` and `Trade` entities, persistence, REST endpoints.
- `TimeInForce` field on `Order` (state, not protocol).
- `OrderStatus` lifecycle including `EXPIRED`.
- The `ExpireDayOrder` rule's *state transition* (mark order as EXPIRED, persist, publish `OrderExpired` event).
- The `ScheduledOrderExpirySweeper` job that runs the transition.
- All position computation and netting logic.

### Order placement flow

Today (synchronous, position-service-internal):

```
UI → Gateway → position-service.submitOrder → FIXExecutionReportProcessor (intra-process) → Kafka(trade.events)
```

After:

```
UI → Gateway → position-service.submitOrder
              → fix-gateway.PlaceOrder (gRPC, sync request/reply)
                 → FIX 35=D to venue
                 → venue 35=8 (Pending New) → Kafka(execution.reports)
              → return to UI with clOrdID + status
              ← later: position-service consumes execution.reports → updates Order/Position state
```

`PlaceOrder` is the only synchronous gRPC call — the trader needs the venue's `Pending New` reply on the same call. All subsequent fills, cancels, replaces, and rejects flow asynchronously via Kafka topic `execution.reports`.

### Day-order expiry flow (A-13)

```
ScheduledOrderExpirySweeper (in position-service, every minute)
  ↓ query: orders where time_in_force = DAY and status in (OPEN, PARTIALLY_FILLED)
  ↓        and venue.cutoff_at(today) <= now
  ↓ for each candidate:
  ↓   fix-gateway.CancelOrder(clOrdID, reason: DAY_ORDER_EXPIRY)  (gRPC, fire-and-forget)
  ↓   transition status: OPEN/PARTIALLY_FILLED → EXPIRED
  ↓   publish: OrderExpired event to audit chain
  ↓
fix-gateway.CancelOrder
  ↓ build 35=F OrderCancelRequest
  ↓ submit on the appropriate venue's FIX session
  ↓ correlate venue's response (35=8 Cancelled OR 35=9 OrderCancelReject)
  ↓ publish to Kafka(execution.reports)
```

State transition is idempotent — if the FIX cancel comes back as a reject (e.g., already filled at the venue), the order's eventual state is determined by the next 35=8 the venue sends, not by our optimistic `EXPIRED` write. That race resolves by treating venue execution reports as authoritative.

### GTD support (A-13 in scope)

`Order` adds `expires_at: Timestamp?` (nullable; non-null only when `time_in_force = GTD`). The same `ScheduledOrderExpirySweeper` extends its query to also expire `GTD` orders where `expires_at <= now`. Validation rules:

- `expires_at` must be in the future at submit time.
- `expires_at` cannot exceed venue's max-GTD horizon (typically 90 days; per-venue setting in cutoff registry).
- UI date picker for `GTD` is a follow-on task in the UI repo, but the API field is in scope.

### gRPC contract sketch

```proto
service FixGateway {
  // Synchronous: trader needs the Pending New reply.
  rpc PlaceOrder(PlaceOrderRequest) returns (PlaceOrderResponse);

  // Fire-and-forget: cancel ack flows on Kafka(execution.reports).
  rpc CancelOrder(CancelOrderRequest) returns (CancelOrderResponse);

  // Replace = atomic cancel-replace at venue level.
  rpc ReplaceOrder(ReplaceOrderRequest) returns (ReplaceOrderResponse);
}

message CancelOrderRequest {
  string cl_ord_id = 1;
  string venue = 2;
  CancelReason reason = 3; // DAY_ORDER_EXPIRY, USER_INITIATED, RISK_LIMIT_BREACH, etc.
}
```

### Kafka topics

- **`execution.reports`** (new). Source of truth for downstream consumers (`position-service`, audit-service, notification-service). Replaces the in-process flow currently routed through `FIXExecutionReportProcessor`. Partition by `clOrdID` so per-order events stay ordered.

## Trade-offs

### Positive

- Clean separation: state changes in `position-service`, protocol changes in `fix-gateway`. Each service can be deployed without disrupting the other.
- FIX session lifecycle is no longer entangled with position-service deploys.
- Test isolation: `position-service` tests use the Kafka in-memory pattern; `fix-gateway` tests use a QuickFIX/J in-memory acceptor.
- Single home for venue-specific oddities. Adding a new venue means adding a session config and a cutoff entry — not a new code path scattered across services.
- The day-order-expiry flow has a clear, testable boundary: `position-service` can be tested by stubbing the gRPC client; `fix-gateway` can be tested by stubbing the FIX session.

### Negative

- New service to deploy, monitor, alert on, and operationally own.
- Cross-service async flow is harder to debug than the current in-process pipeline. Mitigation: `correlation_id` propagation (ADR-0022) end-to-end, with `clOrdID` as a secondary join key.
- `position-service` and `fix-gateway` must agree on `clOrdID` generation. Decision: `position-service` mints `clOrdID` (same as today); `fix-gateway` is given it on `PlaceOrder`.
- One additional network hop on the order-submit path. Latency budget impact: target adds ≤2ms p95 for the gRPC round-trip on the same cluster — acceptable.

### Alternatives considered

- **Keep FIX inside position-service.** Rejected: every FIX session restart becomes a position-service restart, which blocks unrelated deployments. Also entangles execution-tech and risk-engineering ownership.
- **Embed FIX in a library used by position-service.** Reduces the deployment-coupling problem but keeps the test/test-infrastructure problem (Postgres + FIX in one test suite is brittle and slow). Also doesn't help with future venues that need their own session-process.
- **Use an existing OMS as fix-gateway** (e.g. a vendor product). Out of scope for this ADR; if procurement direction shifts, this ADR doesn't preclude it — it just defines the API surface that the eventual gateway must expose.

## Implementation phases

1. **Skeleton service** — Gradle module, Ktor health endpoint, gRPC stub, Helm chart, observability wiring (Prometheus/Loki/Tempo per ADR-0008). No FIX yet.
2. **Outbound FIX-cancel emitter** — `CancelOrder` RPC + QuickFIX/J initiator + venue-cutoff registry (5 venues at launch). Sufficient for A-13.
3. **Migrate `FIXExecutionReportProcessor`** out of position-service — `fix-gateway` becomes the inbound 35=8 consumer; events flow on Kafka `execution.reports`. position-service consumes from there.
4. **Outbound order placement** — `PlaceOrder` RPC migrating today's order-submit path. Largest user-visible change; needs careful staged rollout.

A-13 needs phases 1 and 2 only. Phases 3 and 4 are follow-on work tracked separately.

## Consequences if accepted

- New module `fix-gateway/` in the monorepo (per ADR-0001).
- New gRPC contract in `proto/` for `FixGateway` service.
- New Kafka topic `execution.reports` (per ADR-0004).
- New Helm chart in `deploy/`.
- New Postgres schema for `fix-gateway`'s session log + sequence-number persistence (per ADR-0011 database-per-service).
- ADR-0017 (audit chain) extends — every cancel emit and venue ack lands in the chain.
- Closes audit item A-13 once phase 2 is implemented.

## Open questions

1. **Backfill TIF for existing orders.** Confirmed `GTC` per the A-13 decision (2026-05-01). Existing orders never auto-expire; only new orders that explicitly set `TIF=DAY` are subject to the scheduler. New orders default to `DAY` at the API.
2. **Venue holiday calendars.** Source of truth — vendor data feed (Bloomberg ECAL?), static config, or hand-maintained YAML? Out of scope for this ADR; deferred to phase 2 implementation.
3. **Failover.** A single `fix-gateway` instance is a single point of failure for all venue connectivity. Active-active is hard with FIX sequence numbers; active-passive with a warm standby is the standard. Phase 1 ships with single-instance + HPA-pinned-to-1; HA is a follow-on.

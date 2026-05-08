# fix-gateway Service — Implementation Plan

**Created:** 2026-05-07
**Revised:** 2026-05-07 (post-review-team — incorporates trader, architect, qa, ux, data-analyst feedback)
**Authoritative ADR:** [`docs/adr/0035-fix-gateway-service-extraction.md`](../adr/0035-fix-gateway-service-extraction.md) (Accepted 2026-05-07)
**Closes:** A-13 cancel-emission residual (audit `docs/spec-drift-audit.md`)

This plan extracts FIX/venue-protocol concerns out of `position-service` into a new `fix-gateway` microservice over four phases. Phases follow the ADR. Each phase is independently shippable, has its own tests, and ends with a single squash-style commit theme.

## Goal

Replace the in-process `LoggingOrderCancelEmitter` stub and the in-process `FIXExecutionReportProcessor` in `position-service` with a dedicated `fix-gateway` service that owns:

- FIX session establishment and sequence-number management (per venue).
- Outbound `OrderCancelRequest` (35=F), `NewOrderSingle` (35=D), `OrderCancelReplaceRequest` (35=G).
- Inbound `ExecutionReport` (35=8), `OrderCancelReject` (35=9), `BusinessMessageReject` (35=j).
- Venue cutoff registry (currently in `position-service`, must move).
- Drop-copy ingestion (post-launch).

`position-service` retains all order/position state — it talks to `fix-gateway` via gRPC (sync) and Kafka (async).

## Guiding principles

- **TDD throughout** — every new class has a failing test first per `CLAUDE.md`.
- **Acceptance tests use real infrastructure** — Postgres + Kafka + an in-JVM gRPC stub server (per `CLAUDE.md` testing conventions). FIX session under test uses an in-memory QuickFIX/J acceptor.
- **One concern per commit.** Each phase produces multiple commits.
- **No phase starts until the previous one is committed and CI is green.**
- **Wire-format compatibility before swap.** When migrating an existing flow, the new path runs in parallel behind a feature flag until tests prove parity, then the old path is removed in a follow-up commit.
- **Position-service depends on interfaces, not gRPC types.** `OrderCancelEmitter` (interface, currently in `position-service`) moves to `common/` so the gRPC-backed adapter lives in `position-service` (it owns the client) and tests can swap in a fake.
- **Latency budget:** PlaceOrder gRPC RPC overhead ≤ 2ms p95 same-cluster (per ADR), measured separately from venue-ack wait. Cancel RPC submit has no budget (best-effort fire-and-forget); cancel ack latency is observed but not gated.
- **Idempotency at every retry boundary.** Caller retries on PENDING_FAILED must not produce duplicate venue orders — fix-gateway tracks in-flight `clOrdID`s and reconciles via OrderStatusRequest (35=H) before re-submitting.
- **Phases 2 and 3 ARE user-visible.** Phase 2 introduces best-effort cancel (trader needs degraded-routing indicator); phase 3 changes the inbound fill source (no behavioural change, but Playwright parity tests must pass). Phase 4 introduces `PENDING_FAILED` and the 2s submit window. Each phase carries UI work — see "User-facing UX contract" section.

## Architectural commitments approved 2026-05-07

Per ADR-0035 (Accepted), this plan introduces:

1. **New microservice** `fix-gateway/` (Gradle module, Helm chart, Postgres schema, Kafka topics).
2. **New library dependency** QuickFIX/J (`org.quickfixj:quickfixj-core`) — added to `gradle/libs.versions.toml`.
3. **New Kafka topics**:
   - `execution.reports` — inbound FIX 35=8 / 35=9 / 35=j events from venue → consumers.
   - `fix.session.events` already exists (`KafkaFIXSessionEventPublisher`); fix-gateway becomes the producer instead of position-service.
4. **New Postgres schema** `fix_gateway` (database-per-service per ADR-0011) holding FIX session log + sequence-number state.
5. **New gRPC contract** `proto/src/main/proto/kinetix/execution/fix_gateway.proto`.
6. **New Helm chart additions** under `deploy/` (or extension to `deploy/helm/kinetix/`).

## Phase structure

| Phase | Closes | Risk | Visible to users |
|---|---|---|---|
| 1 — Skeleton | nothing yet | low | no |
| 2 — Outbound cancel | A-13 cancel residual | medium | yes (degraded-routing indicator + ghost-fill alerting) |
| 3 — Inbound migration | wire-format consolidation | high (correctness) | yes (no behavioural change but blotter parity must hold) |
| 4 — Outbound placement | replaces in-process submit path | high (latency + UX) | yes (PENDING_FAILED state, submit-wait UX, Venue Order ID, staged canary) |

---

## Phase 1 — Skeleton service

**Goal:** an empty but deployable service with health checks, observability wiring, an empty gRPC server, and a Postgres schema. No FIX, no business logic.

### Module setup

- [ ] **1.1** New Gradle module `fix-gateway/`.
  - `build.gradle.kts` mirrors `audit-service/build.gradle.kts` (uses `kinetix.kotlin-service` + `kinetix.kotlin-testing` convention plugins).
  - Add `"fix-gateway"` to `settings.gradle.kts`.
  - Dependencies: `:common`, `:proto`, `libs.bundles.exposed`, `libs.bundles.database`, `libs.kafka.clients`, `libs.kotlinx.serialization.json`. **Do not** add QuickFIX/J yet — that lands in phase 2.
- [ ] **1.2** Package skeleton:
  - `fix-gateway/src/main/kotlin/com/kinetix/fix/Application.kt` — Ktor server boot + Prometheus + Loki + Tempo wiring (copy patterns from `audit-service/.../Application.kt`).
  - `fix-gateway/src/main/kotlin/com/kinetix/fix/health/ReadinessChecker.kt` — DB + Kafka readiness checks.
- [ ] **1.3** New Postgres schema `fix_gateway` per ADR-0011:
  - `fix-gateway/src/main/resources/db/migration/V1__create_fix_gateway_schema.sql` — empty schema + `flyway_schema_history` boot row. Real tables added in phase 2.
- [ ] **1.4** Empty gRPC server:
  - `proto/src/main/proto/kinetix/execution/fix_gateway.proto` — declares `FixGateway` service stub with no RPCs (so build wires the proto).
  - `fix-gateway/src/main/kotlin/com/kinetix/fix/grpc/FixGatewayServer.kt` — Netty `ServerBuilder` boot at fixed port (default `9105`); no service implementations bound yet.
- [ ] **1.5** Observability:
  - Prometheus metrics endpoint on `/metrics`.
  - Loki structured logging (JSON formatter — copy `common`'s pattern).
  - Tempo OpenTelemetry tracer (mirror existing services).

### Deploy & infra

- [ ] **1.6** Helm chart additions in `deploy/helm/kinetix/templates/`:
  - `fix-gateway-deployment.yaml`, `fix-gateway-service.yaml`, `fix-gateway-configmap.yaml` (copy `audit-service-*.yaml` shape).
  - **HPA pinned to 1 replica** per ADR open-question #3 (active-passive HA is post-launch).
- [ ] **1.7** `deploy/redeploy.sh` and any docker-compose under `deploy/` learn about `fix-gateway`.
- [ ] **1.8** New Postgres database `fix_gateway` provisioned in `deploy/db-init-configmap.yaml` (or equivalent init script).
- [ ] **1.9** Kafka topic created up front (empty in phase 1):
  - `execution.reports` — partitions `12`, replication `3`, retention `30 days` (extended from `trade.events`'s 7-day default to support MiFID II RTS 28 / MAR audit-replay windows). Partition by `clOrdID` for ordering. `clOrdID` is UUID v4 minted by position-service (see "clOrdID minting") — uniform distribution over 12 partitions; child-order schemes that share a parent `clOrdID` are explicitly out-of-scope and would break ordering.
  - **Authoritative replay source for events older than 30 days:** `fix_gateway.fix_message_log` (table introduced at 2.6 with declarative monthly partitioning).

### Tests

- [ ] **1.10** `FixGatewayApplicationAcceptanceTest` — boots the service against Testcontainers Postgres + Kafka; asserts `/health/ready` returns 200 and `/metrics` exposes `up` gauge.
- [ ] **1.11** `FixGatewayServerAcceptanceTest` — boots the gRPC server, opens a channel, asserts an empty `ListServices` reflection call succeeds.

### Acceptance criteria

- `./gradlew :fix-gateway:build :fix-gateway:test :fix-gateway:acceptanceTest` is clean.
- `./deploy/redeploy.sh` brings the service up alongside the rest of the stack.
- `https://api.kinetixrisk.ai/health` (gateway aggregator) lists `fix-gateway` with status READY.
- `/health` slash command (`.claude/commands/health.md`) reports `fix-gateway: READY`.

### Commit shape

`feat(fix-gateway): scaffold service module [ADR-0035 phase 1]` — single commit with all of the above.

---

## Phase 2 — Outbound cancel emitter (closes A-13 residual)

**Goal:** replace `LoggingOrderCancelEmitter` with a real gRPC-backed emitter that issues FIX `OrderCancelRequest` (35=F) on the appropriate venue session.

### gRPC contract

- [ ] **2.1** Extend `proto/src/main/proto/kinetix/execution/fix_gateway.proto`:
  ```proto
  service FixGateway {
    rpc CancelOrder(CancelOrderRequest) returns (CancelOrderResponse);
    rpc IsVenueOpen(IsVenueOpenRequest) returns (IsVenueOpenResponse);
  }

  message CancelOrderRequest {
    string cl_ord_id = 1;            // FIX tag 41 (OrigClOrdID) — original clOrdID being cancelled
    string venue = 2;                // VenueSessionRegistry normalises to upper-case; typos → UNKNOWN_VENUE
    CancelReason reason = 3;
    string correlation_id = 4;
    string venue_order_id = 5;       // FIX tag 37 (OrderID assigned by venue) — REQUIRED by most venues for 35=F; empty only if order never reached PENDING_NEW
  }

  enum CancelReason {
    CANCEL_REASON_UNSPECIFIED = 0;
    DAY_ORDER_EXPIRY = 1;
    GTD_EXPIRY = 2;
    USER_INITIATED = 3;
    RISK_LIMIT_BREACH = 4;
  }

  message CancelOrderResponse {
    string cl_ord_id = 1;
    Status status = 2;
    enum Status {
      STATUS_UNSPECIFIED = 0;
      ACCEPTED = 1;
      SESSION_DOWN = 2;
      UNKNOWN_VENUE = 3;
      INVALID_REQUEST = 4;
    }
    string detail = 3;
  }

  message IsVenueOpenRequest {
    string venue = 1;
    google.protobuf.Timestamp at = 2;
  }

  message IsVenueOpenResponse {
    bool open = 1;
    google.protobuf.Timestamp next_close = 2;
  }
  ```

### QuickFIX/J integration

- [ ] **2.2** Add QuickFIX/J to `gradle/libs.versions.toml`: `quickfixj-core` 2.3+. Approved by ADR-0035; this is the only new external dependency.
- [ ] **2.3** Move `position-service/.../fix/VenueCutoffRegistry.kt` → `fix-gateway/.../venue/VenueCutoffRegistry.kt`. **Sole owner: fix-gateway.** Position-service consumes cutoff data via the new `IsVenueOpen` RPC (see 2.10) — no copy in `common/`. Rationale: the registry will become runtime-stateful when the holiday calendar (phase 2.5) layers in YAML loaders + future vendor feeds; placing it in `common/` would force every downstream consumer to drag in venue-policy logic. The sweeper invokes `IsVenueOpen` once per sweep pass (not per order) so the RPC cost is negligible.
- [ ] **2.4** New `fix-gateway/.../venue/VenueSessionRegistry.kt` — maps venue → QuickFIX/J `SessionID` + connection config.
- [ ] **2.5** New `fix-gateway/.../session/FixSessionManager.kt` — wraps QuickFIX/J `Initiator`, exposes per-venue session lifecycle. Recovers sequence numbers from Postgres on boot.
- [ ] **2.6** Postgres tables (new migration `V2__fix_session_state.sql`):
  - `fix_session_state(venue PRIMARY KEY, sender_seq_num, target_seq_num, last_logon_at, last_logout_at)` — single row per venue (CHECK constraint enforces uniqueness so reconciliation logic can't see two seq states for one venue).
  - `fix_message_log(id, venue, direction, msg_type, raw_message, clord_id, sent_at)` — append-only, **declaratively partitioned by `sent_at` month** (Postgres native range partitioning). Indexes: `(venue, clord_id)` for outbound-vs-inbound reconciliation; `(venue, msg_type, sent_at)` for ad-hoc queries.
  - **Retention policy:** keep 90 days of partitions hot in Postgres; older partitions detached and archived to S3 in Parquet (cold-archive pipeline lands in phase 2 commit 2). At sustained 50 fills/sec this caps the hot table at ~1.3B rows; partition pruning keeps queries fast.
  - **Pruning job:** scheduled nightly cron in `fix-gateway` that detaches partitions older than 90 days and uploads them via the existing object-store client. Job is observed by Prometheus counter `fix_message_log_partitions_archived_total`.

### Server-side RPC

- [ ] **2.7** `fix-gateway/.../grpc/FixGatewayServiceImpl.kt` — implements `CancelOrder` and `IsVenueOpen`:
  - **CancelOrder:**
    - Validate venue against `VenueSessionRegistry`. Unknown venue → `UNKNOWN_VENUE`.
    - Validate `venue_order_id` is non-empty for venues that require FIX tag 37 (most do). Empty `venue_order_id` for an `OrigClOrdID` that fix-gateway has previously seen confirmed (PENDING_NEW or beyond) → `INVALID_REQUEST`.
    - Build a 35=F `OrderCancelRequest` via QuickFIX/J `Message`. Populate tag 41 (OrigClOrdID), tag 37 (OrderID), tag 11 (new ClOrdID for the cancel itself — minted as `${origClOrdID}-cxl-${seq}`), tag 54 (Side, recovered from `fix_message_log` lookup of the original 35=D), tag 38 (OrderQty), and tag 60 (TransactTime).
    - Submit on session; if session is down, return `SESSION_DOWN` (caller decides whether to retry).
    - On submit success, return `ACCEPTED`. The actual venue ack arrives asynchronously and gets published to `execution.reports` (phase 3); phase 2 only proves the outbound side.
  - **IsVenueOpen:** delegates to `VenueCutoffRegistry.isOpen(venue, at)`. Pure function over registry state; no FIX side-effects.

### Client-side adapter (in `position-service`)

- [ ] **2.8** Promote `OrderCancelEmitter` (interface), `CancelReason` (enum), and a new `VenueOpenChecker` (interface) to `common/`:
  - `common/src/main/kotlin/com/kinetix/common/execution/OrderCancelEmitter.kt`.
  - `common/src/main/kotlin/com/kinetix/common/execution/CancelReason.kt`.
  - `common/src/main/kotlin/com/kinetix/common/execution/VenueOpenChecker.kt` — interface with `fun isOpen(venue: String, at: Instant): Boolean`. Sweeper depends on this; `GrpcVenueOpenChecker` (in position-service) implements it via `IsVenueOpen` RPC.
  - Position-service keeps `LoggingOrderCancelEmitter` for local-dev fallback.
- [ ] **2.9** New `position-service/.../fix/GrpcOrderCancelEmitter.kt` and `position-service/.../fix/GrpcVenueOpenChecker.kt`:
  - `GrpcOrderCancelEmitter` adapter implements `OrderCancelEmitter`, calls `fix-gateway` via the generated gRPC stub.
  - Maps `Order` → `CancelOrderRequest` (uses `order.orderId` as `clOrdID`, `order.venueOrderId` as `venue_order_id` (FIX tag 37) — non-null because cancel only fires on orders that reached PENDING_NEW; if null, throw `IllegalStateException` so the caller surfaces a defect rather than emitting a malformed 35=F).
  - Translates response: `ACCEPTED` → no-op (success); `SESSION_DOWN` / `INVALID_REQUEST` → emit `cancel_failed_total{venue, reason}` Prometheus counter, log warn, **and write a `cancel_attempt(order_id, status, attempted_at)` row to a new `position.cancel_attempts` table** so the phase 2 ghost-fill alerter (2.7b) can detect EXPIRED orders that received fills despite a failed cancel.
  - `GrpcVenueOpenChecker` adapter implements `VenueOpenChecker` via `IsVenueOpen` RPC; sweeper consumes it instead of importing the registry.
- [ ] **2.10** **`ScheduledOrderExpirySweeper` consumes cutoff data via `IsVenueOpen` gRPC**, not via a duplicate registry:
  - Decision: **Option B (revised)**. Sweeper invokes `IsVenueOpen(venue, now)` once per sweep pass — single RPC per sweep regardless of order count, so RPC cost is negligible.
  - Rationale: registry will become runtime-stateful when phase 2.5 holiday calendar layers in YAML loaders; placing it in `common/` would force every consumer to drag in venue-policy logic and make HA migration harder.
  - Fallback: if the RPC fails or fix-gateway is down, sweeper falls back to a hardcoded "always open" assumption (sweeper still performs state transitions, but emits `venue_cutoff_check_failed_total{venue}` counter for ops alerting). This matches today's "best-effort" failure mode.
- [ ] **2.11** Wire selection in `position-service/.../Application.kt:212`:
  - `LoggingOrderCancelEmitter` becomes the dev-mode fallback (env-var gated: `FIX_GATEWAY_ENABLED=false`).
  - Default: `GrpcOrderCancelEmitter` pointing at `fix-gateway:9105`.

### Ghost-fill detection (closes the cancel-race / EXPIRED-with-fill hole)

- [ ] **2.11b** Update `FIXExecutionReportProcessor` to detect ghost fills:
  - When an inbound 35=8 references an order whose status is already `EXPIRED`, `CANCELLED`, or `REJECTED`, do not silently no-op. Instead:
    - Persist the fill against a new `orders.ghost_fills` table (preserves audit trail).
    - Emit `ghost_fill_detected_total{venue, prior_status}` Prometheus counter.
    - Publish a `RiskBreak` event on the `risk.breaks` Kafka topic with severity `CRITICAL` so it surfaces in the ops alerting pipeline AND the trader-facing `RiskAlertBanner`.
    - Do NOT update `Position` automatically — manual resolution required (the operator decides whether the position is real or whether the venue made a mistake).
- [ ] **2.11c** Update `position-service/.../routes/OrdersRoute.kt` to expose `GET /orders/{id}/ghost-fills` so the UI can render attached ghost fills on the order detail panel.

### Tests

- [ ] **2.12** `fix-gateway` unit + integration:
  - `VenueCutoffRegistryTest` (move from position-service) — covers regular sessions, cutoff-boundary minutes, and (post-2.5) holiday entries.
  - `VenueSessionRegistryTest` — covers venue normalisation (lowercase → upper, whitespace stripped).
  - `FixSessionManagerTest` (unit + Testcontainers Postgres — see CLAUDE.md: this lives in fix-gateway as integration-level, not in `common`):
    - Happy-path seq-num recovery from a fixture row.
    - **Cold start (no row)** — must default to seq 1 and write the row, not crash.
    - **Corrupt row** (`sender_seq_num = -1` / NULL) — must fail readiness probe with structured log; service must not silently use 0.
    - **Seq behind venue** — venue's expected is 50, Postgres says 30; must trigger `ResendRequest` (35=2) correctly.
    - **Seq ahead of venue** — Postgres says 50, venue says 30; must `Logout` + `SeqReset` reconcile.
    - **`SequenceReset-GapFill` (35=4)** — handler must advance stored seq without requesting retransmission of the gap.
  - `FixGatewayServiceImplIntegrationTest` (renamed from `…Test`) — uses QuickFIX/J in-memory store; asserts protobuf request → FIX 35=F translation byte-for-byte including tags 41 (OrigClOrdID), 37 (OrderID), 11 (new ClOrdID), 54 (Side), 38 (OrderQty), 60 (TransactTime). **Decimal boundary cases:** quantity = `"0"` → `INVALID_REQUEST`; quantity = `"0.001"` for JPY (fractional shares disallowed) → `INVALID_REQUEST`; `limit_price = "-1.00"` for synthetic instruments → preserved literally without negation; `limit_price = "99999999999999.99"` → asserted FIX byte representation does not overflow.
  - `PendingNewCorrelatorTest` (unit, fix-gateway) — covers (a) inbound 35=8 arriving before deferred is registered, (b) TTL eviction racing late venue reply, (c) duplicate `clOrdID` registration → second call returns `INVALID_REQUEST`. Uses `TestCoroutineDispatcher` for deterministic ordering.
- [ ] **2.13** `fix-gateway` acceptance:
  - `CancelOrderRpcAcceptanceTest` — boots the gRPC server, an in-memory QuickFIX/J acceptor (counterparty role), and Testcontainers Postgres. Sends a `CancelOrderRequest` over gRPC; asserts a 35=F lands at the acceptor with the right `OrigClOrdID` (tag 41), `OrderID` (tag 37), new `ClOrdID` (tag 11), `Side` (tag 54), `OrderQty` (tag 38), and `TransactTime` (tag 60).
  - Coverage: ACCEPTED, SESSION_DOWN (acceptor refuses logon), UNKNOWN_VENUE, INVALID_REQUEST (missing `venue_order_id` for an order beyond PENDING_NEW).
  - **FIX session protocol edges:** `ResendRequest` (35=2) — acceptor requests retransmission of messages 40–45; assert fix-gateway replays from `fix_message_log`. `SequenceReset-Reset` (35=4 with `GapFillFlag=N`); assert Postgres seq state advances. `Logout` (35=5) mid-flight — acceptor sends `Logout` after fix-gateway sends 35=F but before responding; assert RPC returns `SESSION_DOWN` within deadline rather than hanging. `PossDupFlag=Y` on inbound 35=8 → no duplicate event published to `execution.reports`. Malformed `BodyLength` / `CheckSum` → handled at session layer, `fix_messages_in_total{venue,msg_type="MALFORMED"}` increments, no propagation.
  - `IsVenueOpenRpcAcceptanceTest` — covers regular session (open), pre-open (closed), post-cutoff (closed), unknown venue (NOT_FOUND gRPC status).
  - `FixGatewayDurabilityAcceptanceTest` — fix-gateway receives one 35=8, test injects a fault between QuickFIX/J callback and Kafka `send()` (via spy on `KafkaProducer`), kills the process, restarts, asserts venue replays the message on logon AND exactly one `ExecutionReportEvent` lands on `execution.reports` (no duplicate from replay-plus-pre-crash-publish).
  - `MassCancelOnDisconnectAcceptanceTest` — acceptor drops session mid-flow with 3 open orders; assert fix-gateway on reconnect (a) does NOT accept new outbound order requests until reconciliation completes, (b) sends `OrderStatusRequest` (35=H) for each open `clOrdID` recorded in `fix_message_log`, (c) emits `fix_session_reconciliation_total{venue, outcome}` counter.
- [ ] **2.14** `position-service` acceptance:
  - `GrpcOrderCancelEmitterAcceptanceTest` — uses the in-JVM gRPC stub-server pattern from `CLAUDE.md` (bind a fake `FixGatewayImplBase` to `NettyServerBuilder.forPort(0)`); asserts `Order` → `CancelOrderRequest` mapping for each `CancelReason`.
  - Coverage adds: **gRPC `DEADLINE_EXCEEDED`** — stub returns `Status.DEADLINE_EXCEEDED.asException()`; assert sweeper logs structured error and writes a `cancel_attempt(status=TIMEOUT)` row, does not hang. **mTLS handshake failure** — stub server presents an untrusted certificate; assert structured error logged once (not stacktrace flood), circuit breaker opens after 5 consecutive failures, `mtls_handshake_failed_total{peer}` increments. **Channel close mid-call** — server closes the channel after RPC start; assert clean error propagation.
  - `GrpcVenueOpenCheckerAcceptanceTest` — asserts sweeper reads cutoff via RPC; on RPC failure falls back to "always open" + emits `venue_cutoff_check_failed_total`.
  - `GhostFillDetectionAcceptanceTest` — fixture: one EXPIRED order; publish a 35=8 Fill referencing it; assert (a) fill persisted in `orders.ghost_fills`, (b) `Position` not updated, (c) `RiskBreak` event published to `risk.breaks` Kafka topic with severity CRITICAL, (d) `ghost_fill_detected_total` counter increments.
  - Update existing `ScheduledOrderExpirySweeperTest` minimally — emitter is still mocked; the new adapter has its own tests.
- [ ] **2.15** End-to-end:
  - `FixGatewayCancelEnd2EndTest` in `end2end-tests/` — boots position-service + fix-gateway + Postgres + Kafka + an in-memory acceptor; submits an order, advances clock past venue cutoff, runs the sweeper, asserts the acceptor receives a 35=F with all required tags.
  - **Multi-venue concurrent fixture:** the test exercises two venues (NYSE + LSE) simultaneously to expose any venue-keyed session-state collision.

- [ ] **2.16** UI Playwright (phase 2 IS user-visible — see UX contract):
  - `ui/e2e/order-blotter-degraded-routing.spec.ts` — mocks the gateway-aggregator health endpoint to report `fix-gateway: DOWN`; asserts the venue-routing-degraded `StatusDot` indicator becomes amber + tooltip reads "Cancel confirmation unavailable — call venue directly to confirm cancel".
  - `ui/e2e/order-detail-ghost-fills.spec.ts` — mocks an EXPIRED order with attached ghost fills; asserts the order detail panel shows a `RiskAlertBanner` (CRITICAL severity) with "Fill received after cancel — contact ops" copy and the ghost-fill list renders below the order row.
  - `cd ui && npm run lint` before committing.

### Acceptance criteria

- `./gradlew :fix-gateway:build :fix-gateway:test :fix-gateway:acceptanceTest :fix-gateway:integrationTest :position-service:test :position-service:acceptanceTest :end2end-tests:end2EndTest` is green.
- `cd ui && npx playwright test order-blotter-degraded-routing order-detail-ghost-fills` is green.
- A-13 audit entry updated to drop the "Cancel emission to the venue side is currently a logging stub" caveat.
- Grafana dashboard `fix-gateway-overview` (committed under `deploy/observability/grafana/`) shows session state, message rates, cancel ack latency, and the new `ghost_fill_detected_total` counter; alert rule fires if any venue session is down >60s during trading hours.

### Commit shape

Four commits:
1. `feat(common): OrderCancelEmitter + CancelReason + VenueOpenChecker interfaces [ADR-0035 phase 2]` — interface migration only, position-service still uses the logging emitter.
2. `feat(fix-gateway): outbound cancel + IsVenueOpen via QuickFIX/J [ADR-0035 phase 2]` — server-side, gRPC contract, session manager, partitioned `fix_message_log`, tests.
3. `feat(position-service): swap LoggingOrderCancelEmitter for gRPC client + ghost-fill detection [ADR-0035 phase 2]` — wire the adapter, env-flag fallback, ghost-fill detection in `FIXExecutionReportProcessor`, UI degraded-routing indicator, e2e test.
4. `chore(observability): fix-gateway Grafana dashboards + alert rules [ADR-0035 phase 2]` — dashboards under `deploy/observability/grafana/`, alert rules under `deploy/observability/prometheus/`.

---

## Phase 3 — Migrate inbound `ExecutionReport` (35=8) processing

**Goal:** `fix-gateway` becomes the FIX-side inbound consumer. Parsed events flow on Kafka `execution.reports`; `position-service` becomes a Kafka consumer of that topic, dropping its in-process `FIXExecutionReportProcessor`.

### Wire format

- [x] **3.1** Define `ExecutionReportEvent` schema in `common/.../execution/ExecutionReportEvent.kt` (Kotlinx-serialized JSON):
  - Carries BOTH a typed `eventType` discriminator (`FILL`, `PARTIAL_FILL`, `CANCELLED`, `REPLACED`, `REJECTED`, `BUSINESS_REJECT`) AND the raw `execType: String` from FIX tag 150 (preserves `FIXInboundFillEvent` field-for-field compatibility so `FIXExecutionReportProcessor` consumes the new event without signature change).
  - Includes `fixVersion: String` (e.g. `"FIX.4.2"`, `"FIX.4.4"`) so consumers can disambiguate ExecType `1` (Partial Fill in 4.2) from `F` (Fill in 4.4) at the source rather than guessing.
  - Mapping table (`execType` → `eventType`) is owned by `FIXMessageConverter` (moves to fix-gateway at 3.3) and version-keyed; tests in 3.9 pin every combination.
- [x] **3.2** Add a JSON-schema test in `schema-tests/` so future changes are explicit.

### fix-gateway side

- [x] **3.3** New `fix-gateway/.../session/InboundFixHandler.kt` — QuickFIX/J `Application` callback:
  - On 35=8: convert via `FIXMessageConverter` (move from `position-service/.../fix/FIXMessageConverter.kt`), publish to `execution.reports` keyed by `clOrdID`.
  - On 35=9 (`OrderCancelReject`): publish as `eventType = REJECTED` with reject reason.
  - On 35=j (`BusinessMessageReject`): publish as `eventType = BUSINESS_REJECT`.
- [x] **3.4** `KafkaFIXSessionEventPublisher` moves from `position-service/.../fix/` → `fix-gateway/.../kafka/`. Topic name unchanged. Position-service retains its copy (referenced by the legacy `FIXSessionRoutes` admin endpoint) until the in-process FIX wiring is removed in commit 4.
- [x] **3.5** Producer idempotence: `enable.idempotence=true`, `acks=all`, `max.in.flight.requests.per.connection=5`, `delivery.timeout.ms=120000`. Crashes between FIX-receive and Kafka-publish are handled by FIX session resync (the venue resends unacknowledged 35=8s on logon). **Verified by `FixGatewayDurabilityAcceptanceTest` (2.13)** — kill-restart-replay produces exactly one downstream event. The QuickFIX/J `MessageStore` MUST be the Postgres-backed store (not in-memory) so unacked inbound messages survive restart; configured in `FixSessionManager` (2.5).

### position-service side

- [x] **3.6** New `position-service/.../kafka/ExecutionReportConsumer.kt`:
  - Subscribes to `execution.reports` with `partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor` (matches existing pattern at `Application.kt:263`; avoids stop-the-world rebalances during rolling deploys).
  - Dispatches to existing `FIXExecutionReportProcessor.process(...)` — processor stays unchanged because the new event carries the raw `execType` field (3.1).
  - **Idempotency:** consumer dedups on `(venue, execID)` — `execID` (FIX tag 17) is the venue's unique exec-report identifier and is already persisted in `Fill`. A `seen_exec_ids` LRU cache (size 100k) plus a unique constraint on `fills(venue, exec_id)` provides defence-in-depth.
  - Manual offset commits *after* successful DB write to avoid lost events.
- [x] **3.7** Remove direct invocation of `FIXExecutionReportProcessor` from `position-service/.../Application.kt:248` (the in-process boot path).
- [x] **3.8** **Migration safety net + parity monitoring:**
  - Feature flag `EXECUTION_REPORTS_VIA_KAFKA=true` (default true after this phase). When false, falls back to a direct in-process pipeline using a local QuickFIX/J acceptor (**dev-only** — prod has no rollback once commit 4 lands; the real prod safety net is consumer-side replay from Kafka).
  - **Parity-monitoring metric** `execution_report_path_divergence_total{venue, event_type, divergence_kind}` Prometheus counter — increments when an event appears on one path but not the other within 5s. `divergence_kind` ∈ `{kafka_only, in_process_only, content_mismatch}`. Required to be ZERO for the full one-week soak before commit 4 lands. Alert rule fires on any non-zero value.
  - Removed in a follow-up commit once production has run on the new path for one week with `execution_report_path_divergence_total = 0`.

### Tests

- [x] **3.9** Schema test:
  - `ExecutionReportEventSchemaTest` in `schema-tests/` — round-trips every `eventType` and pins JSON shape.
- [x] **3.10** `fix-gateway` acceptance:
  - `InboundExecutionReportAcceptanceTest` — in-memory acceptor sends a 35=8 (Fill, Partial, Cancelled, Replace each); asserts a corresponding `ExecutionReportEvent` lands on `execution.reports` with the correct partition key (`clOrdID`), `eventType` discriminator, AND raw `execType` for downstream parity.
  - Coverage: 35=9 reject path; 35=j business-reject path; FIX 4.2 (ExecType `1` = Partial) AND 4.4 (ExecType `F` = Fill) variants of every above case.
  - **PossDupFlag=Y** on inbound 35=8 → no duplicate event published (asserts the dedup cache + `(venue, exec_id)` constraint).
  - **Unknown ClOrdID** — venue sends 35=8 referencing a `clOrdID` not in `fix_message_log`; assert event still publishes (we are not the order-state authority) but `unknown_clord_id_total{venue}` counter increments.
  - **Missing ClOrdID** (tag 11 absent) → event publishes with empty `clOrdID`, partition key falls back to `venue`, `malformed_inbound_total{venue, defect}` counter increments.
- [x] **3.11** `position-service` acceptance:
  - `ExecutionReportConsumerAcceptanceTest` — Testcontainers Kafka, publishes each event type, asserts the existing DB invariants hold (fill persisted, position updated, dedup works). Reuses every existing assertion from `FIXExecutionReportProcessorTest`.
  - **Negative cases:** (a) `CumQty` exceeds `OrderQty` (overfill) — assert excess fill rejected and `overfill_rejected_total{venue}` counter increments, plus a `RiskBreak` published. (b) Fill referencing a `clOrdID` that does not exist in `orders` table — log warn + `orphan_fill_total` counter, do NOT crash the consumer. (c) 35=8 `Rejected` with no `ClOrdID` — must not NPE. (d) Duplicate event (same `venue` + `execID`) — second insert hits `(venue, exec_id)` unique constraint, consumer commits offset without error.
  - `DualPathParityAcceptanceTest` — with both paths active under `EXECUTION_REPORTS_VIA_KAFKA=true|false` toggled per test scenario, route an identical 35=8 fixture through both paths (different `clOrdID`s to avoid dedup) and assert the resulting `Position`, `Fill`, and `trade.events` records are structurally identical (deep-equal modulo timestamp + ID fields). Required to pass before phase 3 commit 4 ships.
- [x] **3.12** End-to-end:
  - `FixGatewayInboundEnd2EndTest` — order submitted via REST, in-memory acceptor sends 35=8 Fill, asserts position-service has the fill in DB *and* `trade.events` Kafka topic has the booked trade. Reuses existing position-update fixtures.
- [x] **3.13** Soak test — gated under JUnit `@Tag("load")` and the `:end2end-tests:loadTest` Gradle task:
  - `FixGatewayThroughputLoadTest` (`end2end-tests/src/test/kotlin/com/kinetix/loadtest/`) — drives `KafkaExecutionReportPublisher → execution.reports → ExecutionReportDispatcher` against Testcontainers Postgres + Kafka.
  - Hard assertions: `kafka.consumer.lag < 500 messages` at end of run; `p99 dispatch latency < 50ms`; `zero missing fills` (count published vs `SELECT COUNT(*) FROM execution_fills`); `zero ghost fills`.
  - Runs on **every commit** via the new `load-tests` job in `.github/workflows/ci.yml` (per-commit profile: 100 fills/s × 30s = 3000 messages). Nightly soak overrides via `LOAD_TEST_RATE_PER_SEC=1000` and `LOAD_TEST_DURATION_SECONDS=300` to exercise the plan's 1000/s × 5min scenario.

- [x] **3.14** Phase 3 UI regression Playwright (phase 3 IS user-visible in the sense that any divergence from the existing path would surface in the blotter):
  - `ui/e2e/order-blotter-fill-arrival.spec.ts` — fixture: order in `SENT` state; backend pushes a WebSocket event sourced from the new `execution.reports` Kafka path; asserts the blotter row transitions to `FILLED` (or `PARTIALLY_FILLED`) without a page reload, exactly as today.
  - `cd ui && npm run lint` before committing.

### Acceptance criteria

- All existing `FIXExecutionReportProcessor*Test` assertions pass via the new Kafka path.
- One full week of production replay (or in-memory acceptor parity test) before phase 4 starts.
- `position-service`'s `KafkaFIXSessionEventPublisher` is gone (moved to fix-gateway); position-service no longer depends on it.

### Commit shape

Four commits:
1. `feat(common): ExecutionReportEvent wire schema [ADR-0035 phase 3]`.
2. `feat(fix-gateway): inbound 35=8/9/j handler publishes execution.reports [ADR-0035 phase 3]`.
3. `feat(position-service): ExecutionReportConsumer [ADR-0035 phase 3]`.
4. `chore(position-service): drop in-process FIXExecutionReportProcessor wiring [ADR-0035 phase 3]` — only after the consumer has run in production for at least one week (commit gates on the soak window).

---

## Phase 4 — Outbound order placement

**Goal:** replace `position-service`'s in-process order-submit path with a synchronous gRPC `PlaceOrder` RPC to `fix-gateway`. The trader still gets the venue's `Pending New` reply on the same call.

### gRPC contract

- [x] **4.1** Extend `fix_gateway.proto`:
  ```proto
  service FixGateway {
    rpc PlaceOrder(PlaceOrderRequest) returns (PlaceOrderResponse);
  }

  message PlaceOrderRequest {
    string cl_ord_id = 1;
    string venue = 2;
    string instrument_id = 3;
    Side side = 4;
    OrderType order_type = 5;
    string quantity = 6;        // Decimal as string (avoids float drift; matches existing convention)
    string limit_price = 7;     // optional, empty for market orders
    TimeInForce time_in_force = 8;
    string expires_at_iso = 9;  // optional, GTD only
    string correlation_id = 10;
    int32 venue_ack_timeout_ms = 11;  // optional, per-call override; 0 = use venue default from VenueSessionRegistry
  }

  message PlaceOrderResponse {
    string cl_ord_id = 1;
    string venue_order_id = 2;  // assigned by venue (35=37 OrderID)
    Status status = 3;          // PENDING_NEW | REJECTED | SESSION_DOWN | UNKNOWN_VENUE | INVALID_REQUEST | DUPLICATE_IN_FLIGHT
    string reject_reason = 4;
  }
  ```
  - **Per-venue venue-ack timeout (replaces single 2s default):** `VenueSessionRegistry` declares per-venue defaults — NYSE/NASDAQ co-lo `200ms`, LSE `500ms`, TSE/HKEX `1000ms`, EM brokers `5000ms`. The 2s default is rejected as a one-size-fits-all that is too long for co-lo and too short for some EM venues. Per-call override allowed via `venue_ack_timeout_ms`.
  - **`DUPLICATE_IN_FLIGHT` status** — new in this revision; returned when fix-gateway has an in-flight RPC for the same `clOrdID`. Caller must NOT retry with the same `clOrdID`; either wait for the original RPC to settle or mint a fresh `clOrdID` only after reconciliation.
  - Note on nested enum codegen: `PlaceOrderResponse.Status` is a nested enum. Verify the Kotlin gRPC generator produces sane names (`PlaceOrderResponse.Status.PENDING_NEW`) before committing — some generators flatten nested enums in confusing ways. Test in `PlaceOrderProtoTest` (4.8).
- [x] **4.2** Side / OrderType / TimeInForce enums in proto match the Kotlin enums in `common/`.

### fix-gateway side

- [x] **4.3** `FixGatewayServiceImpl.placeOrder`:
  - Validate venue + instrument format.
  - **Idempotency check:** lookup `clOrdID` in the in-flight correlator (4.4) AND in `fix_message_log` for the past 24h. If already in-flight → return `DUPLICATE_IN_FLIGHT`. If already submitted (35=D in log) and outcome unknown → fix-gateway sends `OrderStatusRequest` (35=H) to the venue and waits for the response before deciding the response status; this prevents the double-submit race that follows a caller-side retry on `PENDING_FAILED` (see 4.5).
  - Build 35=D `NewOrderSingle`.
  - Submit on session; await venue's first 35=8 (`OrdStatus = A` Pending New) up to the per-venue timeout from `VenueSessionRegistry` (or per-call override).
  - Return `PENDING_NEW` + venue's assigned `OrderID` on success.
  - The full execution lifecycle (subsequent fills / cancels) continues to flow asynchronously on `execution.reports` (phase 3).
  - **Mass-cancel-on-disconnect / session recovery:** on FIX session reconnect (after disconnect), fix-gateway enters a "reconciliation" state where new outbound RPCs are rejected with `SESSION_DOWN`. It sends `OrderStatusRequest` (35=H) for every open `clOrdID` recorded in `fix_message_log` for that venue, awaits responses, and only then re-opens the session for new outbound traffic. Many venues have "cancel on disconnect" policies — without this reconciliation, fix-gateway's idea of book state can be stale by hours after a session bounce. Captured in `docs/runbooks/fix-gateway-incident.md`.
- [x] **4.4** Pending-new correlator (`PendingNewCorrelator.kt`): maps `clOrdID` → `CompletableDeferred<PlaceOrderResponse>` so the inbound 35=8 handler can wake the synchronous RPC.
  - **Race-safe registration:** the deferred is registered BEFORE the 35=D is sent on the wire; the inbound handler always finds an entry to complete (no inbound-arrives-before-deferred race).
  - **TTL handling:** TTL eviction must use atomic `compareAndSet` on the deferred state; if eviction races with inbound completion, the handler still publishes the inbound 35=8 to `execution.reports` (no event is dropped) and the gRPC caller times out with `SESSION_DOWN`. The eviction emits `pending_new_correlator_ttl_evictions_total{venue}`.
  - **Single-instance constraint:** the in-memory map only works under the phase-1 single-instance HPA pin. Active-active or active-passive HA REQUIRES replacing this with a distributed store (Redis or similar) — captured in the risk register and inherited by the future HA ADR.
  - **Back-pressure cap:** semaphore with `MAX_IN_FLIGHT_PLACE_ORDERS = 500` per venue. RPCs blocked at the semaphore return `SESSION_DOWN` with detail `back_pressure` after a 100ms wait. Prevents heap exhaustion from slow-venue scenarios. Counter `pending_new_correlator_back_pressure_total{venue}` increments on every block.
  - **Concurrent `clOrdID`:** second registration for the same `clOrdID` returns `DUPLICATE_IN_FLIGHT` (see 4.1).

### position-service side

- [x] **4.5** `OrderSubmissionService.submit(...)`:
  - After persistence and risk checks, call `FixGatewayClient.placeOrder(...)` (new collaborator).
  - On `PENDING_NEW`: persist `venueOrderId`, transition to `SENT`, return.
  - On `REJECTED` / `UNKNOWN_VENUE` / `INVALID_REQUEST`: transition to `REJECTED`, return error to caller.
  - On `SESSION_DOWN` or RPC timeout: transition to `PENDING_FAILED` (new status — needs migration), return 503 to caller. **Trader retry path is NOT a fresh `clOrdID`:** the UI's retry CTA reuses the original `clOrdID` so fix-gateway's idempotency check (4.3) reconciles via 35=H rather than producing a duplicate venue order. If the trader wants a genuinely new order they must close the failed ticket and submit fresh.
  - On `DUPLICATE_IN_FLIGHT`: surface the same 503 to the caller with explicit "previous submission still in flight, do not retry yet" copy. The blotter row stays in `PENDING` until the original RPC resolves.
  - **gRPC deadline propagation:** the gRPC client deadline is set to `venue_ack_timeout_ms + 500ms grace` (covers the venue wait + correlator overhead). The HTTP route deadline is set to `gRPC deadline + 500ms`. Without this layering the UI sees ambiguous timeouts where the RPC succeeded but the HTTP client gave up.
- [x] **4.6** New `OrderStatus.PENDING_FAILED` (Flyway migration `V23__order_status_pending_failed.sql` adds the enum value to the existing CHECK constraint). **Migration ordering constraint:** `V23` MUST be deployed before any position-service binary that references `OrderStatus.PENDING_FAILED` enters service. Helm chart `fix-gateway-deployment.yaml` and `position-service-deployment.yaml` MUST coordinate via initContainer or pre-deploy hook to guarantee this ordering; documented in the deploy runbook.
- [ ] **4.7** Staged rollout:
  - `FIX_GATEWAY_PLACE_ORDER=false` initially; flip to `true` per cohort.
  - **Canary plan (replaces 5%/48h):** 1% for 24h → 5% for 48h → **10% for 5 trading days** with statistical equivalence assertion before full rollout. Acceptance test: rejection rate, fill rate, and cancel-confirmation rate are within ±2σ of baseline (not "zero regression" which fails on noise). 5 trading days exercises overnight seq-num resets, GTD expiry, BusinessMessageReject, and weekend session rolls — none of which appear in 48h.
  - Old code path (today's fake-fill simulator, if any) stays for rollback throughout the canary.

### Tests

- [x] **4.8** Proto consistency: `PlaceOrderProtoTest` in `schema-tests/` round-trips every enum.
- [x] **4.9** `fix-gateway` acceptance:
  - `PlaceOrderRpcAcceptanceTest` — sends `PlaceOrderRequest`, in-memory acceptor responds with 35=8 Pending New (delayed 1ms to expose race); asserts response is `PENDING_NEW` with `venue_order_id` populated.
  - Reject path: acceptor responds with 35=8 `OrdStatus = 8` (Rejected) → response is `REJECTED`.
  - Timeout path: acceptor doesn't respond → response is `SESSION_DOWN` after per-venue deadline.
  - **Per-venue timeout matrix:** assert NYSE returns within 200ms, TSE within 1000ms, EM_BROKER within 5000ms; cross-asserts no global 2s default leak.
  - **Concurrent load test:** 50 simultaneous `PlaceOrderRequest`s against the in-memory acceptor with 100ms artificial venue delay; assert (a) p95 end-to-end PENDING_NEW arrival ≤ 250ms (no head-of-line blocking in the correlator), (b) zero deadlock, (c) `pending_new_correlator_back_pressure_total = 0`.
  - **Idempotency test:** same `clOrdID` sent twice in rapid succession; second call returns `DUPLICATE_IN_FLIGHT`. Then: same `clOrdID` retried after the first resolves with `SESSION_DOWN` outcome — fix-gateway sends `OrderStatusRequest` (35=H), acceptor responds with status, response status reflects venue's actual state (e.g. `PENDING_NEW` if venue did receive original).
  - **Mass-cancel-on-disconnect test:** mid-session, drop the FIX connection; submit a `PlaceOrder` RPC during the reconciliation window; assert it returns `SESSION_DOWN` with detail `reconciling`. Verify `OrderStatusRequest` (35=H) is sent for each pre-disconnect open order, and only after responses arrive does the session re-open for new outbound RPCs.
- [x] **4.10** `position-service` acceptance:
  - Update `OrderSubmissionServiceAcceptanceTest`:
    - Stub `FixGatewayClient` returning each Status (PENDING_NEW, REJECTED, UNKNOWN_VENUE, INVALID_REQUEST, SESSION_DOWN, **DUPLICATE_IN_FLIGHT**, **DEADLINE_EXCEEDED** via gRPC `StatusRuntimeException`); assert order's terminal state for each.
    - **Latency split into two distinct measurements:**
      - `fix_grpc_place_order_overhead_seconds` p95 ≤ 2ms — measured against a no-op gRPC stub. Build-gate.
      - `fix_venue_pending_new_latency_seconds{venue}` — measured at runtime against the in-memory acceptor with realistic per-venue delays. Grafana SLO, not build-gate. Plan the SLO with the trading desk before phase 4 canary.
    - mTLS handshake failure case (server presents untrusted cert) — circuit breaker opens after N consecutive failures, no stacktrace flood.
    - Channel close mid-call → clean propagation, order ends in `PENDING_FAILED`.
- [ ] **4.11** End-to-end:
  - `OrderPlacementEnd2EndTest` — full submit-via-REST → fix-gateway → in-memory acceptor → Pending New → REST response. Asserts user-visible response payload now contains `venueOrderId`.
- [ ] **4.12** UI Playwright (full suite — phase 4 introduces a new error state, a new label, and a new latency profile, so one spec is not enough per CLAUDE.md):
  - `ui/e2e/order-placement.spec.ts` — golden path: submit → spinner shown on submit button (`Button.tsx`'s `loading` prop) → `PENDING_NEW` arrives → confirmation modal renders `Venue Order ID` label + value + clipboard-copy button.
  - `ui/e2e/order-placement-timeout.spec.ts` — mock gateway returning 503 after 2s delay; assert (a) submit button disabled during wait, (b) `PENDING_FAILED` amber badge appears in blotter, (c) `RiskAlertBanner` (WARNING severity) renders "Order routing timed out — call venue to confirm before retry", (d) retry CTA is present and reuses original `clOrdID`.
  - `ui/e2e/order-placement-rejected.spec.ts` — mock REJECTED response; assert distinct red badge styling vs PENDING_FAILED amber, no retry CTA, reject reason copy from `reject_reason` field.
  - `ui/e2e/order-placement-duplicate-in-flight.spec.ts` — mock `DUPLICATE_IN_FLIGHT`; assert blotter row stays in `PENDING`, banner shows "Previous submission still in flight, do not retry yet".
  - `ui/e2e/order-venue-id.spec.ts` — assert `Venue Order ID` column in OrderBlotter is sortable, copyable via clipboard button, includes `aria-label="Venue order ID"`, monospaced + right-aligned, present in CSV export.
  - `ui/e2e/venue-routing-degraded.spec.ts` — mock health endpoint returning fix-gateway DOWN; assert `StatusDot` indicator on blotter header turns amber, tooltip shows venue-specific session state.
  - `cd ui && npm run lint` before committing.

- [ ] **4.13** UI implementation tasks (referenced by Playwright specs above):
  - Add `PENDING_FAILED` and `PENDING` cases to `TradeBlotter.tsx` `statusBadgeClass()` — amber for PENDING_FAILED, neutral grey for PENDING.
  - Wire `Button.tsx` `loading` prop to the order-submit flow; disable submit while waiting; show "Sending to venue..." copy.
  - Add `Venue Order ID` column to `TradeBlotter.tsx` — opt-in via column-visibility toggle, clipboard button, sortable, ARIA-labelled.
  - Add `VenueRoutingStatusDot.tsx` component (consumes `/health` aggregator endpoint) and mount in blotter header.
  - Add `OrderPlacementErrorBanner.tsx` reusing `RiskAlertBanner` severity tiers (WARNING for PENDING_FAILED + DUPLICATE_IN_FLIGHT, CRITICAL for REJECTED with venue session error).
  - Add `useOrderPlacement` hook centralising the submit → loading → success/error state machine so behaviour is consistent across order ticket variants.

### Acceptance criteria

- Canary canary: 5% of orders routed via `fix-gateway` for 48 hours with zero rejection-rate regression vs. baseline.
- Full rollout after canary; old in-process submit path deleted in a follow-up commit.
- ADR-0035 marked Complete in the Status section.
- A-13 audit entry can be re-checked: cancel **and** placement now flow through fix-gateway.

### Commit shape

Five commits:
1. `feat(proto): PlaceOrder RPC contract [ADR-0035 phase 4]`.
2. `feat(fix-gateway): PlaceOrder synchronous gRPC + pending-new correlator [ADR-0035 phase 4]`.
3. `feat(common): FixGatewayClient interface [ADR-0035 phase 4]`.
4. `feat(position-service): route order submit through fix-gateway behind flag [ADR-0035 phase 4]`.
5. `chore(position-service): remove in-process simulator [ADR-0035 phase 4]` — after canary completion.

---

## Cross-cutting concerns

### Observability (every phase)

**Prometheus metrics on `fix-gateway`** — split between ops and business analytics:

*Ops:*
- `fix_session_state{venue}` gauge (0=down, 1=up).
- `fix_messages_in_total{venue,msg_type}` counter — `msg_type` is allow-listed in `InboundFixHandler.kt` to known FIX msg types; unknowns map to `msg_type="OTHER"` to prevent label cardinality explosion.
- `fix_messages_out_total{venue,msg_type}` counter — same allow-list contract.
- `fix_message_log_partitions_archived_total` counter (nightly partition-prune job).
- `pending_new_correlator_ttl_evictions_total{venue}` counter.
- `pending_new_correlator_back_pressure_total{venue}` counter.
- `fix_session_reconciliation_total{venue, outcome}` counter (post-reconnect mass-cancel reconcile).
- `mtls_handshake_failed_total{peer}` counter.

*Business analytics (TCA / execution quality):*
- `fix_order_fills_total{venue, fill_type}` counter — `fill_type` ∈ `{FULL, PARTIAL}`.
- `fix_order_reject_total{venue, reject_reason}` counter — `reject_reason` is the venue's tag 58/103 normalised to a bounded set (`UNKNOWN_INSTRUMENT`, `RISK_REJECT`, `VENUE_HALT`, `INVALID_PRICE`, `OTHER`); unbounded raw text is logged but NOT used as a label.
- `fix_grpc_place_order_overhead_seconds` histogram — gRPC overhead alone, no venue wait. **Build-gate at p95 ≤ 2ms.**
- `fix_venue_pending_new_latency_seconds{venue}` histogram — time from 35=D send to 35=8 OrdStatus=A receive. **Grafana SLO, not build-gate.**
- `cancel_ack_latency_seconds{venue, cancel_reason}` histogram — time from 35=F send to 35=8 OrdStatus=4 / 35=9 receive.
- `ghost_fill_detected_total{venue, prior_status}` counter (position-service).
- `overfill_rejected_total{venue}` counter (position-service).
- `orphan_fill_total{venue}` counter (position-service).
- `unknown_clord_id_total{venue}`, `malformed_inbound_total{venue, defect}` counters (fix-gateway).
- `execution_report_path_divergence_total{venue, event_type, divergence_kind}` counter — phase 3 parity gate, must be 0 for the full one-week soak before commit 4.

**Cardinality budget:** `venue` ≤ 5 (launch venues). `msg_type` ≤ 10 (allow-listed). `reject_reason` ≤ 5 (normalised). Total label combinations bounded under 500 — well within Prometheus targets.

**Daily reconciliation jobs (no drop-copy until post-launch):**
- `unacknowledged_outbound_alerter` — runs every 5 minutes during trading hours; queries `fix_message_log` for outbound 35=D / 35=F messages older than 30 minutes with no corresponding inbound 35=8 or 35=9; emits `unacknowledged_outbound_total{venue, msg_type}` and pages on any non-zero value.
- `kafka_log_reconciliation_job` — daily; counts `execution.reports` consumer offsets vs `fix_message_log` inbound count for the same window; emits `kafka_vs_log_divergence_total{venue}`.

**Tempo trace propagation:** `correlation_id` flows from REST → gRPC → FIX (`Tag 11/41 ClOrdID` already serves as the FIX-side correlator).

**Loki structured fields:** `clOrdID`, `venue`, `correlationId`, `msgType` on every log line.

**Grafana dashboards** (committed under `deploy/observability/grafana/`, deployed alongside service code):
- `fix-gateway-overview` (lands phase 2): per-venue session-state status panel (red/green), in/out message rates, cancel ack latency p50/p95/p99, ghost-fill counter rate.
- `fix-gateway-execution-quality` (lands phase 3): partial-vs-full fill ratio, reject-rate-by-venue stacked, exec-report path divergence.
- `fix-gateway-placement` (lands phase 4): place-order latency p95 (gRPC overhead vs venue ack split), reject-rate canary-vs-baseline, PENDING_FAILED rate.

**Alert rules** (committed under `deploy/observability/prometheus/`):
- `FixSessionDownDuringTradingHours` — any `fix_session_state == 0` for >60s while venue cutoff says open. Page.
- `FixGhostFillDetected` — any `ghost_fill_detected_total` increment. Page.
- `FixExecReportDivergence` — `execution_report_path_divergence_total > 0` during phase 3 soak. Page.
- `FixUnacknowledgedOutbound` — any `unacknowledged_outbound_total` increment. Page.
- `FixCorrelatorBackPressure` — `pending_new_correlator_back_pressure_total` rate > 1/sec for 5min. Warn.
- `FixCancelAckLatencyHigh` — `cancel_ack_latency_seconds` p95 > 5s for 10min. Warn.

### Security

- gRPC mTLS between `position-service` and `fix-gateway` (matches existing intra-cluster pattern).
- FIX session credentials in K8s Secret `fix-gateway-venue-credentials` per venue. Never logged.
- Session log redaction: tag 554 (Password) and tag 925 (NewPassword) stripped before persistence.

### Operational

- Single-instance HPA pin (per ADR open-question #3) — explicit annotation in `fix-gateway-deployment.yaml`. Active-passive HA is a follow-on ADR.
- Runbook `docs/runbooks/fix-gateway-incident.md` covering: session re-establishment, sequence-number reset, replay from execution log.
- `/health` slash command updated to surface fix-gateway session state per venue.
- `/incident` slash command extended with a fix-gateway diagnostic step.

### Holiday calendar (ADR open-question #2)

- Phase 2 launches with regular-session cutoffs only (already in `VenueCutoffRegistry`).
- Holiday calendar is **deferred to phase 2.5** as a follow-on:
  - Static YAML at `fix-gateway/src/main/resources/venue-calendars/{NYSE,NASDAQ,LSE,TSE,HKEX}.yaml` with explicit closures + half-day cutoffs.
  - Loaded into `VenueCutoffRegistry` at boot.
  - Not blocking phase 2; missing calendar entries fall back to regular-session cutoff (existing behaviour).
- **UI behaviour during the gap (phase 2 ships before phase 2.5):** the order ticket renders a maintenance-style `RiskAlertBanner` (severity WARNING) on any GTD/Day order routed to a venue with no calendar entry, copy: "Holiday calendar incomplete for {venue} — verify session is open before submitting". Adds Playwright spec `ui/e2e/order-ticket-holiday-warning.spec.ts`. Removed once phase 2.5 lands.
- Vendor-feed integration (Bloomberg ECAL or equivalent) is post-launch.

### clOrdID minting (ADR open-question)

- **Decision**: `position-service` mints `clOrdID = order.orderId` (UUID v4 as today). `fix-gateway` receives it on each RPC and never mints its own. This is consistent with `FIXExecutionReportProcessor.kt:120` (`correlationId = order.orderId`).
- **Partition-key constraint:** `clOrdID` is reused as the Kafka partition key for `execution.reports` (12 partitions). UUID v4 entropy guarantees uniform distribution. **Future child-order schemes that share an `origClOrdID` parent stem MUST NOT be used as partition keys** — they would create hot partitions and break ordering guarantees. Documented as a comment on the proto field.
- **Cancel ClOrdID disambiguation:** the 35=F cancel mints a NEW `clOrdID` of the form `${origClOrdID}-cxl-${seq}` (FIX requires unique ClOrdID per outbound message); `OrigClOrdID` (tag 41) carries the original. This is set by fix-gateway, not the caller.

### Failure isolation

- `fix-gateway` outage → cancel emission becomes best-effort (sweeper still transitions state to EXPIRED per `ScheduledOrderExpirySweeper.kt:73-76`). **The trader-facing UI surfaces this via `VenueRoutingStatusDot` indicator (4.13) — amber when fix-gateway health is DOWN, with tooltip "Cancel confirmation unavailable — call venue directly".** Phase 4: `PENDING_FAILED` status routes around outage with operator-visible alarm.
- `position-service` outage → fix-gateway buffers inbound 35=8s in `execution.reports` Kafka (consumer lag absorbs the gap).
- **Ghost-fill protection (closes the cancel-race hole):** if a 35=8 fill arrives for an already-EXPIRED order, `FIXExecutionReportProcessor` (2.11b) creates a `ghost_fill` row, fires `RiskBreak` event with severity CRITICAL, does NOT auto-update Position, requires manual ops resolution. UI shows the ghost fill on the order detail panel via `RiskAlertBanner`.
- **Mass-cancel-on-disconnect:** on FIX session reconnect, fix-gateway enters reconciliation mode (4.3); rejects new outbound RPCs with `SESSION_DOWN` until 35=H reconciliation completes for every open `clOrdID`. This prevents stale book state after session bounce.

---

## User-facing UX contract

This plan introduces user-visible behaviour in every phase from 2 onwards. The contract below is binding before any phase ships.

### Order status visual hierarchy (`TradeBlotter.tsx` `statusBadgeClass()`)

| Status | Badge colour | Severity tier | Retry CTA | Copy |
|---|---|---|---|---|
| `PENDING` | grey | info | no | "Awaiting venue ack" |
| `SENT` | blue | info | no | "Working at venue" |
| `PARTIALLY_FILLED` | blue | info | no | "Partially filled — N of M" |
| `FILLED` | green | success | no | "Filled" |
| `CANCELLED` | grey | info | no | "Cancelled" |
| `EXPIRED` | grey | info | no | "Expired" |
| `REJECTED` | red | error | no | reject reason from `reject_reason` field |
| `PENDING_FAILED` (new, phase 4) | **amber** | warning | **yes** | "Order routing timed out — call venue to confirm before retry" |
| `EXPIRED + ghost fill` (new, phase 2) | red | critical | manual ops | "Fill received after cancel — contact ops" |

### Submit-flow state machine (`useOrderPlacement` hook, 4.13)

```
idle → submitting → (success | failed | duplicate)
                  ↑ button disabled, "Sending to venue..." copy, spinner
```

- Submit button uses `Button.tsx`'s existing `loading` prop.
- During `submitting`, the button is disabled (no double-submit risk).
- Per-venue timeout (200ms–5000ms) governs the wait window; the spinner shows for the full wait without a deceptive "almost done" state.
- On `failed` (PENDING_FAILED): order persists in blotter as amber, retry CTA enabled, original `clOrdID` preserved.
- On `duplicate` (DUPLICATE_IN_FLIGHT): banner-only, retry CTA disabled.

### Indicators

- **`VenueRoutingStatusDot`** — blotter-header indicator consuming `/health` aggregator. Green when all venue sessions UP; amber when ≥1 DOWN (with per-venue tooltip listing); red when fix-gateway service unreachable.
- **`Venue Order ID`** column in `TradeBlotter` — opt-in via column-visibility toggle, monospaced, right-aligned, clipboard-copy button per row, ARIA-labelled, included in CSV export.
- **`RiskAlertBanner`** instances (consume existing `RiskAlertBanner.tsx` severity tiers):
  - WARNING severity: PENDING_FAILED, DUPLICATE_IN_FLIGHT, holiday-calendar-incomplete venue.
  - CRITICAL severity: ghost fill detected on EXPIRED order.

### Playwright coverage (binding before each phase ships)

Per CLAUDE.md, every new tab/panel/dialog/workflow needs a Playwright spec. New specs:
- Phase 2: `order-blotter-degraded-routing.spec.ts`, `order-detail-ghost-fills.spec.ts`, `order-ticket-holiday-warning.spec.ts` (until phase 2.5 lands).
- Phase 3: `order-blotter-fill-arrival.spec.ts`.
- Phase 4: `order-placement.spec.ts`, `order-placement-timeout.spec.ts`, `order-placement-rejected.spec.ts`, `order-placement-duplicate-in-flight.spec.ts`, `order-venue-id.spec.ts`, `venue-routing-degraded.spec.ts` (extended from phase 2 if scope grew).

`cd ui && npm run lint` before every UI commit (per CLAUDE.md).

---

## Risk register

| Risk | Mitigation | Owner |
|---|---|---|
| QuickFIX/J version skew across venue counterparties | Pin a single QuickFIX/J version; venue-specific dictionaries under `fix-gateway/src/main/resources/quickfixj/`. | execution-tech |
| Sequence-number divergence between Postgres state and venue | On boot, send `Logon` with `ResetSeqNumFlag=N` and reconcile via `Logout`+`SeqReset` if the venue disagrees. Sequence-state migration `V2__fix_session_state.sql` enforces single-row-per-venue. Tests cover cold start, corrupt row, seq-behind, seq-ahead, and GapFill (2.12). | execution-tech |
| Phase 3 silently drops events during the cutover | Feature flag `EXECUTION_REPORTS_VIA_KAFKA` runs old + new in parallel for one week before old path is deleted. **`execution_report_path_divergence_total` Prometheus gauge required to be 0 for the full week**; alert fires on any non-zero value. `DualPathParityAcceptanceTest` (3.11) gates the build. | execution-tech + risk-eng |
| Phase 4 latency exceeds 2ms p95 budget | Acceptance test 4.10 fails the build if `fix_grpc_place_order_overhead_seconds` p95 > 2ms on a no-op stub. End-to-end venue-ack latency tracked separately as Grafana SLO. | execution-tech |
| Position-service couples to gRPC-generated types | `OrderCancelEmitter`, `VenueOpenChecker`, and `FixGatewayClient` are interfaces in `common/`; position-service depends on those, not on gRPC stubs. | execution-tech |
| Single-instance fix-gateway is an SPOF | Phase-1 launch accepts this per ADR open-question #3; HA is a follow-on ADR scoped post-A-13. | sre |
| Migration of `FIXMessageConverter` breaks existing trade booking | Phase 3 acceptance test 3.11 reuses every assertion from `FIXExecutionReportProcessorTest` to prove behavioural parity. `ExecutionReportEvent` carries both typed `eventType` and raw `execType` so processor signature is unchanged. | risk-eng |
| Holiday calendar gaps cause day-orders to expire on a venue holiday | Phase-2 fallback is regular-session cutoff (today's behaviour); phase 2.5 layers in the calendar. **Trader-facing maintenance banner** during the gap warns on missing calendar entries. | execution-tech |
| **Double-submit on PENDING_FAILED retry → duplicate venue order** | Fix-gateway tracks in-flight `clOrdID`s and 24h history in `fix_message_log`; second submission with same `clOrdID` returns `DUPLICATE_IN_FLIGHT`. Reconciliation via `OrderStatusRequest` (35=H) before re-submit. UI retry CTA reuses original `clOrdID` so reconciliation triggers. | execution-tech + product |
| **Ghost fill: venue fills order between cancel-emit and cancel-confirm** | `FIXExecutionReportProcessor` detects fills against EXPIRED/CANCELLED/REJECTED orders, persists to `orders.ghost_fills`, fires CRITICAL `RiskBreak`, does NOT auto-update Position. UI surfaces via `RiskAlertBanner`. | risk-eng |
| **Stale book state after FIX session bounce (mass-cancel-on-disconnect not handled)** | On reconnect, fix-gateway enters reconciliation mode: rejects new outbound RPCs with `SESSION_DOWN` until 35=H sent for every open `clOrdID` in `fix_message_log`. Captured in runbook `docs/runbooks/fix-gateway-incident.md`. | execution-tech |
| **Pending-new correlator races (TTL eviction vs late completion, pre-arrival completion, duplicate clOrdID)** | Deferred registered before 35=D send; TTL eviction uses atomic `compareAndSet`; duplicate clOrdID returns `DUPLICATE_IN_FLIGHT`; back-pressure cap of 500 in-flight per venue. `PendingNewCorrelatorTest` covers all three races. | execution-tech |
| **Pending-new correlator is single-instance only — blocks future HA** | Documented constraint inherited by HA follow-on ADR. Active-active will require distributed store (Redis or similar) or sticky routing. | sre |
| **gRPC deadline propagation mismatch between HTTP, gRPC, and FIX** | Layered deadlines: gRPC = `venue_ack_timeout_ms + 500ms grace`; HTTP route = `gRPC + 500ms`. Tested in `OrderSubmissionServiceAcceptanceTest`. | execution-tech |
| **Kafka partition rebalance during phase-3 consumer rollout pauses processing** | `ExecutionReportConsumer` uses `CooperativeStickyAssignor` (matches existing pattern). Rolling deploy verified in staging before production canary. | execution-tech |
| **Nested-enum proto codegen produces unexpected Kotlin names** | `PlaceOrderProtoTest` (4.8) round-trips every enum and asserts the qualified name. Verified before phase-4 commit. | execution-tech |
| **Flyway migration ordering: V23 (PENDING_FAILED) must precede position-service binary** | Helm chart pre-deploy hook runs migrations before pod rollout; documented in deploy runbook. | sre |
| **`fix_message_log` unbounded growth** | Declarative monthly partitioning + 90-day hot retention + S3 cold-archive pipeline. Pruning job emits `fix_message_log_partitions_archived_total`. | execution-tech + dba |
| **Kafka 7-day retention insufficient for regulatory replay** | Extended to 30 days. `fix_message_log` (with 90-day hot + S3 cold) is the authoritative replay source for older windows. | risk-eng |
| **Drop-copy absence: fix-gateway log is sole source of truth pre-launch** | `unacknowledged_outbound_alerter` job runs every 5min during trading hours; pages on any unack older than 30min. Daily `kafka_log_reconciliation_job` cross-checks Kafka vs log. | risk-eng |
| **35=G replace flow out-of-scope is a real workflow gap** | Documented as a known launch-time limitation, not a deferred enhancement. Trader workflow guidance: cancel + re-enter for any amend. Phase 5 follow-on. | product |
| **Per-venue latency budgets vary by 25× (NYSE 200ms vs EM 5000ms)** | Per-venue timeouts in `VenueSessionRegistry`; 2s global default rejected. `PlaceOrderRpcAcceptanceTest` matrix exercises each venue's bound. | execution-tech |

---

## Out of scope for this plan

- Active-active or active-passive HA for fix-gateway (separate ADR post-A-13). **Inherited constraint:** the in-memory pending-new correlator (4.4) requires replacement with a distributed store before HA is possible.
- Vendor holiday-calendar feed integration (Bloomberg ECAL or equivalent).
- Drop-copy session ingestion (post-launch). **Compensating control until then:** `unacknowledged_outbound_alerter` + daily `kafka_log_reconciliation_job` (see Observability).
- **Replace flow (`OrderCancelReplaceRequest` 35=G outbound) — KNOWN LAUNCH-TIME WORKFLOW LIMITATION, not just a deferred enhancement.** Only inbound 35=8 ExecType=5 is in scope (phase 3). Without outbound replace, traders cannot amend price or quantity on a resting limit order — they must cancel and re-enter, which adds re-entry risk on fast-moving markets. Trader-facing documentation must call this out explicitly. Phase 5 follow-on.
- Vendor-OMS substitution path (ADR-0035 alternative #3).
- ITCH/OUCH or non-FIX venue protocols.

---

## Tracking

After each phase commit:
- Tick the corresponding `[ ]` in this plan.
- Update `docs/spec-drift-audit.md` A-13 entry once phase 2 lands (drop the cancel-stub caveat).
- Update ADR-0035 Status to "Complete" only after phase 4 canary completes (1% → 5% → 10% for 5 trading days, all SLOs within ±2σ of baseline).
- Confirm Grafana dashboards + alert rules under `deploy/observability/` are deployed alongside service code (binding before each phase ships).
- Verify `execution_report_path_divergence_total = 0` for the full one-week phase-3 soak before commit 4 lands.

## Context-clearing checkpoints

Recommended `/clear` points:
1. After phase 1 commits (skeleton in place).
2. After phase 2 commits (A-13 closed).
3. After phase 3 wire-format definition (commit 1).
4. After phase 3 cutover (commit 4) — pause for one week's production soak.
5. After phase 4 canary completes.

Each new session begins by re-reading `docs/adr/0035-fix-gateway-service-extraction.md` and this plan.

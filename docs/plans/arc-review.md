# Kinetix Architectural Review

*By Elena — principal engineer, 30+ years across trading/risk systems.*
*Review date: 2026-05-28 (revised 2026-05-29 — observability promoted to the headline; market-data consolidation deferred pending separate user discussion; K8s-specific recommendations parked because the demo runtime is docker-compose).*

---

## Context

You asked for a review of the current system and improvement suggestions. This is a comprehensive walk across all axes — production-readiness, architectural simplification, and performance — based on ground-truth exploration of the repo. The goal is to call out what is genuinely strong (so we do not damage it), name the real gaps honestly, and recommend a path forward that prioritises high-ROI, low-disruption work first.

This is a review document, not an implementation plan. Each recommendation in §6 and §7 could be split into its own beads issue and worked individually.

---

## 1. Verdict (one line)

**Kinetix is a well-engineered, well-tested mid-sized distributed platform with a few specific architectural rough edges — not a system that needs rescuing, but one that would repay targeted, surgical work in three areas: observability (the headline gap), trust boundaries, and a small amount of code organisation.**

This is unusual. Most reviews I do find platforms with deep structural problems. Kinetix has discipline most teams aspire to.

**The single biggest opportunity is end-to-end observability — wire OTel through every service, propagate correlation IDs across every boundary, and verify it with a real trace test. Today the Tempo + collector stack is running but blind. That changes the operational posture of the platform more than any other single piece of work in this review.**

---

## 2. What is solid (preserve, do not regress)

These are working well. Future changes should not erode them.

- **TDD is actually practised.** 8,910 acceptance tests, 5,205 integration tests, 127 Playwright specs, zero `@Disabled`/`@Ignore`/`.skip`. The CLAUDE.md philosophy is operational, not aspirational. (Refs: `position-service/src/test/`, `end2end-tests/`, `ui/e2e/`.)
- **Spec-as-source-of-truth.** 27 Allium spec files with active divergence reports under `specs/divergences/` (914 lines from the 2026-05-28 sweep). Drift is hunted, not tolerated. Keep `/weed` in the regular cadence.
- **Healthy shared kernel.** `common/` is 104 files organised by concern (kafka, resilience, security, health, observability, audit, persistence). Not a junk drawer. The `CircuitBreaker`, `RetryableConsumer`, and `ReadinessChecker` abstractions are good.
- **Consistent communication patterns.** Sync HTTP for fetches, gRPC for typed RPCs, Kafka for events. Each used appropriately. Circuit breakers on every cross-service call.
- **Database-per-service with TimescaleDB + Flyway.** Strict isolation, sequential migrations, no dangerous patterns observed (the `CREATE INDEX CONCURRENTLY` gotcha is acknowledged in CLAUDE.md).
- **Audit trail (ADR-0017) implemented correctly.** SHA-256 hash chain in `audit-service/src/main/kotlin/com/kinetix/audit/persistence/AuditHasher.kt`, verified incrementally and on startup. This is the kind of detail that takes systems years to get right.
- **Issue hygiene.** 290/298 beads issues closed. 7 open are legitimate, not stale.
- **Discovery-Valuation contract (ADR-0029).** Risk-engine is a pure calculator; orchestrator owns data fetching. This is the right shape.
- **Two-phase contract enforcement.** `MarketDataDependenciesService` gRPC method lets the orchestrator parallel-fetch only what the calculation actually needs. Elegant.

---

## 3. Findings — production-readiness gaps

### 3.1 Distributed tracing infrastructure is unused (high severity)

Tempo + OTel collector are deployed (`deploy/observability/docker-compose.observability.yml`). Prometheus metrics are wired. But **no OpenTelemetry SDK instrumentation exists in the Kotlin services** beyond test setup. You are paying the cost of running the stack without getting cross-service traces.

**Why it matters:** When risk-orchestrator times out and the user sees a 504, you currently cannot answer "which downstream service was slow on this specific request?" without correlating logs by timestamp across 5+ services. Traces collapse that to one query.

**Evidence:** Searched for `io.opentelemetry.sdk` and `OpenTelemetrySdk` in service production code — zero hits. The hooks are there in `common/observability/` but unwired.

### 3.2 Correlation ID propagation is incomplete (medium severity)

`AuditEvent.correlationId` is captured server-side but not surfaced in API responses (open ticket **kx-oy2**). More importantly, I see no consistent middleware to propagate it through HTTP client calls, gRPC interceptors, and Kafka headers in `common/`.

**Why it matters:** Without an end-to-end correlation ID, a user-reported issue cannot be traced to the originating request. The audit chain knows; the rest of the system does not.

### 3.3 Error handling is inconsistent (medium severity)

- `position-service`: 29 `try-catch` blocks, several catching generic `Exception` (smell).
- `gateway/Application.kt`: 2 generic `catch (_: Exception)` blocks in HMAC validation and WebSocket broadcasting that silently swallow.
- No centralised Ktor `StatusPages` installation mapping typed exceptions to HTTP responses.
- Custom exception hierarchy exists (`UpstreamErrorException`, `ServiceUnavailableException`, `GatewayTimeoutException`) but is not used uniformly.

**Why it matters:** Silent catches hide bugs. Inconsistent error mapping means the UI sees different shapes for similar failures across services.

### 3.4 Trust boundary is gateway-mediated, not network-enforced (medium severity, contextual)

`TlsConfig` in `common/` is **disabled by default**. There is no mTLS between services. The model is: gateway validates JWT, extracts user/role, forwards headers; downstream services trust the headers.

**Why it matters:** Any compromised pod inside the cluster can call any service as any user by setting the right headers. For institutional risk platforms targeting Tier-1 buyers, this would fail a security review. For pre-prod or single-tenant deployments, it is acceptable. **Whether this is a gap depends on your deployment model.**

### 3.5 No Kafka schema registry (low severity, contextual)

Schemas are Kotlin `@Serializable` data classes. `schema-tests/` runs compatibility tests on commit. This is a reasonable trade-off for a monorepo, but a registry (Confluent Schema Registry, or `buf` with Protobuf) would catch breaks earlier and enable independent consumers (e.g. analytics tools) without recompiling.

---

## 4. Findings — architectural simplification

### 4.1 Market data is over-decomposed (high impact, high effort)

You have four services for market data:
- `price-service` (30 files)
- `rates-service` (245 files)
- `volatility-service` (237 files)
- `correlation-service` (227 files)

The 30-vs-245 file disparity is the clearest tell — `price-service` is structurally identical to the others in shape (HTTP REST + Kafka producer + Timescale-backed time series) but ~8× smaller. The other three look like they grew by copy-paste of the same scaffolding.

These are **one bounded context** ("market data") with four internal modules. Splitting them into separate services buys nothing — they share data shape, ownership, deployment cadence, scaling profile, and consumers (always risk-orchestrator). It costs:
- 4× the Helm charts, dashboards, alert rules, runbooks.
- 4× the network hops on every risk calculation.
- 4× the maintenance surface (auth wiring, observability wiring, common-module bumps).

**Recommend:** consolidate into a single `market-data-service` with internal `price/`, `rates/`, `volatility/`, `correlation/` packages, each with its own routes and Kafka producer. One process, one DB instance, one Helm chart. See §6.2 for the migration path.

### 4.2 `notification-service` at 725 files is implausibly large

For a notification dispatcher, 725 files is 3× what I would expect. This either means:
- It does more than notify (alert rules, escalation policies, RBAC triage — all visible in the UI screens) — in which case it is misnamed and should be `alerts-service`, possibly split.
- Or it is bloated with boilerplate.

**Recommend:** Spend a day reading the top 30 files by line count in `notification-service/src/main/kotlin/`. If it has grown a separate "alert lifecycle" bounded context, extract it. If it is boilerplate, prune.

### 4.3 `gateway/Application.kt` is 899 lines

40+ route overloads in one file. Standard Ktor pattern is to split into route modules: `PriceRoutes.kt`, `RiskRoutes.kt`, `CopilotRoutes.kt`, etc., each as an extension function on `Route`. Some of this is already done; finish the job.

### 4.4 `risk-orchestrator` at 532 files — audit, do not assume bloat

This is the largest service. Given the responsibilities (5-phase pipeline, scheduled jobs, cross-book aggregation, regulatory reporting, regime detection, hedge recommendations), this may be appropriately sized. But it deserves a focused audit:
- Are scheduled jobs, REST routes, and Kafka consumers each cleanly separated?
- Is there a single `RiskOrchestrationService` god-class, or is the work properly delegated?
- Could the regulatory and ML-prediction concerns be extracted as separate services that consume `risk.results`?

I would not refactor this without first understanding it deeply. Flag for a §6.4-style follow-up.

---

## 5. Findings — performance and scale

### 5.1 HPA disabled (open ticket kx-kdbt) — **parked: docker-compose runtime**

`minReplicas: 1, maxReplicas: 5, targetCPU: 70%` — but HPA is off, and crucially the current demo runtime is docker-compose, not Kubernetes. The Helm charts are present but not the active deployment. HPA rollout is parked until a K8s deployment is on the roadmap. For docker-compose, the equivalent operational concern is sizing container CPU/memory limits and tuning the number of replicas explicitly in compose — not autoscaling.

### 5.2 Synchronous fan-out latency in the gateway

For risk views, gateway calls `risk-orchestrator`, which in turn fan-outs to 4 market data services + position-service over HTTP. Latency stacks linearly through that chain. The orchestrator already parallelises (`coroutineScope { async { } }`), which is correct. Two further wins:

- **Reduce hops:** consolidating market data (§4.1) collapses 4 calls to 1.
- **Cache aggressively:** the Redis cache already exists; verify hit rates with metrics.

### 5.3 Risk-engine throughput (Python GIL)

Python gRPC server with NumPy-heavy workloads. Each calculation releases the GIL during native NumPy work, so single-process concurrency is fine. But:
- Verify the gRPC server uses a thread pool sized for actual parallelism (`ThreadPoolExecutor(max_workers=N)`).
- For CPU-bound paths (Monte Carlo with pure-Python inner loops), consider multiprocessing or moving the hot loop to Cython/Numba — but only if profiling justifies it.

### 5.4 Discovery-Valuation could batch better

Right now, `MarketDataDependenciesService` is called once per Valuate request. If the same orchestrator processes many books concurrently, dependency discovery is repeated. Cache dependency declarations per `(asset_class, calculation_type)` — they change rarely.

---

## 6. Top 5 recommendations — concrete migration paths

These are ordered by ROI (impact / effort). Each is independent.

### 6.1 Wire OpenTelemetry across all services (rec #1 — highest ROI)

**Outcome:** Distributed traces in Tempo with full cross-service spans for every request.

**Path:**
1. Add an `Observability.kt` helper in `common/observability/` that wires the OTel SDK (BatchSpanProcessor → OTLP exporter pointing at the OTel collector).
2. In each Ktor `Application.kt`, install Ktor's OTel server plugin (auto-spans incoming HTTP).
3. Add an OTel `HttpClientInterceptor` to the shared HTTP client factory in `common/` so outbound calls propagate `traceparent`.
4. Add an OTel gRPC `ClientInterceptor` to the shared gRPC channel builder.
5. Wrap Kafka producers/consumers to write/read `traceparent` from headers.
6. Smoke test: book a trade in the UI, follow the trace through gateway → position-service → Kafka → risk-orchestrator → risk-engine in Tempo.

**Effort:** ~3 days. **Risk:** very low — purely additive.

### 6.2 ~~Consolidate market data services~~ — DEFERRED

The original recommendation here was to fold price/rates/volatility/correlation into a single `market-data-service`. The user has signalled a different idea for this area and we will take it up separately. **No issue filed for this; do not act on the original §6.2 until that discussion happens.** Findings in §4.1 stand as raw observations only.

### 6.3 Centralise error handling at the gateway (rec #3 — best safety win)

**Outcome:** One consistent HTTP error shape for the UI, regardless of which downstream failed.

**Path:**
1. Define a single `ApiError(code, message, correlationId, details?)` DTO in `common/dtos/`.
2. Install Ktor `StatusPages` in `gateway/Application.kt` with explicit mappings:
   - `UpstreamErrorException` → matching HTTP status from `statusCode` field.
   - `ServiceUnavailableException` → 503 with `Retry-After`.
   - `GatewayTimeoutException` → 504.
   - `IllegalArgumentException` → 400.
   - `Throwable` → 500 with logged stack trace, never the message.
3. Audit every `catch (_: Exception)` in the gateway and replace with either typed handling or removal (let the StatusPages handler do its job).
4. Define `StatusPages` in each backend service too, with the same `ApiError` shape, so gateway-to-backend errors deserialise cleanly.
5. Add a small acceptance test per service that asserts the error shape.

**Effort:** ~2 days. **Risk:** low — improves debuggability immediately.

### 6.4 Audit and (if needed) split `risk-orchestrator` and `notification-service`

**Outcome:** Clear answer to whether either service has bloated, and concrete next steps if so.

**Path:**
1. Run `cloc` on each service's `src/main/kotlin/`.
2. List the top 20 files by LoC. Read them. Identify the conceptual modules.
3. For risk-orchestrator, candidate extraction: regulatory reporting + ML regime detection could become their own services consuming `risk.results` rather than living inside the orchestrator. This depends on what you find.
4. For notification-service, the question is "is this one bounded context or two?" (alert generation vs. alert delivery). If two, extract `alerts-service` with notification-service as the downstream delivery layer.
5. **Do not refactor until the audit is done.** This recommendation is "investigate", not "act".

**Effort:** ~1 day audit; refactor effort TBD. **Risk:** medium if refactor is undertaken.

### 6.5 ~~Roll out HPA per ADR-0026~~ — PARKED (docker-compose runtime)

The demo runs as docker-compose, not Kubernetes, so HPA is not applicable today. Existing `kx-kdbt` remains open for when/if a K8s deployment is on the roadmap. For docker-compose, sizing concerns belong in compose `deploy.resources` blocks and explicit `replicas` counts.

---

## 7. Secondary punch list

Each one-liner could become its own beads issue.

- **Move `gateway/Application.kt` route overloads into per-domain `Route` extension files.** Standard Ktor refactor; reduces file from 899 lines to ~200.
- **Adopt a Kafka schema registry or convert to Protobuf for Kafka events.** Mostly a nice-to-have given `schema-tests/` already exists; revisit when external consumers are added.
- **Decide explicitly on the network trust model.** Either enable mTLS via `TlsConfig` (it is built, just disabled) or document explicitly that the cluster network is the trust boundary and ensure NetworkPolicies enforce it.
- **Cache dependency declarations from `MarketDataDependenciesService`.** Per-`(asset_class, calc_type)` cache, invalidate on risk-engine deploy.
- **Verify Python gRPC thread pool sizing in `risk-engine/src/kinetix_risk/server.py`.** Should be tuned to NumPy parallelism profile.
- **Replace remaining `catch (_: Exception)` swallows in `gateway/Application.kt` and `position-service`.** Either re-raise typed, or let StatusPages handle it.
- **Resolve open beads issues:** kx-oy2 (correlationId in DTO), kx-y3y (failure counter metric), kx-66x (dead useAlertStream hook), kx-0ic (Claude CLI in container).
- ~~AI insights credentials.~~ Closed (`kx-t6ll`) — the proposed Vault-backed Kubernetes Secret pattern does not apply to a docker-compose deployment. `~/.claude` readonly mount remains the demo pattern. Revisit if a K8s deployment lands.
- **Audit `fix-gateway` phase 4.** It is in-flight with a `LoggingFIXOrderSender` fallback. Make sure the canary rollout (1% → 5%) is metric-gated, not time-gated.

---

## 8. What NOT to do (resist these)

Easy temptations that would reduce value:

- **Do not introduce GraphQL.** REST + WebSocket + SSE covers your needs. GraphQL would add a schema layer over an already well-structured API.
- **Do not migrate to Quarkus/Spring/etc.** Ktor is doing its job. Migrations cost six months and buy nothing visible to users.
- **Do not split risk-orchestrator without the §6.4 audit first.** It may already be the right size.
- **Do not introduce a service mesh (Istio/Linkerd).** Your circuit breakers and retries are already in `common/`. A mesh would duplicate them and add operational complexity.
- **Do not adopt event sourcing for the audit trail.** The hash-chained design is doing what event sourcing would do, with less machinery.
- **Do not push for 100% trace sampling.** When OTel wiring is in (§6.1), use head-based sampling (e.g. 10%) with tail-based sampling for errors. Full sampling is expensive in Tempo storage.
- **Do not rewrite the Python risk-engine in Rust/Go.** Numerical work in NumPy is competitive with native code for the calculations you do, and the team's velocity matters more than micro-optimisations until profiling proves otherwise.

---

## 9. Verification

After implementing any of the above, verify behaviour end-to-end:

- **OTel wiring (§6.1):** Open Tempo at `https://grafana.kinetixrisk.ai`, run a trade book + risk calc flow, confirm a single trace spans gateway → position-service → Kafka → risk-orchestrator → risk-engine.
- **Market data consolidation (§6.2):** All existing acceptance tests must pass against the new `market-data-service`. Gateway and risk-orchestrator clients should require no logic changes — only host config.
- **Error handling (§6.3):** Hit a known failing endpoint (e.g. invalid bookId), verify single consistent `ApiError` shape in response. Acceptance test per service.
- **HPA rollout (§6.5):** Trigger the `load-test.yml` workflow, observe replicas scaling in Grafana.

For each: full module test suite (`./gradlew :<module>:test`), affected acceptance tests (`./gradlew :<module>:acceptanceTest`), and the relevant Playwright spec if UI is touched.

---

## 10. How to consume this review

If you want a single starting point: **§6.1 (OpenTelemetry wiring + correlation IDs)**. Lowest risk, highest visibility win, unlocks everything else. This is the headline.

If you have a sprint to spend: **§6.1 + §6.3 + §7 trust-model decision**. Together those make the platform materially safer to operate, in roughly one week of focused work. (§6.5 is parked because the runtime is docker-compose, not K8s.)

If you have a quarter: **add §6.4 (audit + targeted splits)** and pull from the §7 punch list as capacity allows.

§6.2 is deferred for separate discussion and is not on the current punch list.

Beads issues created from this review are tracked in `bd` — see the table the user was shown when issues were filed.

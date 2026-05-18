# Testing Overhaul — Follow-Up Plan

## Context

The May 2026 testing excellence campaign (see commits `004c0c81` through `7fb986e2` on `main`) landed Phase 1 (coverage measurement everywhere), most of Phase 2 (correlation + volatility test gaps), part of Phase 3 (Black-Scholes hypothesis tests + Kotlin hash/vol-surface properties), the skeleton of Phase 4 (test-support module), most of Phase 5 (Python + UI mutation testing config), and Phase 6 (docs + ratchet + naming linter).

Substantial items from the original plan remain. This document is the punch list — what's missing, why it was deferred, and what it would take to close. Items are ordered top-to-bottom by execution priority (impact-first per the original plan's recommendation); each leaf is a `- [ ]` checkbox with an acceptance command so `/work-plan` (typically wrapped in `/loop`) can advance them autonomously.

## Decisions applied (override before execution if you disagree)

1. **PSD validation (was item 4):** Add a new `CorrelationPsdValidator` class with eigenvalue tolerance `1e-6` rather than mutating the `CorrelationMatrix` domain model. Rationale: domain-model PSD enforcement risks invalidating real feed/seed data that's only near-PSD due to numerical noise; a separate validator keeps the invariant under test without coupling it to construction. The `1e-6` tolerance matches conventional float64 numerical-noise floors for n×n correlation matrices.
2. **Kotlin mutation testing path (was item 6):** Implement the `pitest-command-line` CLI-via-exec workaround now. Rationale: matches the plan's stated recommendation; the gradle-pitest plugin's Gradle 9 compatibility has not shipped after six months.
3. **Naming linter location (was items 8 vs 9):** Keep `scripts/check-test-naming.py` and tighten its regex (item 8). Retire item 9. Rationale: cross-stack uniformity outweighs Gradle-task IDE integration for a linter that runs in seconds; the Python script also already lints non-Kotlin tests, which a Gradle convention plugin cannot.

## CI/CD approval

Checkboxes 6.7 and 7.2 modify `.github/workflows/`. This plan grants approval for those specific edits — the executing subagent does not need to re-confirm before changing those files.

## Remaining work

### 1. notification-service Kafka integration tests (Phase 2)

**Status:** 0 of 4 files. My initial attempt in the campaign was blocked when a concurrent agent reverted `testImplementation(libs.testcontainers.kafka)` on `notification-service/build.gradle.kts`. The shared `test-support` module created in Phase 4 now re-exports `testcontainers.kafka` transitively, which unblocks this without re-adding the direct dependency.

**Build change** (folded into checkbox 1.1 — the first test to need it):

```kotlin
// notification-service/build.gradle.kts — under dependencies { }
testImplementation(project(":test-support"))
```

That brings in `org.testcontainers.kafka.KafkaContainer` transitively, plus the shared `KafkaTestSetup` helper at `com.kinetix.testsupport.kafka.KafkaTestSetup`.

**Highest-impact remaining gap** — closes the structural blind spot for an event-driven alerting service.

- [x] **1.1** Create `notification-service/src/test/kotlin/com/kinetix/notification/kafka/LimitBreachEventConsumerIntegrationTest.kt`. Also add `testImplementation(project(":test-support"))` to `notification-service/build.gradle.kts`. Produce a real `LimitBreachEvent` to a Testcontainers Kafka topic; assert the consumer persists an `AlertEvent` and invokes the delivery service. Pattern: in-memory `AlertEventRepository` + recording `DeliveryService`, wait for `repository.findRecent(10)` to become non-empty with `assertTimeoutPreemptively(Duration.ofSeconds(15))`. Cover a clean event AND a poisoned-payload-then-valid-payload sequence to verify the consumer loop survives.
  - Acceptance: `./gradlew :notification-service:integrationTest --tests "*LimitBreachEventConsumerIntegrationTest"`
- [x] **1.2** Create `notification-service/src/test/kotlin/com/kinetix/notification/kafka/RiskResultConsumerIntegrationTest.kt` — same shape as 1.1 for `RiskResultEvent` flowing into `RulesEngine` → `DeliveryRouter`.
  - Acceptance: `./gradlew :notification-service:integrationTest --tests "*RiskResultConsumerIntegrationTest"`
- [x] **1.3** Create `notification-service/src/test/kotlin/com/kinetix/notification/kafka/NotificationConsumerDlqIntegrationTest.kt` — produce a permanently-failing payload, verify the DLQ topic (`<topic>.dlq`) receives the message via a real `KafkaConsumer` subscribed to the DLQ.
  - Acceptance: `./gradlew :notification-service:integrationTest --tests "*NotificationConsumerDlqIntegrationTest"`
- [ ] **1.4** Create `notification-service/src/test/kotlin/com/kinetix/notification/kafka/RetryableConsumerNotificationIntegrationTest.kt` — exercise `RetryableConsumer` wiring (retry → eventual success on transient errors; retry → DLQ on permanent errors) under real broker rebalance conditions. The canonical template is `position-service/src/test/kotlin/com/kinetix/position/kafka/RetryableConsumerKafkaIntegrationTest.kt`.
  - Acceptance: `./gradlew :notification-service:integrationTest --tests "*RetryableConsumerNotificationIntegrationTest"`

---

### 2. Remaining Python property tests (Phase 3)

**Status:** Black-Scholes invariants are in `risk-engine/tests/test_black_scholes_invariants.py`. Two property files from the original plan are missing. Follow the pattern at `test_black_scholes_invariants.py` — strategies, settings, `@given`, hypothesis seed determinism is automatic in CI.

- [ ] **2.1** Create `risk-engine/tests/test_var_invariants.py`:
  - VaR magnitude monotone in confidence level: `VaR(0.99) ≥ VaR(0.975) ≥ VaR(0.95)` for any portfolio.
  - VaR scales monotonically with horizon: `sqrt(T)` scaling under the parametric assumption (already implemented in `var_parametric.py` — verify the property holds across random portfolios).
  - VaR is non-negative for any well-formed input (loss magnitude convention).
  - Cap each test at `max_examples=100` because each VaR call is heavier than a BS price.
  - Acceptance: `cd risk-engine && uv run pytest tests/test_var_invariants.py -m unit`
- [ ] **2.2** Create `risk-engine/tests/test_bond_pricing_invariants.py`:
  - Price-yield round-trip: `price(yield(price)) ≈ price` within numerical tolerance over a domain of valid (coupon, maturity, face) inputs.
  - Bond price monotone decreasing in yield.
  - Duration is non-negative.
  - Convexity is non-negative.
  - Acceptance: `cd risk-engine && uv run pytest tests/test_bond_pricing_invariants.py -m unit`

---

### 3. Kotlin property tests for risk-orchestrator and position-service (Phase 3)

**Status:** Two properties landed (`labelsHash` permutation-invariance, `volAt` knot-bounds). The mathematically heaviest ones — VaR monotonicity, P&L additivity, position aggregation associativity — are not done. Read the existing `VaRCalculationService` and `PositionAggregationService` first; use Kotest's `Arb` for trade/position generators; cap `iterations` at 50–100 because each VaR run involves the Python sidecar.

- [ ] **3.1** Create `risk-orchestrator/src/test/kotlin/com/kinetix/orchestrator/property/VaRCalculationPropertyTest.kt`:
  - VaR result monotone in confidence level on a random portfolio.
  - VaR additivity does **not** hold (sub-additivity invariant: `VaR(A∪B) ≤ VaR(A) + VaR(B)` for coherent risk measures — verify this holds for the historical method and document it as a property).
  - Empty portfolio returns zero VaR.
  - Acceptance: `./gradlew :risk-orchestrator:test --tests "*VaRCalculationPropertyTest"`
- [ ] **3.2** Create `risk-orchestrator/src/test/kotlin/com/kinetix/orchestrator/property/PnLAdditivityPropertyTest.kt`:
  - For a portfolio split into N book-sized chunks, sum of per-book P&L equals portfolio-level P&L within floating-point tolerance.
  - Property holds across random book partitions and random market shifts.
  - Acceptance: `./gradlew :risk-orchestrator:test --tests "*PnLAdditivityPropertyTest"`
- [ ] **3.3** Create `position-service/src/test/kotlin/com/kinetix/position/property/PositionAggregationPropertyTest.kt`:
  - For any sequence of trades, aggregating them in any order yields the same net position (commutativity).
  - Aggregating sub-sequences and then aggregating the results equals aggregating the full sequence (associativity).
  - These properties already hold by the algebra — these tests lock them in against accidental future regressions.
  - Acceptance: `./gradlew :position-service:test --tests "*PositionAggregationPropertyTest"`

---

### 4. Correlation & volatility additional invariants (Phase 2/3 overlap)

**Status:** `CorrelationMatrixEdgeCasesIntegrationTest` + `CorrelationMatrixHashingTest` + `CorrelationMatrixHashPropertyTest` cover construction, lookup, and hashing. `VolSurfaceInvariantsTest` + `VolSurfacePropertyTest` cover surface invariants. Three gaps remain.

- [ ] **4.1** Add `correlation-service/src/main/kotlin/com/kinetix/correlation/validation/CorrelationPsdValidator.kt` plus `correlation-service/src/test/kotlin/com/kinetix/correlation/validation/CorrelationPsdValidatorTest.kt`. The validator computes eigenvalues (Apache Commons Math or EJML — whichever is already on the classpath; do NOT add a new dependency) and returns a `PsdValidationResult` with `isPsd(tolerance: Double = 1e-6)`. Tests verify: known-PSD matrices pass; matrices with one negative eigenvalue (e.g., `-0.5`) fail; near-PSD matrices with smallest eigenvalue within `±1e-7` pass at the default tolerance; the existing seed/feed correlation matrices all pass (load them in a parameterised test).
  - Acceptance: `./gradlew :correlation-service:test --tests "*CorrelationPsdValidatorTest"`
- [ ] **4.2** Create `volatility-service/src/test/kotlin/com/kinetix/volatility/property/VolSurfaceButterflyTest.kt`. For three knots at a fixed maturity with strikes `K1 < K2 < K3`, the vol smile should be convex enough that `C(K1) - 2·C(K2) + C(K3) ≥ 0` (or, in vol terms, the implied-vol curve does not violate static arbitrage). Generate random three-strike slices with Kotest `Arb`; assert the convexity property. Inputs that violate it are unlikely in practice — the test documents the invariant.
  - Acceptance: `./gradlew :volatility-service:test --tests "*VolSurfaceButterflyTest"`
- [ ] **4.3** Extract extrapolation coverage from `VolSurfaceInvariantsTest` into a dedicated `volatility-service/src/test/kotlin/com/kinetix/volatility/property/VolSurfaceExtrapolationTest.kt`. Exercise extreme out-of-grid strikes (1.0, 10_000.0) and maturities (1 day, 10 years) so failures point clearly at extrapolation logic. After extraction, leave the moved test removed from `VolSurfaceInvariantsTest` — no duplication.
  - Acceptance: `./gradlew :volatility-service:test --tests "*VolSurfaceExtrapolationTest" "*VolSurfaceInvariantsTest"`

---

### 5. test-support module expansion (Phase 4)

**Status:** Module exists at `test-support/`. Only `KafkaTestSetup` ported. Plan called for builders + Postgres + gRPC + demo migrations. Do NOT big-bang migrate every service — opportunistic migration is fine; the two demo migrations (5.8, 5.9) prove the pattern.

- [ ] **5.1** Add `test-support/src/main/kotlin/com/kinetix/testsupport/database/PostgresTestSetup.kt` — promote from the duplicates at `correlation-service/.../DatabaseTestSetup.kt`, `volatility-service/.../DatabaseTestSetup.kt`, `position-service/.../DatabaseTestSetup.kt`, etc. Must support per-service migration scripts via `migrationLocation: String = "db/migration"`.
  - Acceptance: `./gradlew :test-support:build :test-support:test`
- [ ] **5.2** Add `test-support/src/main/kotlin/com/kinetix/testsupport/grpc/InProcessGrpcServer.kt` — wraps `NettyServerBuilder.forPort(0)` + `ManagedChannelBuilder.usePlaintext()` per the convention in CLAUDE.md ("Acceptance tests use real infrastructure"). Provides `register(serviceImpl)` and `channel()`.
  - Acceptance: `./gradlew :test-support:build :test-support:test`
- [ ] **5.3** Add `test-support/src/main/kotlin/com/kinetix/testsupport/builders/TestTrade.kt` — chainable builder with sensible defaults: `TestTrade.aTrade().withSymbol("AAPL").withQuantity(100).build()`.
  - Acceptance: `./gradlew :test-support:build :test-support:test`
- [ ] **5.4** Add `test-support/src/main/kotlin/com/kinetix/testsupport/builders/TestPosition.kt`.
  - Acceptance: `./gradlew :test-support:build :test-support:test`
- [ ] **5.5** Add `test-support/src/main/kotlin/com/kinetix/testsupport/builders/TestCorrelationMatrix.kt` with `identity(n)` and `random(n, seed)` constructors.
  - Acceptance: `./gradlew :test-support:build :test-support:test`
- [ ] **5.6** Add `test-support/src/main/kotlin/com/kinetix/testsupport/builders/TestVolSurface.kt` with `flatAt(0.20)` and `withSmile(...)` constructors.
  - Acceptance: `./gradlew :test-support:build :test-support:test`
- [ ] **5.7** Add `test-support/src/main/kotlin/com/kinetix/testsupport/builders/TestPriceCurve.kt` with `constant(100.0)` and `linear(start, slope)` constructors.
  - Acceptance: `./gradlew :test-support:build :test-support:test`
- [ ] **5.8** Refactor `position-service/src/test/kotlin/.../KafkaTestSetup.kt` to delegate to `com.kinetix.testsupport.kafka.KafkaTestSetup` (one-line delegation), or delete it and update imports.
  - Acceptance: `./gradlew :position-service:test :position-service:integrationTest`
- [ ] **5.9** Refactor `audit-service/src/test/kotlin/.../KafkaTestSetup.kt` the same way.
  - Acceptance: `./gradlew :audit-service:test :audit-service:integrationTest`

---

### 6. Kotlin PIT mutation testing (Phase 5)

**Status:** Deferred. `info.solidsoft.gradle.pitest:gradle-pitest-plugin` versions 1.15 and earlier reference `reporting.baseDir` which Gradle 9 removed. The reason is documented in `.github/workflows/mutation.yml`. Per the decision above we implement the CLI-via-exec workaround using `org.pitest:pitest-command-line:1.17.x` — invoked from an `exec` task in a `kinetix.kotlin-mutation.gradle.kts` convention plugin that emits HTML + XML under `build/reports/pitest/`.

- [ ] **6.1** Create `build-logic/convention/src/main/kotlin/kinetix.kotlin-mutation.gradle.kts` convention plugin. Register a `pitest` `JavaExec` task per consuming module that:
  - Builds the test runtime classpath from the module's source sets.
  - Invokes `org.pitest:pitest-command-line:1.17.x` with `--targetClasses`, `--targetTests`, `--reportDir build/reports/pitest`.
  - Emits HTML and XML.

  Apply the plugin to `risk-orchestrator/build.gradle.kts` as the first consumer.
  - Acceptance: `./gradlew :risk-orchestrator:pitest && test -f risk-orchestrator/build/reports/pitest/mutations.xml`
- [ ] **6.2** Apply `kinetix.kotlin-mutation` to `position-service/build.gradle.kts`.
  - Acceptance: `./gradlew :position-service:pitest && test -f position-service/build/reports/pitest/mutations.xml`
- [ ] **6.3** Apply `kinetix.kotlin-mutation` to `correlation-service/build.gradle.kts`.
  - Acceptance: `./gradlew :correlation-service:pitest && test -f correlation-service/build/reports/pitest/mutations.xml`
- [ ] **6.4** Apply `kinetix.kotlin-mutation` to `volatility-service/build.gradle.kts`.
  - Acceptance: `./gradlew :volatility-service:pitest && test -f volatility-service/build/reports/pitest/mutations.xml`
- [ ] **6.5** Apply `kinetix.kotlin-mutation` to `regulatory-service/build.gradle.kts`.
  - Acceptance: `./gradlew :regulatory-service:pitest && test -f regulatory-service/build/reports/pitest/mutations.xml`
- [ ] **6.6** Apply `kinetix.kotlin-mutation` to `audit-service/build.gradle.kts`.
  - Acceptance: `./gradlew :audit-service:pitest && test -f audit-service/build/reports/pitest/mutations.xml`
- [ ] **6.7** Add a `kotlin-mutation` job to `.github/workflows/mutation.yml` that runs `./gradlew pitest` across the six modules on the weekly Sunday cadence and uploads `pit-report-<module>` artifacts. (Approval for this CI edit is granted under "CI/CD approval" above.)
  - Acceptance: `python3 -c "import yaml; data=yaml.safe_load(open('.github/workflows/mutation.yml')); assert 'kotlin-mutation' in data['jobs'], 'kotlin-mutation job missing'"`

---

### 7. Coverage ratchet — flip from warning to hard fail (Phase 6)

**Status:** `scripts/check-coverage-ratchet.py` exists and works. CI step at `.github/workflows/ci.yml` line 606 runs it but uses `|| echo "::warning::..."` so it only emits a workflow warning. The plan called for hard fail with 0.5pp tolerance. `coverage-baselines.json` has `"kotlin": {}` — needs populating.

- [ ] **7.1** Run kover locally for every Kotlin module that publishes coverage (`./gradlew koverXmlReport`), parse the resulting per-module line-coverage percentages, and populate `coverage-baselines.json`'s `"kotlin"` map with the observed values rounded **down** by 0.5pp (provides immediate headroom). Modules to include: `common`, `position-service`, `price-service`, `rates-service`, `volatility-service`, `correlation-service`, `reference-data-service`, `risk-orchestrator`, `regulatory-service`, `notification-service`, `audit-service`, `gateway`. Skip modules where coverage isn't measured (e.g. `proto`).
  - Acceptance: `python3 scripts/check-coverage-ratchet.py` exits 0 against the populated baselines.
- [ ] **7.2** Remove `|| echo "::warning::Coverage ratchet breached"` from `.github/workflows/ci.yml` line 606 — let a non-zero exit fail the job. (Approval for this CI edit is granted under "CI/CD approval" above.)
  - Acceptance: `! grep -F '|| echo "::warning::Coverage ratchet' .github/workflows/ci.yml`
- [ ] **7.3** Document baseline raise/lower procedure in `docs/testing.md`: how to legitimately lower a baseline (deliberate behavioural change, document why in the PR description), how to raise one (after a verified improvement), and the policy that lowering requires explicit reviewer sign-off.
  - Acceptance: `grep -E "lower.*baseline|raise.*baseline" docs/testing.md`

---

### 8. Test-naming linter — false-positive cleanup (Phase 6)

**Status:** `scripts/check-test-naming.py` exists. Currently produces false positives because `IMPL_STYLE_KOTEST` matches any `test("...")` block, including parameterized tests that pass currency codes (`'USD'`, `'GBP'`) or instrument IDs (`'USD-TREASURY'`) as inline test data.

- [ ] **8.1** Tighten the `IMPL_STYLE_KOTEST` regex in `scripts/check-test-naming.py` to match only the Kotest *test description* form: `test("description here") {` followed by a block body. Right now it matches any `test("...")` which is too broad.
  - Acceptance: `python3 scripts/check-test-naming.py` reports fewer hits than before AND fails the build with non-zero exit on a fixture file containing `test("foo") {}` (add a small inline self-test or run the script against a tmp file with one impl-style and one descriptive test).
- [ ] **8.2** Add a small allow-list in `scripts/check-test-naming.py` of intentional short identifiers (currency codes ISO-4217 majors, instrument types like `BOND`, `EQUITY`, `FX`, `IRS`) that legitimately appear at the start of test descriptions.
  - Acceptance: `python3 scripts/check-test-naming.py` reports zero false positives on the current codebase.
- [ ] **8.3** Split the "implementation-flavoured" heuristic in `scripts/check-test-naming.py` so it applies separately to `@Test fun ...` (JUnit-style, camelCase functions are idiomatic — be lenient) versus Kotest `test("...")` (descriptive strings expected — be strict). Document the split in a comment at the top of the script.
  - Acceptance: `python3 scripts/check-test-naming.py` exits 0 on the current codebase.

**Out of scope for this follow-up** — promote `scripts/check-test-naming.py` from warning to hard fail in CI. Target Sept 2026 per the original plan's "soft warning initially, hard fail after one quarter" cadence; track separately when the time comes.

---

## Verification (after all checkboxes ticked)

- `./gradlew build` succeeds.
- `./gradlew :notification-service:integrationTest` runs the four new tests and they pass.
- `cd risk-engine && uv run pytest -m unit` includes the new invariant files and all 800+ tests still pass at ≥ 83 % coverage.
- `cd ui && npm run test:coverage` still at ≥ 80 % lines.
- `python3 scripts/check-coverage-ratchet.py` returns 0 on a clean run; deliberately introducing a coverage drop fails CI.
- `python3 scripts/check-test-naming.py` produces zero false positives on the current codebase.
- `.github/workflows/mutation.yml` weekly Sunday run produces `mutmut-report`, `stryker-report`, and `pit-report-<module>` artifacts.

## When this is done

The Kinetix test suite will be measurably exemplary:
- Coverage measured everywhere, regression-locked by ratchet.
- Property-based tests on every mathematically critical path.
- Mutation testing on every stack (weekly dashboard).
- Shared test infra removes per-service duplication.
- Test naming enforced by tooling.
- Single source of truth philosophy doc in `docs/testing.md`.

That is something worth being proud of.

# Testing in Kinetix

> Kinetix has a deliberate, multi-layered test philosophy. New code is expected to follow it; review will push back when it doesn't.

## The pyramid we maintain

```
                    ┌────────────────┐
                    │  Smoke / E2E   │   ~10 scenarios — happy path of every flow
                    └────────────────┘
                  ┌────────────────────┐
                  │  Integration tests │   Per-service, real Postgres + Kafka via Testcontainers
                  └────────────────────┘
              ┌────────────────────────────┐
              │   Acceptance tests          │   Per-service, real infra, no mocks of internal collaborators
              └────────────────────────────┘
        ┌────────────────────────────────────────┐
        │  Unit tests + property-based tests       │   Fast, in-process, deterministic
        └────────────────────────────────────────┘
```

As of May 2026 the pyramid is healthy across the Kotlin services (62/22/12/2 % split unit/acceptance/integration/e2e). The Python risk engine has ~912 unit tests plus 73 integration tests. The UI has 2,089 Vitest unit tests plus 580 Playwright scenarios.

## What goes where

**Unit tests** — fast, in-process, no external dependencies. One concept per test. Naming reads as a specification:

```kotlin
test("rejects a trade when the position limit is exceeded") { ... }
```

Use Kotest `FunSpec` with `shouldBe`/`shouldThrow` (Kotlin), `pytest` with descriptive `def test_` names (Python), Vitest `it(...)` (UI).

**Property-based tests** — for code with mathematical invariants. Verify properties that hold for an entire class of inputs rather than picking examples. See `common/.../VolSurfacePropertyTest.kt` and `risk-engine/tests/test_black_scholes_invariants.py` for canonical examples. Use Kotest's `Arb`/`forAll`/`checkAll` for Kotlin (the property module is wired into every Kotlin test classpath) and hypothesis for Python.

**Acceptance tests** (Kotlin only, suffix `*AcceptanceTest`) — contract and behaviour tests within a service module. Use Testcontainers for Postgres and Kafka; never use H2, embedded Kafka, or mocked internal collaborators (a Gradle task enforces this). gRPC dependencies on other Kinetix services are faked with in-JVM Netty servers (`NettyServerBuilder.forPort(0)`) so calls still travel over real HTTP/2.

**Integration tests** (Kotlin suffix `*IntegrationTest`, Python `@pytest.mark.integration`) — tests that exercise an infrastructure boundary — Kafka consumer round-trips, repository against a real database, gRPC server lifecycle.

**End-to-end tests** (`end2end-tests/*End2EndTest.kt`) — cross-service flows: place a trade → see a position → see a risk result → see a regulatory submission. Run via `./gradlew :end2end-tests:end2EndTest`.

**Browser tests** (`ui/e2e/*.spec.ts`) — Playwright scenarios verifying user-visible behaviour: empty states, data rendering, interactions, validation, error paths. Mock API routes via the patterns in `ui/e2e/fixtures.ts`. Every new tab, panel, dialog, or interactive workflow gets a Playwright spec — unit tests alone are not sufficient.

## TDD and BDD

- Write a failing test first. Watch it fail for the right reason. Then write the minimal production code to make it pass. Then refactor.
- Test behaviour, not implementation. Don't couple to internal collaborators that may change.
- Bug fixes need a reproducing test *before* the fix. Refactors must not reduce coverage.

## Coverage

Coverage measurement is live across all stacks:

| Stack | Tool | Command | Output |
|-------|------|---------|--------|
| Kotlin | Kover | `./gradlew :MODULE:koverHtmlReport` | `MODULE/build/reports/kover/html/` |
| Python | pytest-cov | `cd risk-engine && uv run pytest` | `risk-engine/reports/htmlcov/` |
| UI | @vitest/coverage-v8 | `cd ui && npm run test:coverage` | `ui/coverage/` |

CI uploads all three as artifacts on every PR. **Coverage is measured, not gated** — but tests *should* exercise the code they're meant to verify. A new feature with zero coverage will be flagged in review.

Ratchet enforcement (fail CI on coverage drop > 0.5%) is the Phase 6 sustain mechanism — see `scripts/check-coverage-ratchet.py` and the post-test CI step.

## Property-based testing

Use when:
- The function has a clear mathematical invariant (monotonicity, symmetry, conservation, identity, idempotence).
- The output space is large enough that example-based tests inevitably miss cases.
- The cost of a wrong answer is high (pricing, VaR, capital).

**Good** examples to model on:
- `risk-engine/tests/test_black_scholes_invariants.py` — put-call parity, delta bounds, monotonicity in spot/strike/vol.
- `common/src/test/kotlin/com/kinetix/common/model/VolSurfacePropertyTest.kt` — interpolated vol within knot bounds.
- `correlation-service/.../CorrelationMatrixHashPropertyTest.kt` — hash permutation-invariance.

Always set a deterministic seed (`PropertyTesting.defaultSeed` in Kotlin; hypothesis defaults to seed by name in CI). Failures must be reproducible.

## Mutation testing

Runs weekly via `.github/workflows/mutation.yml`, not per-PR. Treat the report as a quality dashboard. If a test class has a low mutation score, it likely passes without verifying behaviour — strengthen the assertions or delete the test.

- **Python**: `cd risk-engine && uv run mutmut run` (see `[tool.mutmut]` in `pyproject.toml`).
- **UI**: `cd ui && npm run test:mutation` (see `stryker.config.mjs`).
- **Kotlin (PIT)**: currently deferred — the published gradle-pitest-plugin versions are incompatible with Gradle 9. Will be added when a compatible plugin version ships.

## Shared test infrastructure

`test-support/` is the canonical place for Testcontainers wrappers, builders, and other test plumbing shared across services. Currently houses `KafkaTestSetup`; migrate per-service duplicates here opportunistically when their tests are next touched.

## Guardrails (from CLAUDE.md, repeated for emphasis)

- **Never delete, disable, or skip a test** without explicit approval. `@Ignore`, `@Disabled`, `pytest.skip`, `xfail`, `.todo`, `test.skip` are all forbidden as a way to make a build pass.
- **Acceptance tests use real infrastructure** — never mocks, in-memory fakes, H2, or embedded Kafka. The `verifyAcceptanceTestCompliance` task enforces this.
- **Run linting before pushing UI changes**: `cd ui && npm run lint`.
- **Run the full suite for every affected module after each change** — tests must be fast, independent, self-contained.

## Quick reference

```bash
# Kotlin
./gradlew test                                       # unit tests only
./gradlew acceptanceTest                             # acceptance tests
./gradlew integrationTest                            # integration tests
./gradlew :end2end-tests:end2EndTest                 # full end-to-end
./gradlew :MODULE:koverHtmlReport                    # coverage report

# Python
cd risk-engine && uv run pytest                      # all tests (with coverage)
cd risk-engine && uv run pytest -m unit              # unit only
cd risk-engine && uv run pytest -m integration       # integration only
cd risk-engine && uv run mutmut run                  # mutation testing

# UI
cd ui && npm run test                                # Vitest unit tests
cd ui && npm run test:coverage                       # Vitest with coverage
cd ui && npm run playwright                          # Playwright browser tests
cd ui && npm run test:mutation                       # Stryker mutation testing
cd ui && npm run lint                                # ESLint
```

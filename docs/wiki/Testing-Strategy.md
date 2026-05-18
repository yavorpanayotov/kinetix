# Testing Strategy

Kinetix follows **strict TDD** ([CLAUDE.md](https://github.com/panayotovk/kinetix/blob/main/CLAUDE.md) — Testing Philosophy). Tests are written first, behaviour-focused (not implementation-focused), and named like specifications.

**915 tests** across the stack:

| Suite | Count | Tool |
|---|---|---|
| Kotlin | 561 | Kotest (FunSpec) + MockK + Testcontainers |
| Python (risk engine) | 79 | pytest + Hypothesis |
| UI unit | 191 | Vitest |
| UI browser | 84 | Playwright |
| **Total** | **915** | |

Plus mutation testing (Stryker for UI, mutmut for Python), property-based tests (Hypothesis), Gatling load tests, and a coverage ratchet gating merges.

## Test pyramid

```
                  ┌────────────────────┐
                  │   End-to-End (E2E) │   Few. Across-service flows.
                  └────────────────────┘
              ┌──────────────────────────┐
              │     Acceptance / E2E     │   Each service's contract.
              │   (Testcontainers, gRPC) │   Real Postgres, real Kafka.
              └──────────────────────────┘
        ┌──────────────────────────────────────┐
        │           Integration tests          │   Infrastructure boundaries.
        └──────────────────────────────────────┘
   ┌────────────────────────────────────────────────┐
   │                   Unit tests                   │   Fast. Behavioural.
   └────────────────────────────────────────────────┘
```

## Layers

### Unit tests

- Kotlin: `*Test.kt` files run via `./gradlew test`
- Python: `tests/test_*.py` with `@pytest.mark.unit`
- UI: `*.test.tsx` / `*.test.ts` via Vitest

Unit tests are **fast** (single-digit milliseconds typical), have **no I/O**, and target a single class or function. MockK is used sparingly — for collaborators across boundaries (repositories, clients, publishers). Avoid mocking internal language constructs.

### Acceptance tests — `*AcceptanceTest`

```bash
./gradlew acceptanceTest
```

Acceptance tests live alongside the service they test. They exercise the **service contract** — REST routes, Kafka publishers, gRPC servers — against **real infrastructure** ([CLAUDE.md](https://github.com/panayotovk/kinetix/blob/main/CLAUDE.md)):

- **Real Postgres** via Testcontainers (no H2, no in-memory)
- **Real Kafka** via Testcontainers (no embedded Kafka)
- **Real gRPC dependencies** stubbed by binding a fake `XxxServiceImplBase` to an **in-JVM Netty gRPC server** on `NettyServerBuilder.forPort(0)` and pointing the client under test at it with `ManagedChannelBuilder…usePlaintext()`. Calls travel over real HTTP/2 — interceptors, serialisation, channel wiring all exercised.

The goal is **high-fidelity wire signal**. Mocking transport defeats the purpose.

### Integration tests — `*IntegrationTest`

```bash
./gradlew integrationTest
```

Focus on a specific infrastructure boundary: a repository against a real database, a Kafka consumer against a real broker, a gRPC client against a real server.

### End-to-end tests — `*End2EndTest`

```bash
./gradlew :end2end-tests:end2EndTest
```

Cross-service tests against a running stack. Exercise UI → gateway → backend service → Kafka → downstream service → audit flows.

### UI Vitest

```bash
cd ui && npm run test
```

Component-level tests against the rendered DOM. Mocking restricted to API clients.

### UI Playwright

```bash
cd ui && npx playwright test
cd ui && npx playwright test --ui   # interactive mode
```

Browser-driven E2E. **Required for every new tab, panel, dialog, or interactive workflow** ([CLAUDE.md](https://github.com/panayotovk/kinetix/blob/main/CLAUDE.md)).

API routes mocked via fixtures in `ui/e2e/fixtures.ts`. Tests target user-visible behaviour — empty states, data rendering, user interactions, validation, error paths.

## Property-based testing (Python)

Risk-engine modules where the maths admits invariants are property-tested with Hypothesis. Examples:

- **VaR aggregation commutativity** — VaR(A ∪ B) under correlation matrices is symmetric in A and B
- **Greeks consistency** — finite-difference Greeks converge to analytical Greeks as `h → 0`
- **Bond pricing monotonicity** — PV monotone in yield
- **FRTB bucket reordering** — SBM aggregate invariant under bucket index permutation

## Mutation testing

- **UI:** Stryker mutates TypeScript and re-runs Vitest. Surviving mutants flag weak assertions.
- **Python:** mutmut over `risk-engine/src/`. Same idea.

Mutation testing is run periodically (not on every push) and gates new modules that exceed a survival-rate threshold.

## Property + example combined

Pattern in `risk-engine/tests/`:

```python
@pytest.mark.unit
def test_european_call_known_value():
    # Worked example from Hull
    assert black_scholes_call(...) == pytest.approx(2.4226, rel=1e-4)

@pytest.mark.unit
@given(s=positive_floats, k=positive_floats, r=...)
def test_put_call_parity(s, k, r, sigma, t):
    assume(t > 0 and sigma > 0)
    call = black_scholes_call(s, k, r, sigma, t)
    put  = black_scholes_put(s, k, r, sigma, t)
    parity = call - put - (s - k * exp(-r * t))
    assert abs(parity) < 1e-9
```

Worked examples lock the absolute output; property tests guard the algebraic structure.

## Load tests — Gatling

Path: [`load-tests/`](https://github.com/panayotovk/kinetix/tree/main/load-tests)

```bash
./gradlew :load-tests:gatlingRun
```

Scenarios include:

- 50K-row blotter under realistic filter and sort load
- `fix-gateway` throughput at sustained order rate
- Demo-reset latency p95 < 5 minutes

## Schema compatibility tests

Path: [`schema-tests/`](https://github.com/panayotovk/kinetix/tree/main/schema-tests)

Ensures Kafka event schema changes are backward-compatible. New consumer code must still deserialise pre-change payloads; new producer code must still produce payloads old consumers can read.

## Smoke tests

Path: [`smoke-tests/`](https://github.com/panayotovk/kinetix/tree/main/smoke-tests)

Post-deploy checks that the platform actually works end-to-end. Run via `/health` and as the final step of `/deploy`.

## Coverage ratchet

CI tracks coverage per module and gates merges that **reduce** coverage. New code is expected to come with tests covering it. Targets per module are persisted in `coverage-baselines.json`.

## CI execution

GitHub Actions runs jobs in parallel per push:

- Kotlin unit + acceptance + integration per module (matrix)
- Risk-engine unit + integration
- UI Vitest + Playwright
- Schema compatibility
- Gatling smoke (subset)
- ESLint, mypy, Detekt

Failure in any job blocks merge.

## Linting

```bash
cd ui && npm run lint          # ESLint
./gradlew detekt               # Detekt
cd risk-engine && uv run mypy  # mypy
```

ESLint catches errors that unit tests do not (e.g. `react-hooks/set-state-in-effect`). **Always run `cd ui && npm run lint` before pushing UI changes** ([CLAUDE.md](https://github.com/panayotovk/kinetix/blob/main/CLAUDE.md)).

## Guardrails

From [CLAUDE.md](https://github.com/panayotovk/kinetix/blob/main/CLAUDE.md):

- **Never delete, disable, or skip a test** without explicit permission. This includes `@Ignore`, `@Disabled`, `pytest.mark.skip`, `xfail`, `test.skip`, `.todo`. If a test is failing, fix the code under test or fix the test.
- **Bug fixes need a reproducing test before the fix.**
- **Refactors must not reduce coverage.**

## Naming conventions

Test names read as specifications:

```kotlin
test("rejects a trade when the position limit is exceeded") { ... }
test("publishes a limits.breaches event when utilisation crosses 100%") { ... }
test("promotes an EOD run only after all positions have been valued") { ... }
```

Not `testTradeLimit` or `testBreach`.

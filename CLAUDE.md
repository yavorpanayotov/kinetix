# CLAUDE.md

Kinetix is a multi-service financial risk management platform: Kotlin/Ktor microservices, a Python risk engine, and a React/TypeScript UI.

## Project Structure

```
kinetix/
  common/                  # Shared Kotlin library (DTOs, Kafka, HTTP utils)
  proto/                   # Protobuf definitions (gRPC contracts)
  gateway/                 # API gateway — aggregates backend services for the UI
  position-service/        # Trade booking, position management, P&L, limits
  price-service/           # Market data ingestion and price history
  rates-service/           # Interest rate curves
  volatility-service/      # Volatility surfaces
  correlation-service/     # Correlation matrices
  reference-data-service/  # Instrument and counterparty reference data
  risk-engine/             # Python — VaR, Greeks, Monte Carlo, stress testing (gRPC)
  risk-orchestrator/       # Orchestrates risk calculations across services
  regulatory-service/      # Model governance, backtesting, submissions
  notification-service/    # WebSocket push to the UI
  audit-service/           # Hash-chained audit trail
  ui/                      # React + TypeScript + Vite frontend
  end2end-tests/           # Kotlin E2E tests against running services
  schema-tests/            # Kafka event schema compatibility tests
  deploy/                  # Helm charts, infrastructure config
```

## Build & Run

```bash
# Kotlin services
./gradlew build                              # Build all
./gradlew test                               # Unit tests only
./gradlew acceptanceTest                     # Acceptance tests (*AcceptanceTest)
./gradlew integrationTest                    # Integration tests (*IntegrationTest)
./gradlew :end2end-tests:end2EndTest         # End-to-end tests (*End2EndTest)

# Run a single test in a single module
./gradlew :position-service:test --tests "com.kinetix.position.FooTest"
./gradlew :gateway:acceptanceTest --tests "*GatewayRoutesAcceptanceTest"

# Risk engine (Python)
cd risk-engine && uv run pytest              # All tests
cd risk-engine && uv run pytest -m unit      # Unit only
cd risk-engine && uv run pytest -m integration  # Integration only
cd risk-engine && uv run pytest tests/test_greeks.py::test_delta  # Single test

# UI
cd ui && npm run dev                         # Dev server
cd ui && npm run test                        # Vitest unit tests
cd ui && npx playwright test                 # Playwright browser tests
cd ui && npx playwright test --ui            # Playwright UI mode
```

## Local Dev

- **Bring the platform up:** `./deploy/redeploy.sh` — rebuilds Kotlin images, starts infra (postgres, redis, kafka) and all services. Compose files live in `deploy/` and `deploy/infra/`.
- **Local URLs:** UI `https://kinetixrisk.ai`, Gateway `https://api.kinetixrisk.ai`, Grafana `https://grafana.kinetixrisk.ai`.
- **Observability stack:** Prometheus + Loki + Tempo (see ADR-0008). Configs in `deploy/observability/`.
- **Useful slash commands:** `/health` (service health check), `/incident` (triage), `/deploy` (full redeploy), `/demo` (seed demo data).

## Testing Philosophy

Follow TDD (Test-Driven Development) and BDD (Behaviour-Driven Development) practices:

- **Write tests first.** Before implementing a feature or fixing a bug, write a failing test that describes the expected behaviour. Then write the minimal code to make it pass.
- **Red-Green-Refactor.** Start with a failing test (red), make it pass (green), then refactor while keeping tests green.
- **Test behaviour, not implementation.** Tests should describe *what* the system does, not *how* it does it. Avoid coupling tests to internal details that may change.
- **Name tests descriptively.** Test names should read as specifications — e.g. `"rejects a trade when the position limit is exceeded"` rather than `"testTradeLimit"`.
- **Cover every applicable level.** Backend changes need unit tests (Kotest/pytest), acceptance tests (`*AcceptanceTest`), and integration tests where infrastructure boundaries are involved. UI changes need Vitest unit tests *and* Playwright E2E tests in `ui/e2e/` — every new tab, panel, dialog, or interactive workflow. Unit tests alone are never sufficient; higher-level tests prove the feature works as a user or consumer would experience it.
- **Run the full suite for every affected module after each change.** Tests must be fast, independent, and self-contained — no execution-order dependencies.
- **Run linting before pushing UI changes.** Always run `cd ui && npm run lint` before committing UI code. ESLint catches errors (e.g. `react-hooks/set-state-in-effect`) that unit tests do not.
- **Bug fixes need a reproducing test before the fix.** Refactors must not reduce coverage. If you change behaviour that existing tests cover, update those tests — do not leave them failing.

## Project Conventions

- **Kotlin tests** use Kotest `FunSpec` with `shouldBe` / `shouldThrow` matchers and MockK for mocking.
- **Python tests** use pytest with `@pytest.mark.unit` / `@pytest.mark.integration` / `@pytest.mark.performance` markers.
- **UI unit tests** use Vitest.
- **UI browser tests** use Playwright and live in `ui/e2e/`. Mock API routes using the patterns in `ui/e2e/fixtures.ts` and test user-visible behaviour: empty states, data rendering, user interactions, validation, and error paths.
- **Acceptance tests** are named `*AcceptanceTest` and run via `./gradlew acceptanceTest`. These are contract and behaviour tests that live in each service module.
- **Acceptance tests use real infrastructure — never mocks, in-memory fakes, H2, or embedded Kafka.** Postgres and Kafka run via Testcontainers. gRPC dependencies on *other* Kinetix services are stubbed by binding a fake `XxxServiceImplBase` to an in-JVM Netty gRPC server on a random localhost port (`NettyServerBuilder.forPort(0)`) and pointing the client under test at it with `ManagedChannelBuilder…usePlaintext()`, so calls still travel over real HTTP/2 — interceptors, serialization, and channel wiring all exercised. The goal is high-fidelity wire signal; mocking transport defeats the purpose.
- **Integration tests** are named `*IntegrationTest` and run via `./gradlew integrationTest`.
- **End-to-end tests** are named `*End2EndTest` and run via `./gradlew :end2end-tests:end2EndTest`.
- Regular `./gradlew test` excludes acceptance, integration, and end-to-end tests.

## Design Principles

- **Service / repository / mapper roles stay separate.** A service orchestrates; a repository persists; a mapper converts. Don't blend them.
- **Depend on abstractions at module boundaries.** Repositories, clients, publishers, and gRPC stubs are interfaces — concrete implementations swap behind them so tests can substitute fakes.
- **Introduce a new collaborator before growing an existing class.** When a new responsibility appears, a new file is almost always the right answer.

## Code Organisation

### Kotlin services

- **One type per file.** Data classes, enums, sealed classes, and interfaces should each live in their own file rather than being inlined in the implementation class that uses them. This keeps files focused and easy to navigate.
- **DTOs live in a `dtos` sub-package, one per file.** For example, route DTOs go in `routes/dtos/VaRResultResponse.kt`, not grouped in a single `RiskDtos.kt`. Each file contains exactly one `@Serializable` data class. The same applies to events and domain types — e.g. `PriceEvent` in `PriceEvent.kt`, not inside `KafkaPricePublisher.kt`.
- **Keep implementation files focused on behaviour.** A service or route file should contain the logic, not a mix of logic and type definitions.

### Python risk engine

- Source lives in `risk-engine/src/kinetix_risk/`. Tests live in `risk-engine/tests/`.
- Modules are flat files (e.g. `greeks.py`, `monte_carlo.py`, `valuation.py`) — no deep package nesting.
- Use dataclasses or Pydantic models for structured data. Keep gRPC server wiring (`server.py`) separate from calculation logic.

## Architectural Decisions

- **30 ADRs are recorded in [`docs/adr/`](docs/adr/README.md).** Consult them before changes that overlap. Notable ones to know about:
  - ADR-0001 monorepo structure
  - ADR-0004 Kafka for async messaging
  - ADR-0008 observability stack (Prometheus + Loki + Tempo)
  - ADR-0013 Keycloak for authentication
  - ADR-0018 run reproducibility via manifests
  - ADR-0021 risk orchestration architecture
- **Ask before changing architecture.** Before introducing a new service, module, library, messaging topic, database table, or API contract — or before significantly restructuring existing ones — explain the trade-offs and get my approval.
- **Act autonomously within existing boundaries.** Adding a class/file within an existing service, writing tests, refactoring internals, or adding a route to an existing API — follow established patterns without asking.

## Guardrails

- **Never delete, disable, or skip a test** (test file, test function, or test assertion) without my explicit permission. This includes marking tests as ignored, disabled, skipped, or xfail (e.g. `@Ignore`, `@Disabled`, `xconfig`, `pytest.mark.skip`, `test.skip`, `.todo`). If a test is failing, fix the code under test or fix the test — do not delete, skip, or suppress it to make the build pass. Always explain the failure and ask before removing or disabling any test.
- **Never force-push or rewrite published git history** without my explicit permission.
- **Never modify CI/CD pipeline files** without my approval.
- **Never add a new library/dependency** without my approval.
- **Never skip pre-commit hooks** (no `--no-verify`).
- **When stuck, explain the problem** and your proposed fix before silently retrying or working around it.

## Known Gotchas

- **Testcontainers in `common` module** — Docker connectivity fails because `common` is a library module missing Docker client deps on its classpath. Place integration tests in service modules instead.
- **Exposed + Kotest `shouldThrow`** — Exceptions thrown inside `newSuspendedTransaction` cannot be caught by `shouldThrow`. Workaround: move validation before the `transactional.run{}` block.
- **Risk engine PYTHONPATH** — The Dockerfile needs `PYTHONPATH=/app/src` because `uv sync` doesn't install the project in editable mode.
- **Flyway migrations run inside a transaction** — never use `CREATE INDEX CONCURRENTLY` or other transaction-incompatible statements in a migration. Review new migrations before committing.

## Commit Practices

- **Commit frequently during implementation.** After completing each logical, working unit of change, create a commit. Do not wait until the entire task is finished to commit.
- **Each commit should be self-contained.** The codebase must build and tests must pass after every commit. Never commit half-finished work that breaks the build.
- **Don't batch unrelated changes.** Keep commits focused — one concern per commit. If a plan involves multiple steps, each step should typically be its own commit.

## Plans

Multi-step plans live in `plans/` and should be loop-ready so they can be advanced autonomously by `/work-plan` (usually wrapped in `/loop`). When you author or substantially edit a plan:

- **Use literal `- [ ]` markdown checkboxes for every executable unit.** `/work-plan` advances exactly one checkbox per iteration; sections without checkboxes are inert prose. One checkbox = one independently committable change.
- **Order checkboxes top-to-bottom by dependency.** The first unchecked box is what runs next. Don't bury blocking work later in the file.
- **Pair every checkbox with an acceptance command.** Put `Acceptance: ./gradlew …` (or `cd risk-engine && uv run pytest …`, `python3 scripts/…`, etc.) on the line directly under the checkbox. `/work-plan` re-runs it independently before ticking the box — if you can't name a command, the unit isn't loop-ready.
- **Resolve decisions up front in a "Decisions applied" section at the top.** Don't leave open questions inside checkbox bodies. If a decision is genuinely user-blocking, state the default, mark it overridable, and proceed — never bury an unanswered question in the middle of executable work.
- **Pre-approve guardrail edits at the top of the plan.** If any checkbox touches a CI/CD file, adds a dependency, or otherwise crosses a `## Guardrails` line, grant the approval in a "CI/CD approval" (or equivalent) section near the top so subagents don't stop mid-loop.
- **Don't checkbox out-of-scope follow-ups.** Items deferred to a future quarter, blocked on external signals, or explicitly out of scope go in prose under an "Out of scope" callout, not in a checkbox — otherwise the loop will try to do them.

### Running `/loop /work-plan` (dynamic mode)

When invoked as `/loop /work-plan <plan>` with no explicit interval, the runtime expects me to schedule the next iteration via `ScheduleWakeup` between iterations. To keep the plan advancing without idle time:

- **Always re-schedule at the minimum interval — `delaySeconds: 60`.** The runtime clamps to `[60, 3600]`; 60 is the floor. There is nothing external to wait for between checkboxes — the next unit of work is ready immediately — so never pick a larger delay "to be safe."
- **Never end the loop by omitting `ScheduleWakeup` unless the plan has no remaining unchecked boxes.** If a checkbox failed or a guardrail blocks progress, surface the problem in the response and still schedule the next tick at 60s so the user sees the loop is alive and can intervene.
- **Don't ask clarifying questions mid-loop.** Plans should resolve decisions up front (see the "Decisions applied" rule above); if something is genuinely ambiguous, pick the default documented in the plan, note the choice, and keep looping.
- **Pass the original `/loop /work-plan <plan>` invocation back as the `prompt` argument verbatim** so the next firing re-enters `/work-plan` against the same plan file.

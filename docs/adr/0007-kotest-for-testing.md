# ADR-0007: Use Kotest Over JUnit 5 for Kotlin Testing

## Status
Accepted

## Context
TDD is a first-class requirement. We need a testing framework for Kotlin that supports unit, integration, acceptance, and property-based testing with minimal boilerplate. Options: JUnit 5, Kotest, Spek.

## Decision
Use Kotest 5.9 as the primary testing framework for all Kotlin modules. Use MockK for mocking. Use Testcontainers for integration tests.

## Applies when
- Writing or modifying any Kotlin test (unit, acceptance, integration, end-to-end).
- Tempted to import `org.junit.jupiter.api.*`, `org.mockito.*`, `org.hamcrest.*` — that's the signal to read this ADR.
- Naming a new test class — the suffix decides which Gradle task runs it.

## Rules
- **DO** use `FunSpec` for unit tests, `BehaviorSpec` (Given/When/Then) for acceptance tests.
- **DO** use Kotest matchers: `shouldBe`, `shouldNotBe`, `shouldContain`, `shouldThrow<T>`, `eventually { … }`.
- **DO** use MockK (`mockk<T>()`, `every { … } returns …`, `coEvery`, `verify`) for mocking — never Mockito.
- **DO** suffix tests by intent: `*Test` → `./gradlew test`; `*AcceptanceTest` → `acceptanceTest`; `*IntegrationTest` → `integrationTest`; `*End2EndTest` → end-to-end run.
- **DO** name test functions as specifications: `"rejects a trade when the position limit is exceeded"` — not `testTradeLimit`.
- **DO** use Testcontainers for Postgres and Kafka in acceptance/integration tests. Stub other Kinetix services via in-JVM Netty gRPC servers (see CLAUDE.md "Acceptance tests").
- **DON'T** import `org.junit.jupiter.api.*`, Mockito, or Hamcrest. They are not on the classpath by intent.
- **DON'T** use H2, embedded Kafka, or in-memory fakes in acceptance tests.
- **DON'T** mark a test `@Ignore`, `@Disabled`, or otherwise skip it without explicit user permission (CLAUDE.md guardrail).
- **WORKAROUND**: `shouldThrow` cannot catch exceptions thrown inside `newSuspendedTransaction`. Validate before the transaction block.

## Consequences

### Positive
- Kotlin-native: DSL-based test definitions, no annotations
- Multiple spec styles: `FunSpec` for unit tests, `BehaviorSpec` (Given/When/Then) for acceptance tests — avoids needing Cucumber
- Built-in property-based testing (`kotest-property`) for numerical edge cases (critical in financial calculations)
- First-class coroutine support — tests can use `suspend` functions naturally
- Rich assertion library (`shouldBe`, `shouldContain`, `eventually` for async assertions)
- Testcontainers extension (`kotest-extensions-testcontainers`) for lifecycle management

### Negative
- Less familiar to developers coming from Java/JUnit
- IDE support is good but not as mature as JUnit's (Kotest IntelliJ plugin required)
- Some third-party libraries assume JUnit — occasional adapter friction

### Test Organization
- `*Test` suffix for unit tests → run by `./gradlew test`
- `*AcceptanceTest` suffix → run by `./gradlew acceptanceTest`
- `*IntegrationTest` suffix → run by `./gradlew integrationTest`
- `*End2EndTest` suffix → run by `./gradlew end2EndTest`
- Separation enforced by Gradle task filters in `kinetix.kotlin-testing.gradle.kts`

### Alternatives Considered
- **JUnit 5**: Industry standard, excellent IDE support, but verbose in Kotlin. No built-in BDD specs — would need Cucumber (extra `.feature` file layer). No built-in property testing — would need jqwik.
- **Spek**: Kotlin-native spec framework but less actively maintained than Kotest and lacks property testing.

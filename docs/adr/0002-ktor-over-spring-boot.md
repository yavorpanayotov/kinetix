# ADR-0002: Use Ktor Over Spring Boot for Kotlin Services

## Status
Accepted

## Context
We need a web/service framework for the Kotlin backend services. The main contenders are Spring Boot 3.x (most popular JVM framework), Ktor 3.1.3 (JetBrains Kotlin-native framework), and Micronaut (compile-time DI, GraalVM-friendly).

## Decision
Use Ktor 3.1.3 for all Kotlin services.

## Applies when
- Building a new Kotlin service or adding HTTP routes to an existing one.
- Wiring authentication, serialization, metrics, or logging into a service module.
- Tempted to reach for a Spring annotation (`@RestController`, `@Autowired`, `@Component`, etc.) — that's the signal to read this ADR.

## Rules
- **DO** use `embeddedServer(Netty, …)` + `Application.module()` for service entry points. Follow the layout established in `gateway/`, `position-service/`, `risk-orchestrator/`.
- **DO** install Ktor plugins via the `install(...)` DSL inside `Application.module()` — `ContentNegotiation`, `Authentication`, `StatusPages`, `CallLogging`, `MicrometerMetrics`.
- **DO** use Koin for DI. Module wiring lives in `Application.kt` alongside server setup.
- **DON'T** add Spring Boot, Spring Web, Spring Security, or Spring Data dependencies — even transitively.
- **DON'T** use annotation-driven request mapping or annotation-driven DI. If a library only ships annotations, wrap it behind a Ktor plugin or a Koin-provided bean.
- **DON'T** use Spring's reactive `Mono`/`Flux`. Use Kotlin coroutines and `Flow` — Ktor is coroutine-first.

## Consequences

### Positive
- Kotlin-native, coroutine-first — structured concurrency is a first-class citizen, not bolted on
- Lightweight: faster startup, lower memory footprint than Spring Boot
- No annotation magic — explicit DSL-based configuration is easier to reason about and debug
- Aligns with the rest of the Kotlin-native stack (Exposed, Koin, kotlinx.serialization)
- Plugin architecture allows pulling in only what each service needs

### Negative
- Smaller community and ecosystem compared to Spring Boot
- Fewer ready-made integrations (e.g., Spring Data, Spring Security) — we build or configure more ourselves
- Hiring: more developers are familiar with Spring Boot

### Alternatives Considered
- **Spring Boot 3.x**: Mature, massive ecosystem, but heavyweight for this use case. Annotation-driven model is less idiomatic in Kotlin. Coroutine support exists but is secondary to the reactive (WebFlux) model.
- **Micronaut 4.x**: Compile-time DI is appealing, but the framework is less Kotlin-idiomatic than Ktor and its ecosystem is smaller.

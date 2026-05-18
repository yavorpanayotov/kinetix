# ADR-0014: Resilience Patterns — DLQ and Circuit Breaker

## Status
Accepted

## Context
Kafka consumers and HTTP clients can encounter transient failures (network blips, service restarts, database locks). Without resilience patterns, a single poison message can stall a consumer, and a failing upstream service can cascade failures across the platform.

## Decision
Implement two resilience patterns in the shared `common` module:

### 1. RetryableConsumer with Dead-Letter Queue
`RetryableConsumer` (`common/.../kafka/RetryableConsumer.kt`) wraps Kafka message processing with:
- Configurable retry count (`maxRetries`, default 3)
- Exponential backoff (`baseDelayMs * 2^attempt`)
- Automatic routing to a DLQ topic (`<topic>.dlq`) after retries are exhausted
- Liveness tracking via `ConsumerLivenessTracker`

All 6 Kafka consumers across the platform use `RetryableConsumer`.

### 2. Circuit Breaker
`CircuitBreaker` (`common/.../resilience/CircuitBreaker.kt`) implements the standard three-state pattern:
- **CLOSED** (normal): requests pass through; failures are counted
- **OPEN** (tripped): requests fail fast with `CircuitBreakerOpenException` after `failureThreshold` (default 5) consecutive failures
- **HALF_OPEN** (probing): after `resetTimeoutMs` (default 30s), allows `halfOpenMaxCalls` (default 1) trial request to determine recovery

The implementation is coroutine-safe using Kotlin's `Mutex`.

## Applies when
- Writing a new Kafka consumer.
- Adding an HTTP or gRPC client to call another service.
- Tempted to wrap a call in raw `try/retry` loops or to swallow exceptions to "keep the consumer alive".

## Rules
- **DO** instantiate Kafka consumers via `RetryableConsumer` from `common/.../kafka/RetryableConsumer.kt`. Specify `maxRetries`, `baseDelayMs`, and the DLQ topic.
- **DO** provision a `<topic>.dlq` for every consumer-bearing topic in `infra/kafka/create-topics.sh`.
- **DO** wrap inter-service HTTP/gRPC calls in `CircuitBreaker` (`common/.../resilience/CircuitBreaker.kt`) when the downstream is critical-path.
- **DO** emit metrics from both patterns — DLQ message counts and circuit-state transitions are alertable signals.
- **DO** monitor DLQ topics (`<topic>.dlq`) for accumulating messages; a growing DLQ indicates a poison-message bug or schema mismatch.
- **DON'T** add Resilience4j or any other JVM resilience library. The two existing patterns in `common/` cover the cases we have.
- **DON'T** apply exponential backoff to user-facing request paths — retries can stack into multi-second waits. These patterns are for background processing and Kafka pipelines.
- **DON'T** silently retry on `CircuitBreakerOpenException` — propagate it; the caller decides whether to degrade or fail.

## Consequences

### Positive
- Poison messages are diverted to DLQ instead of blocking the consumer indefinitely
- Circuit breaker prevents cascade failures when a backend service is down
- Both patterns are in `common` — consistent behaviour across all services
- Configurable thresholds allow tuning per use case

### Negative
- DLQ messages require separate monitoring and reprocessing tooling
- Circuit breaker adds a state machine that can be surprising during debugging (requests fail fast when open)
- Exponential backoff means retries can take seconds — acceptable for Kafka processing, not suitable for latency-sensitive paths

### Alternatives Considered
- **Kafka-native retries (retry topics)**: More complex topic management; DLQ is simpler for our scale.
- **Resilience4j**: Mature JVM library, but adds a dependency for two patterns we can implement in ~100 lines of coroutine-safe Kotlin.
- **No retry / fail fast**: Simpler, but a single transient failure would drop messages permanently.

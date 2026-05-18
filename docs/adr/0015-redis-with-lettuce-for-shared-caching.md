# ADR-0015: Redis with Lettuce for Shared Caching

## Status
Accepted

## Context
VaR calculations are expensive (seconds per portfolio). When the UI polls for latest risk results, recalculating on every request is wasteful. We need a cache that is shared across gateway and risk-orchestrator instances, with TTL-based expiry.

## Decision
Use Redis (via Lettuce 6.5.3 client) as a shared cache for VaR results and quant diff snapshots. Cache access is abstracted behind interfaces:
- `VaRCache` — caches `ValuationResult` by book ID with configurable TTL (default 300s)
- `QuantDiffCache` — caches quantitative diff snapshots for run comparison

Implementations:
- `RedisVaRCache` / `RedisQuantDiffCache` — production implementations using Lettuce synchronous commands with `SetArgs.ex()` for TTL
- `InMemoryVaRCache` / `InMemoryQuantDiffCache` — in-memory fallbacks for local development and testing
- `LatestVaRCache` — keeps only the most recent result per book in memory

Cached values are serialized to JSON via `kotlinx.serialization` with dedicated `Cached*` DTOs to handle type conversion (BigDecimal ↔ String, Instant ↔ String, UUID ↔ String).

## Applies when
- Adding a cache to avoid re-computing or re-fetching an expensive value.
- Caching something that must be visible across replicas (gateway scale-out, orchestrator scale-out).
- Tempted to use Caffeine, Guava cache, or a `ConcurrentHashMap` for cross-replica caching.

## Rules
- **DO** define every cache behind an interface (e.g. `VaRCache`, `QuantDiffCache`). Implement `Redis*` for production and `InMemory*` for tests.
- **DO** serialise cached payloads via dedicated `Cached*` DTOs that convert `BigDecimal`, `Instant`, `UUID` to strings. Never serialise domain types directly.
- **DO** set an explicit TTL via `SetArgs.ex(seconds)`. No unbounded keys.
- **DO** key by a stable, low-cardinality identifier (e.g. `var:<bookId>:<runId>`). Document the key schema where the cache is defined.
- **DO** treat the cache as advisory — every code path must still work if the cache is empty or stale.
- **DON'T** add Caffeine, Guava cache, or any other in-process cache library when the cached state must be shared across replicas. In-process is acceptable only for per-instance state (e.g. `LatestVaRCache` for local convenience).
- **DON'T** use Redis as a database. No write-only paths, no source-of-truth state. Cache only.
- **DON'T** add a Hazelcast/Memcached/Infinispan dependency.

## Consequences

### Positive
- Sub-millisecond cache reads vs. seconds for a full VaR calculation
- TTL-based expiry ensures stale results are automatically purged
- Interface abstraction allows swapping to in-memory cache in tests without Redis dependency
- Lettuce is non-blocking and thread-safe, compatible with Ktor's coroutine model

### Negative
- Redis is an additional infrastructure component
- Cache serialization/deserialization adds complexity (dedicated `Cached*` DTOs for type safety)
- Cache invalidation is TTL-only — a new calculation may not immediately replace a cached result

### Alternatives Considered
- **In-process cache (Caffeine)**: No infrastructure dependency, but cache is per-instance — not shared across gateway replicas.

**Note (updated 2026-04-07):** Terminology updated to reflect the portfolio→book rename (V34).
- **PostgreSQL materialized views**: Too slow for per-request reads. Suitable for aggregates (used separately for daily summaries) but not for individual book lookups.
- **Hazelcast**: Embedded distributed cache, but heavier than Redis and less familiar operationally.

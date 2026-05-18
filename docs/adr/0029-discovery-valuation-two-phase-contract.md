# ADR-0029: Discovery-Valuation Two-Phase Contract

## Status
Accepted

## Context
The risk-engine is a Python gRPC service that performs computationally expensive valuation (VaR, Greeks, Monte Carlo, stress testing). These calculations require market data from multiple services — spot prices, yield curves, volatility surfaces, correlation matrices, credit spreads, dividend yields, and historical price series.

A natural but problematic approach would be for the risk engine to fetch this data itself. This creates coupling between the calculation engine and service discovery, HTTP/REST infrastructure, authentication, retry logic, and circuit-breaking — none of which belong in a numerical computation service. It also makes the engine hard to test in isolation, hard to reproduce results (the engine's outputs become a function of external service state at call time), and hard to reason about failures (did the calculation fail, or did a data fetch fail?).

## Decision
The risk-engine is a **pure calculator**. It receives all inputs via its gRPC request and returns all outputs via its gRPC response. It never makes outbound calls to fetch data from other services.

Data acquisition follows a two-phase contract between the risk-orchestrator and the risk-engine:

### Phase 1: Discovery
The orchestrator calls the risk-engine's `DiscoverDependencies` RPC (defined in `market_data_dependencies.proto`), passing the set of positions and the requested calculation type. The risk-engine inspects the positions — their asset classes, instrument types, and the calculation being requested — and returns a manifest of required market data dependencies. Each dependency specifies:
- **Data type** (spot price, yield curve, volatility surface, correlation matrix, etc.)
- **Identifier** (instrument ID, curve ID)
- **Parameters** (lookback window, tenor, currency)
- **Whether it is required or optional**

The dependency registry in the risk-engine (`dependencies.py`) encodes which data types each asset class needs. For example, derivatives require a volatility surface and risk-free rate; equities require spot prices and optionally historical prices for VaR; fixed income requires yield curves and credit spreads.

### Phase 2: Valuation
The orchestrator fetches all discovered dependencies from the appropriate market data services (price-service, rates-service, volatility-service, correlation-service, reference-data-service) and passes them — along with the positions — to the risk-engine's `Valuate` RPC. The engine computes the requested outputs using only the data provided in the request.

### The boundary rule
The risk-engine must have **zero knowledge** of how or where market data is sourced. It declares what it needs (discovery) and computes with what it receives (valuation). The orchestrator owns all data acquisition, caching, fallback, and resilience logic.

This rule applies to all current and future gRPC services exposed by the risk-engine. If a new calculation type requires additional market data, the correct response is to extend the dependency registry and the proto contract — never to add an HTTP client or service call inside the engine.

## Applies when
- Adding a new asset class, instrument type, or calculation method to the risk-engine.
- Touching `dependencies.py`, `DiscoverDependencies`, or `Valuate`.
- Tempted to add `httpx`, `requests`, a gRPC client, or any other outbound network library to `risk-engine/`.

## Rules
- **DO** declare new market-data dependencies in `dependencies.py`. The orchestrator fetches them; the engine receives them.
- **DO** add new data types to the `MarketDataValue` `oneof` in proto when the existing four shapes don't fit (ADR-0024).
- **DO** call `DiscoverDependencies` before `Valuate` for every calculation. The discovery round trip is part of the contract, not an optimisation to skip.
- **DO** keep the risk-engine deterministic: same inputs + same `monte_carlo_seed` + same `model_version` → same outputs.
- **DON'T** add an HTTP, gRPC, or Kafka client inside the risk-engine. The engine has zero outbound dependencies on Kinetix services.
- **DON'T** read from disk for market data, configuration files that vary at runtime, or environment-derived inputs. Everything that affects the result comes via the gRPC request.
- **DON'T** hardcode market data lookups in the orchestrator that bypass `DiscoverDependencies`. Domain knowledge of "which inputs does this calculation need" belongs in the engine.

## Consequences

### Positive
- **Testability**: The risk-engine can be tested with synthetic, deterministic inputs — no mocking of HTTP clients, no test containers for data services
- **Reproducibility**: Given the same positions and market data, the engine produces the same results. Run manifests (ADR-0018) capture input digests precisely because all inputs are explicit
- **Separation of concerns**: Infrastructure concerns (service discovery, retries, circuit-breaking, caching) stay in the orchestrator (Kotlin); numerical concerns (pricing models, VaR methodology) stay in the engine (Python)
- **Efficiency**: The orchestrator can fetch market data in parallel, cache aggressively (ADR-0015), and skip fetches for data already available — none of which the engine needs to know about
- **Debuggability**: When a valuation fails, the phase that failed is immediately clear — was it a data fetch issue (orchestrator) or a calculation issue (engine)?
- **Evolvability**: New data sources can be added to the orchestrator without touching the engine, and new calculation models can be added to the engine without touching the orchestrator's data-fetching logic

### Negative
- The orchestrator carries the complexity of knowing how to fetch every market data type from every service
- Adding a new asset class or calculation type requires changes in two places: the engine's dependency registry and the orchestrator's market data fetcher
- Discovery adds a round trip before every valuation — acceptable given that the subsequent data fetching and computation dominate latency

### Alternatives Considered
- **Risk engine fetches its own data**: Simpler initial wiring, but creates tight coupling between the engine and all data services. Breaks testability, reproducibility, and separation of concerns. Rejected — documented also in ADR-0021.
- **Static dependency mapping in the orchestrator**: The orchestrator hard-codes which data to fetch per asset class, without asking the engine. Avoids the discovery round trip but duplicates domain knowledge — the orchestrator would need to understand pricing model requirements, which belong in the engine. Rejected.
- **Push all data unconditionally**: The orchestrator fetches everything available and sends it all. Simple but wasteful — fetching volatility surfaces for a pure equity spot portfolio adds latency and network overhead for no benefit. Rejected.

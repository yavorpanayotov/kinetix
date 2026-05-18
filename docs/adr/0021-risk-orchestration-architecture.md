# ADR-0021: Risk Orchestration Architecture

## Status
Accepted

## Context
A VaR calculation requires data from multiple sources (positions, prices, yield curves, volatility surfaces, correlation matrices, credit spreads, dividend yields) and coordination of multiple phases (fetch, discover dependencies, compute, publish). This orchestration logic needs a home.

## Decision
The `risk-orchestrator` service owns the end-to-end VaR calculation workflow. `VaRCalculationService` orchestrates 5 sequential phases:

1. **Fetch Positions** — calls `PositionProvider` (HTTP client to position-service) to get current positions for a book
2. **Discover Dependencies** — `DependenciesDiscoverer` analyzes positions to determine which market data is needed (spot prices, vol surfaces, yield curves, etc.) based on instrument types and calculation method
3. **Fetch Market Data** — `MarketDataFetcher` retrieves required data from rates-service, volatility-service, correlation-service, reference-data-service, and price-service via HTTP
4. **Valuation** — sends positions + market data to the Python risk-engine via gRPC (`RiskEngineClient.valuate()`)
5. **Publish Result** — publishes `RiskResultEvent` to Kafka for downstream consumers

Each phase is independently timed and recorded in the `ValuationJob` record with detailed metadata (position counts, dependency lists, market data fetch results, VaR values).

Key design choices:
- Market data services are fetched via HTTP REST, not Kafka — the orchestrator needs point-in-time snapshots, not streaming data
- The risk-engine is called via gRPC for efficient binary serialization of large position/market data payloads
- Dependencies are grouped by position to enable targeted data fetching
- Run manifests capture input digests for reproducibility (see ADR-0018)

## Applies when
- Adding a new phase, input source, or output to the VaR / valuation workflow.
- Modifying `VaRCalculationService` or any of its collaborators (`PositionProvider`, `DependenciesDiscoverer`, `MarketDataFetcher`, `RiskEngineClient`, `RunManifestCapture`, `ResultPublisher`).
- Tempted to have the risk-engine fetch data, or to skip the discovery phase.

## Rules
- **DO** preserve the five sequential phases (fetch positions → discover → fetch market data → valuate → publish). Each phase is timed and recorded in `ValuationJob`.
- **DO** add a new collaborator as a new file and inject via Koin — don't fold new responsibility into `VaRCalculationService`.
- **DO** fetch market data via HTTP REST clients. Risk requires point-in-time snapshots, not streaming.
- **DO** call the risk-engine via gRPC `Valuate` (ADR-0024), passing all required market data inline (ADR-0029).
- **DO** capture a `RunManifest` between fetch and valuate (ADR-0018).
- **DO** publish the result on `risk.results` Kafka topic.
- **DON'T** let the risk-engine make outbound calls to fetch data. It is a pure calculator (ADR-0029).
- **DON'T** add a new "fast path" that skips manifest capture, dependency discovery, or result publication.
- **DON'T** introduce event-choreography for the workflow — the orchestrator owns the sequence.

## Consequences

### Positive
- Clear phase separation makes debugging straightforward — each phase's timing and inputs are recorded
- Market data dependency discovery means we only fetch what's needed, not everything
- gRPC to the risk engine avoids JSON serialization overhead for large payloads
- Phase-level error handling: a failed market data fetch degrades gracefully (proceeds with defaults)

### Negative
- Sequential phases mean total latency is the sum of all phases — no parallel fetching of market data types (potential future optimisation)
- The orchestrator has many collaborators (position provider, risk engine, market data fetcher, job recorder, manifest capture, result publisher)
- Market data HTTP calls add latency compared to a local cache

### Alternatives Considered
- **Event choreography**: Each service reacts to events and produces the next step. Simpler per service, but the overall workflow becomes invisible — hard to debug, hard to track progress.
- **Centralised batch engine**: A scheduled batch process that runs all calculations. Works for EOD but doesn't support on-demand or event-triggered calculations.
- **Risk engine fetches its own data**: The Python risk-engine calls market data services directly. Creates coupling between the engine and service discovery, and the engine should remain a pure calculation service.

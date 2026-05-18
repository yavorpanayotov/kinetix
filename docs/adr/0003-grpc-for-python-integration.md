# ADR-0003: Use gRPC for Kotlin-Python Integration

## Status
Accepted

## Context
The Python risk engine performs computationally intensive calculations (VaR, Monte Carlo, Greeks) and serves ML model predictions. Kotlin services need to communicate with it efficiently. Options: REST/JSON, gRPC/Protobuf, message queue (Kafka), embedded Python (GraalPython/Jython), or sidecar pattern.

## Decision
Use gRPC with Protocol Buffers for synchronous communication between the Kotlin risk-orchestrator and the Python risk-engine.

## Applies when
- Adding a new RPC between Kotlin and the Python risk-engine.
- Changing fields in `risk_calculation.proto`, `market_data_dependencies.proto`, or any other `.proto` under `proto/`.
- Considering a REST endpoint on the risk-engine, or considering Kafka as a way to invoke the engine.

## Rules
- **DO** define every Kotlin↔Python contract in `proto/` first. Add fields, then regenerate stubs, then implement.
- **DO** add new fields with the next available tag number. Never reuse tag numbers from removed fields — mark them `reserved` instead.
- **DO** route every new Kotlin→Python call through a typed interface in `risk-orchestrator` (e.g. `RiskEngineClient`) backed by a generated gRPC stub. Stub the interface in tests via the in-JVM Netty pattern from CLAUDE.md.
- **DO** propagate W3C traceparent via gRPC metadata — that is how distributed tracing crosses the language boundary.
- **DON'T** add a REST/JSON endpoint to the risk-engine. The engine speaks gRPC only.
- **DON'T** invoke the risk-engine via Kafka. Risk calls are synchronous request/response by design (ADR-0029).
- **DON'T** embed Python into the JVM (GraalPython/Jython). NumPy/QuantLib/PyTorch require CPython.

## Consequences

### Positive
- Binary serialization: Protobuf encoding of large numerical arrays (position vectors, scenario matrices) is 10-50x smaller than JSON, critical for high-volume financial data
- Streaming: gRPC bidirectional streaming enables batch VaR calculation pipelines (stream positions in, stream results out)
- Strong typing: `.proto` files are the single source of truth; both Kotlin (grpc-kotlin) and Python (grpcio) stubs are generated from the same definitions
- Distributed tracing: W3C TraceContext propagation works automatically across the language boundary
- Performance: lower latency than REST for request-response patterns

### Negative
- More complex than REST to set up (proto compilation, stub generation)
- Harder to debug than JSON — need tools like grpcurl or Bloom RPC
- Browser clients can't call gRPC directly (irrelevant — only the orchestrator calls the engine)

### Alternatives Considered
- **REST/JSON**: Simpler but significantly slower for large numerical payloads. No streaming. Weak typing.
- **Kafka (async)**: Good for fire-and-forget, but risk calculations are request-response — the orchestrator needs the result to proceed. Would require a correlation ID + reply topic pattern, adding unnecessary complexity.
- **Embedded Python (GraalPython)**: Cannot run NumPy, QuantLib, or PyTorch — these depend on C extensions that GraalPython doesn't support.
- **Sidecar**: One Python process per Kotlin service wastes resources. A centralized engine allows GPU sharing and model caching.

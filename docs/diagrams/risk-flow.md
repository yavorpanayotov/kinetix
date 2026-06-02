# Risk-flow — VaR run sequence

A single VaR run end-to-end, showing the risk-orchestrator's five sequential phases (ADR-0021) and the discovery-valuation two-phase gRPC contract with the pure-calculator risk engine (ADR-0029). Consult this when changing the orchestration pipeline, the engine contract, or the publish/notify fan-out.

```mermaid
sequenceDiagram
    actor U as Trader
    participant G as Gateway
    participant O as Risk Orchestrator
    participant P as Position Service
    participant MD as Market-data services
    participant RE as Risk Engine
    participant K as Kafka
    participant A as Audit Service
    participant N as Notification Service

    U->>G: POST /risk/var (JWT)
    G->>O: HTTP + correlationId
    Note over O,P: Phase 1 — fetch positions
    O->>P: GET positions for book
    P-->>O: positions
    Note over O,RE: Phase 2 — discover dependencies
    O->>RE: gRPC DiscoverDependencies(positions, calcType)
    RE-->>O: dependency manifest
    Note over O,MD: Phase 3 — fetch market data (point-in-time)
    O->>MD: HTTP prices / curves / vols / correlations
    MD-->>O: snapshots
    Note over O: capture RunManifest (ADR-0018)
    Note over O,RE: Phase 4 — valuate
    O->>RE: gRPC Valuate(positions + market data + seed)
    RE-->>O: VaR, Greeks, P&L
    Note over O,K: Phase 5 — publish
    O->>K: risk.results / risk.pnl.intraday / limits.breaches
    O->>K: risk.audit
    K->>A: audit event into SHA-256 chain
    K->>N: risk result
    N->>G: WebSocket push
    G-->>U: live update — Risk / P&L / Alerts tabs
```

Last regenerated: 2026-06-02 @ `1023b46b`

Source signals: ADR-0021 (5-phase orchestration), ADR-0029 (discovery-valuation), ADR-0024 (unified Valuate RPC), ADR-0018 (run manifests), ADR-0017 (hash-chained audit), `docs/wiki/Architecture.md` (trade booking → risk update flow).

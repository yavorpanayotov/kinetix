# C4 — Container

Every Kinetix service as a container, grouped by trust zone, with technology labels and the dominant transport between them. Consult this when deciding where new code belongs or how two services communicate. Synchronous calls (HTTP/gRPC) and asynchronous events (Kafka) are both shown; the full Kafka wiring is in [kafka-topology](kafka-topology.md).

```mermaid
graph TB
    subgraph client["Client zone"]
        ui["UI<br/>React 19 + TypeScript + Vite"]
    end

    subgraph edge["Edge / trust boundary"]
        gateway["Gateway<br/>Kotlin/Ktor — JWT, rate-limit, WS fan-out, SSE proxy"]
        keycloak["Keycloak<br/>OIDC / RBAC"]
    end

    subgraph core["Core services — Kotlin/Ktor"]
        position["Position Service<br/>trades, P&amp;L, limits"]
        orchestrator["Risk Orchestrator<br/>5-phase VaR pipeline"]
        regulatory["Regulatory Service<br/>governance, backtests, FRTB"]
        notification["Notification Service<br/>WebSocket push"]
        audit["Audit Service<br/>hash-chained trail"]
        fixgw["FIX Gateway<br/>FIX 4.4 sessions"]
    end

    subgraph marketdata["Market-data services — Kotlin/Ktor"]
        price["Price Service"]
        rates["Rates Service"]
        vol["Volatility Service"]
        corr["Correlation Service"]
        refdata["Reference Data Service"]
    end

    subgraph aiz["AI zone — Python"]
        aiinsights["AI Insights Service<br/>FastAPI + Claude SDK + in-proc MCP"]
        riskengine["Risk Engine<br/>Python/gRPC — NumPy/SciPy/PyTorch"]
    end

    subgraph infra["Infrastructure"]
        postgres["PostgreSQL / TimescaleDB"]
        redis["Redis"]
        kafka["Apache Kafka"]
    end

    ui -->|"REST / WebSocket"| gateway
    gateway -->|"OIDC"| keycloak
    gateway -->|"HTTP"| position
    gateway -->|"HTTP"| orchestrator
    gateway -->|"HTTP / SSE / WS"| aiinsights
    gateway -->|"HTTP"| regulatory

    position -->|"Kafka"| kafka
    fixgw -->|"Kafka"| kafka
    price --> kafka
    rates --> kafka
    vol --> kafka
    corr --> kafka

    orchestrator -->|"HTTP point-in-time"| price
    orchestrator -->|"HTTP"| rates
    orchestrator -->|"HTTP"| vol
    orchestrator -->|"HTTP"| corr
    orchestrator -->|"HTTP"| refdata
    orchestrator -->|"HTTP"| position
    orchestrator -->|"gRPC Discover + Valuate"| riskengine
    orchestrator -->|"Kafka risk.results"| kafka

    kafka --> notification
    kafka --> audit
    kafka --> aiinsights
    notification -->|"WS push"| gateway

    aiinsights -->|"MCP tool HTTP (X-User-Id)"| position
    aiinsights -->|"MCP tool HTTP"| orchestrator

    position --> postgres
    audit --> postgres
    position --> redis
```

Last regenerated: 2026-06-02 @ `c3ef7922`

Source signals: `settings.gradle.kts` (module list), `README.md` (Services in depth, Architecture), ADR-0012 (gateway aggregation), ADR-0024/0029 (unified Valuate, discovery-valuation), ADR-0036 (AI copilot, in-proc MCP, service-principal). Database-per-service per ADR-0011 — only representative Postgres/Redis edges drawn to keep the diagram legible.

# AI Copilot architecture (v2)

The v2 Copilot (ADR-0036) lives entirely inside `ai-insights-service` (FastAPI/Python): a Claude SDK conversation grounded in Kinetix data via an **in-process MCP server** (internal port 8096, never exposed through the gateway). Four user surfaces share the machinery — morning brief, intraday push, inline explainers, and the ⌘K palette. Write actions are out of scope. Consult this when touching any copilot surface, the MCP tool registry, or the streaming proxy.

## Components

```mermaid
flowchart TB
    subgraph browser["UI"]
        palette["⌘K palette"]
        inline["inline explainers"]
        brief["morning brief panel"]
        push["intraday push toasts"]
    end

    subgraph gw["Gateway"]
        sse["streamProxyToInsights (SSE)"]
        wsroute["/ws/copilot"]
        internal["POST /internal/copilot/push"]
        bcast["CopilotBroadcaster"]
    end

    subgraph ai["ai-insights-service — FastAPI/Python"]
        chat["chat / brief / saved-query (SSE)"]
        sdk["Claude SDK query()"]
        mcp["in-process MCP server :8096"]
        tools["read-only MCP tools"]
        guard["citation + banned-phrase guard"]
        kconsumer["Kafka consumer<br/>group ai-insights-risk-consumer"]
        evaluator["IntradayThresholdEvaluator"]
        pushgen["IntradayPushGenerator"]
    end

    claude["Anthropic Claude<br/>host ~/.claude credential"]
    downstream["Downstream services<br/>position / risk / price / vol / corr / audit"]
    k_risk(["Kafka — risk.results / risk.regime.changes"])

    palette --> sse
    inline --> sse
    brief --> sse
    sse --> chat
    chat --> sdk
    sdk --> mcp
    mcp --> tools
    tools -->|"HTTP + X-User-Id / X-User-Books"| downstream
    sdk --> claude
    chat --> guard

    k_risk --> kconsumer
    kconsumer --> evaluator
    evaluator --> pushgen
    pushgen -->|"POST"| internal
    internal --> bcast
    bcast -->|"WS (filtered by X-User-Books)"| wsroute
    wsroute --> push
```

## Intraday push sequence

```mermaid
sequenceDiagram
    participant K as Kafka
    participant AI as ai-insights-service
    participant G as Gateway
    participant UI

    K->>AI: risk.results / risk.regime.changes
    Note over AI: IntradayThresholdEvaluator vs copilot_alert_thresholds
    AI->>AI: IntradayPushGenerator composes sourced payload
    AI->>G: POST /internal/copilot/push (cluster-internal)
    G->>G: CopilotBroadcaster fan-out, filter by X-User-Books
    G->>UI: WebSocket /ws/copilot
```

Last regenerated: 2026-06-02 @ `1023b46b`

Source signals: ADR-0036 (AI Copilot architecture v2), `specs/ai-insights.allium`, `docker-compose.services.yml` (ai-insights-service, port 8096), ADR-0013 (Keycloak JWT → service-principal headers), ADR-0016 (broadcaster pattern).

# Architecture Diagrams

Regenerable [Mermaid](https://mermaid.js.org/) diagrams of the Kinetix platform. GitHub renders these natively — no external tooling, no stale Lucid links. Each file is self-describing: a summary, the diagram, a "Last regenerated" stamp, and the source signals it was derived from.

**These are the source of truth for diagrams.** Embedded copies in `README.md` and the GitHub wiki should be refreshed from here. Regenerate after any structural change with `/diagrams <scope>` and overwrite the previous output — stale diagrams are worse than none.

## Index

| Diagram | What it shows | When to consult |
|---|---|---|
| [c4-context](c4-context.md) | Actors + external systems around Kinetix as a black box | Top-level orientation |
| [c4-container](c4-container.md) | Every service as a container, by trust zone, with tech labels | Where new code belongs; how services talk |
| [service-dependencies](service-dependencies.md) | Synchronous HTTP/gRPC call graph (no Kafka) | Blast radius; what must be up |
| [kafka-topology](kafka-topology.md) | Producers → 20 topics → consumers + DLQs | Adding a topic/producer/consumer; event fan-out |
| [risk-flow](risk-flow.md) | VaR run end-to-end (5-phase + discovery-valuation) | Changing the orchestration pipeline or engine contract |
| [data-flow-trade](data-flow-trade.md) | A trade from UI click to risk update on screen | What touches a trade, in order |
| [data-flow-price](data-flow-price.md) | A price update → live tick + risk recalc | Market-data wiring |
| [data-flow-audit](data-flow-audit.md) | Events → SHA-256 hash chain → immutable store | Adding an auditable source; chain integrity |
| [ai-copilot](ai-copilot.md) | AI Copilot v2 components + intraday push sequence | Any copilot surface, MCP tools, streaming proxy |
| [auth-flow](auth-flow.md) | Keycloak JWT + service-principal pattern | Auth, gateway headers, per-book ACLs |
| [eod-promotion](eod-promotion.md) | Risk-run lifecycle to OFFICIAL_EOD (four-eyes) | Run labelling, promotion governance |
| [deployment](deployment.md) | Docker Compose topology (Caddy, services, infra) | Local-dev wiring |
| [observability](observability.md) | Metrics/logs/traces → Prometheus/Loki/Tempo/Grafana | Telemetry, correlation-id tracing |

Last regenerated: 2026-06-02 @ `1023b46b`

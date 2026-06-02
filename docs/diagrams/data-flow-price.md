# Data flow — Price update

A market-data price update from ingestion to the two things it drives: a live UI tick (WebSocket fan-out) and a risk recalculation. Note the orchestrator consumes prices from Kafka for *streaming awareness* but fetches point-in-time snapshots over HTTP for an actual valuation (ADR-0021) — the two paths are distinct.

```mermaid
flowchart TD
    vendor["Market Data Vendor"]
    prc["Price Service<br/>ingest, validate, store"]
    tsdb[("TimescaleDB — price history")]
    k_price(["Kafka — price.updates"])
    gw["Gateway — WS fan-out"]
    ui["UI — live price ticks"]
    orch["Risk Orchestrator"]

    vendor -->|"feed"| prc
    prc --> tsdb
    prc -->|"publish (key=instrumentId)"| k_price
    k_price --> gw
    gw -->|"WebSocket"| ui
    k_price -->|"streaming awareness"| orch
    orch -.->|"point-in-time GET on valuation"| prc
```

Last regenerated: 2026-06-02 @ `1023b46b`

Source signals: `docs/wiki/Architecture.md` (Kafka topic table — `price.updates` producer/consumers), ADR-0005 (TimescaleDB), ADR-0021 (orchestrator fetches point-in-time via HTTP, not streaming), ADR-0016 (WebSocket for real-time UI).

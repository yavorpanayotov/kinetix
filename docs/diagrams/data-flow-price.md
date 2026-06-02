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

Last regenerated: 2026-06-02 @ `c3ef7922`

Source signals: `price-service/kafka/KafkaPricePublisher.kt` (topic `price.updates`, key=instrumentId), `risk-orchestrator/Application.kt` (`PriceEventConsumer` consumes `price.updates`; `HttpPriceServiceClient` fetches point-in-time), `gateway/kafka/KafkaIntradayPnlConsumer.kt` + `DevModule.kt` (gateway consumes `price.updates` for WS fan-out), ADR-0005 (TimescaleDB), ADR-0021 (point-in-time fetch), ADR-0016 (WebSocket).

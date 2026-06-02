# Kafka Topology

Kinetix uses Apache Kafka (KRaft mode) as the event backbone — each topic has a `.dlq` counterpart, and every consumer wraps in a `RetryableConsumer` ([ADR-0014](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0014-resilience-patterns-dlq-circuit-breaker.md)) with bounded retries before routing to the DLQ. Partition keys are chosen for ordering or aggregation locality.

This page is the rendered counterpart to the producer/consumer table on the [Architecture](Architecture) page. The diagram source lives at [`docs/diagrams/kafka-topology.md`](https://github.com/panayotovk/kinetix/blob/main/docs/diagrams/kafka-topology.md) and is regenerable with `/diagrams kafka`.

Note: `rates.yield-curves`, `rates.risk-free`, `rates.forwards`, `volatility.surfaces`, and `correlation.matrices` are published to Kafka but the risk-orchestrator fetches these point-in-time over HTTP rather than consuming from Kafka — no Kafka consumer exists for those five topics.

```mermaid
flowchart LR
    pos["position-service"]
    fix["fix-gateway"]
    prc["price-service"]
    rts["rates-service"]
    vol["volatility-service"]
    cor["correlation-service"]
    orch["risk-orchestrator"]
    reg["regulatory-service"]
    aud["audit-service"]
    gw["gateway"]
    notif["notification-service"]
    ai["ai-insights-service"]

    t_trades(["trades.lifecycle"])
    t_exec(["execution.reports"])
    t_fixses(["fix.session.events"])
    t_orders(["orders.topic"])
    t_price(["price.updates"])
    t_rates(["rates.yield-curves<br/>rates.risk-free<br/>rates.forwards"])
    t_vol(["volatility.surfaces"])
    t_corr(["correlation.matrices"])
    t_results(["risk.results"])
    t_xbook(["risk.cross-book-results"])
    t_pnl(["risk.pnl.intraday"])
    t_regime(["risk.regime.changes"])
    t_anom(["risk.anomalies"])
    t_raudit(["risk.audit"])
    t_eod(["risk.official-eod"])
    t_limits(["limits.breaches"])
    t_gaudit(["governance.audit"])

    dlq[["*.dlq — per-topic"]]

    pos --> t_trades & t_orders & t_limits
    fix --> t_exec & t_fixses
    prc --> t_price
    rts --> t_rates
    vol --> t_vol
    cor --> t_corr
    orch --> t_results & t_xbook & t_pnl & t_regime & t_anom & t_raudit & t_eod
    reg --> t_gaudit
    gw --> t_gaudit

    t_trades --> orch & aud & notif
    t_exec --> pos
    t_fixses --> pos
    t_orders --> fix
    t_price --> orch & gw
    t_results --> gw & notif & ai
    t_xbook --> gw
    t_pnl --> gw
    t_regime --> notif & gw & ai
    t_anom --> notif & gw
    t_raudit --> aud
    t_eod --> gw & reg
    t_limits --> notif & gw
    t_gaudit --> aud

    t_trades -. retry exhausted .-> dlq
    t_price -.-> dlq
    t_results -.-> dlq
    t_anom -.-> dlq
    t_limits -.-> dlq
    t_regime -.-> dlq
```

## Resilience

- **Bounded retries → DLQ.** `RetryableConsumer` retries a configurable number of times, then routes the poisoned message to the topic's `.dlq`. Ops tooling can replay from the DLQ once the cause is fixed.
- **Ordering.** Partition keys (`tradeId`, `instrumentId`, `bookId`, …) preserve per-entity ordering where it matters.
- **Correlation.** Every Kafka message carries the `correlationId` header ([ADR-0022](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0022-correlation-id-propagation.md)) so an event can be stitched to its originating UI click and risk run in Tempo.

See also: [Architecture](Architecture) · [Observability](Observability)

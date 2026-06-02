# Kafka Topology

Kinetix uses Apache Kafka (KRaft mode) as the event backbone — 20 production topics, each with a `.dlq` counterpart. Every consumer wraps in a `RetryableConsumer` ([ADR-0014](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0014-resilience-patterns-dlq-circuit-breaker.md)) with bounded retries before routing to the DLQ. Partition keys are chosen for ordering or aggregation locality.

This page is the rendered counterpart to the producer/consumer table on the [Architecture](Architecture) page. The diagram source lives at [`docs/diagrams/kafka-topology.md`](https://github.com/panayotovk/kinetix/blob/main/docs/diagrams/kafka-topology.md) and is regenerable with `/diagrams kafka`.

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

    t_trades(["trades.lifecycle"])
    t_exec(["execution.reports"])
    t_fixses(["fix.session.events"])
    t_orders(["orders.topic"])
    t_price(["price.updates"])
    t_rates(["rates.yield-curves / forwards / risk-free"])
    t_vol(["volatility.surfaces"])
    t_corr(["correlation.matrices"])
    t_results(["risk.results"])
    t_xbook(["risk.cross-book-results"])
    t_pnl(["risk.pnl.intraday"])
    t_regime(["risk.regime.changes"])
    t_anom(["risk.anomalies"])
    t_raudit(["risk.audit"])
    t_breaks(["risk.breaks"])
    t_eod(["risk.official-eod"])
    t_limits(["limits.breaches"])
    t_gaudit(["governance.audit"])
    t_chain(["kinetix.audit.chain"])

    dlq[["*.dlq — per-topic"]]

    c_orch["risk-orchestrator"]
    c_notif["notification-service"]
    c_gw["gateway (WS fan-out)"]
    c_aud["audit-service"]
    c_pos["position-service"]
    c_reg["regulatory-service"]
    c_ai["ai-insights-service"]

    pos --> t_trades & t_orders
    fix --> t_exec & t_fixses
    prc --> t_price
    rts --> t_rates
    vol --> t_vol
    cor --> t_corr
    orch --> t_results & t_xbook & t_pnl & t_regime & t_anom & t_raudit & t_breaks & t_eod & t_limits
    reg --> t_gaudit
    aud --> t_chain

    t_trades --> c_aud & c_orch & c_notif
    t_exec --> c_pos
    t_fixses --> c_pos
    t_orders --> fix
    t_price --> c_orch & c_gw
    t_rates --> c_orch
    t_vol --> c_orch
    t_corr --> c_orch
    t_results --> c_gw & c_notif & c_ai
    t_xbook --> c_gw
    t_pnl --> c_gw
    t_regime --> c_notif & c_gw & c_ai
    t_anom --> c_notif & c_gw
    t_raudit --> c_aud
    t_breaks --> c_notif
    t_eod --> c_gw & c_reg
    t_limits --> c_notif & c_gw
    t_gaudit --> c_aud
    t_chain --> c_aud

    t_trades -. retry exhausted .-> dlq
    t_price -.-> dlq
    t_results -.-> dlq
    t_anom -.-> dlq
```

## Resilience

- **Bounded retries → DLQ.** `RetryableConsumer` retries a configurable number of times, then routes the poisoned message to the topic's `.dlq`. Ops tooling can replay from the DLQ once the cause is fixed.
- **Ordering.** Partition keys (`tradeId`, `instrumentId`, `bookId`, …) preserve per-entity ordering where it matters.
- **Correlation.** Every Kafka message carries the `correlationId` header ([ADR-0022](https://github.com/panayotovk/kinetix/blob/main/docs/adr/0022-correlation-id-propagation.md)) so an event can be stitched to its originating UI click and risk run in Tempo.

See also: [Architecture](Architecture) · [Observability](Observability)

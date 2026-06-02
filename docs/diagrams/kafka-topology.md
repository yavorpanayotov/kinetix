# Kafka topology

Producers (left) → topics (centre) → consumers (right) for the production topics. Every topic has a `.dlq` counterpart; consumers wrap in a `RetryableConsumer` with bounded retries before routing to the DLQ (ADR-0014). Consult this when adding a topic, producer, or consumer, or when tracing event fan-out. Test-suffixed topics are omitted.

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

Last regenerated: 2026-06-02 @ `c3ef7922`

Source signals: Topic literals from `grep -rn "topic\s*=\s*\""` across all service `src/main/kotlin` directories; `KafkaRatesPublisher.kt`, `KafkaVolatilityPublisher.kt`, `KafkaCorrelationPublisher.kt` (topic names); `notification-service/Application.kt` (consumers: `risk.results`, `risk.anomalies`, `limits.breaches`, `risk.regime.changes`); `audit-service/Application.kt` (consumers: `trades.lifecycle`, `governance.audit`); `risk-orchestrator/Application.kt` (consumers: `trades.lifecycle`, `price.updates`; publishers: `risk.results`, `risk.cross-book-results`, `risk.pnl.intraday`, `risk.regime.changes`, `risk.audit`, `risk.official-eod`, `risk.anomalies`); `ai-insights-service/src/kinetix_insights/push/kafka_consumer.py` (consumers: `risk.results`, `risk.regime.changes`); `gateway/DevModule.kt` (consumer: `risk.pnl.intraday`); ADR-0004 (Kafka), ADR-0014 (DLQ + RetryableConsumer), ADR-0036 (ai-insights Kafka consumption).

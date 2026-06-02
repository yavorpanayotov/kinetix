# Kafka topology

Producers (left) → topics (centre) → consumers (right) for the 20 production topics. Every topic has a `.dlq` counterpart; consumers wrap in a `RetryableConsumer` with bounded retries before routing to the DLQ (ADR-0014). Consult this when adding a topic, producer, or consumer, or when tracing event fan-out. Test-suffixed topics (`*.test-1`, `*.e2e`, …) are omitted.

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

Last regenerated: 2026-06-02 @ `1023b46b`

Source signals: `grep -rhoE '"[a-z]+\.[a-z0-9.-]+"' --include=*.kt` across services (topic literals), `docs/wiki/Architecture.md` (Kafka topic → producer/consumer/partition-key table), ADR-0004 (Kafka), ADR-0014 (DLQ + RetryableConsumer), ADR-0036 (ai-insights consumes `risk.results` + `risk.regime.changes`).

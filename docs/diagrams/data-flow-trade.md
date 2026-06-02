# Data flow — Trade

A trade from the UI click to the risk update landing back on screen, including the pre-trade limit checks (ADR-0023), the Kafka hop, and the audit fork. Consult this to understand what touches a trade and in what order.

```mermaid
flowchart TD
    ui["UI — Place trade"]
    gw["Gateway — Keycloak JWT"]
    pos["Position Service<br/>pre-trade limit checks (6 levels)<br/>persist trade"]
    posdb[("Postgres — positions")]
    k_trades(["Kafka — trades.lifecycle"])
    orch["Risk Orchestrator<br/>5-phase pipeline"]
    re["Risk Engine — Valuate"]
    k_results(["Kafka — risk.results / pnl.intraday / limits.breaches"])
    k_audit(["Kafka — risk.audit + trades.lifecycle"])
    notif["Notification Service"]
    audit["Audit Service<br/>SHA-256 hash chain"]
    auddb[("Postgres — audit (immutable)")]
    wsui["UI — Positions / P&amp;L / Risk / Alerts"]

    ui -->|"REST POST /trades"| gw
    gw -->|"HTTP"| pos
    pos --> posdb
    pos -->|"publish"| k_trades
    k_trades --> orch
    orch -->|"gRPC"| re
    re --> orch
    orch --> k_results
    orch --> k_audit
    k_results --> notif
    k_audit --> audit
    audit --> auddb
    notif -->|"WebSocket via gateway"| wsui
```

Last regenerated: 2026-06-02 @ `1023b46b`

Source signals: `docs/wiki/Architecture.md` (trade booking → risk update), ADR-0023 (hierarchical limits), ADR-0021 (orchestration), ADR-0017 (audit chain), Kafka topic literals (`trades.lifecycle`, `risk.results`, `risk.audit`).

# C4 — System Context

Kinetix as a single black box, showing the human actors who use it and the external systems it integrates with. Consult this when you need the 10,000-foot view: who depends on the platform and what it talks to across its trust boundary. For the internals, see [c4-container](c4-container.md).

```mermaid
graph TB
    trader["Trader<br/>books trades, monitors risk & P&amp;L"]
    riskmgr["Risk Manager<br/>limits, VaR, EOD sign-off"]
    compliance["Compliance Officer<br/>model governance, submissions"]
    auditor["Auditor<br/>immutable trail review"]
    quant["Quant<br/>model validation, backtests"]

    kinetix["Kinetix Platform<br/>Market-risk management:<br/>VaR, Greeks, limits, FRTB, audit"]

    venue["Trading Venue / ECN"]
    primebroker["Prime Broker"]
    mdvendor["Market Data Vendor"]
    keycloak["Keycloak<br/>identity provider"]
    anthropic["Anthropic Claude<br/>AI copilot via host credential"]

    trader --> kinetix
    riskmgr --> kinetix
    compliance --> kinetix
    auditor --> kinetix
    quant --> kinetix

    kinetix -->|"FIX 4.4 orders"| venue
    kinetix -->|"FIX 4.4 give-up / allocations"| primebroker
    mdvendor -->|"prices, curves, vols"| kinetix
    kinetix -->|"OIDC / JWT"| keycloak
    kinetix -->|"grounded chat over MCP"| anthropic
```

Last regenerated: 2026-06-02 @ `1023b46b`

Source signals: `README.md` (At a glance, Architecture), `docs/wiki/Architecture.md`, ADR-0013 (Keycloak), ADR-0035 (FIX gateway extraction), ADR-0036 (AI copilot — host credential, no API key).

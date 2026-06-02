# Deployment topology (local / demo)

How the platform runs locally via Docker Compose: a Caddy reverse proxy fronts the UI and gateway, all services and the shared infrastructure run as containers on one network. Compose files: `docker-compose.services.yml` (services) + `infra/docker-compose.infra.yml` (Postgres, Kafka, Redis) + `infra/docker-compose.observability.yml`. Consult this for local-dev wiring; production hosting decisions live elsewhere.

```mermaid
flowchart TB
    browser["Browser"]
    caddy["Caddy<br/>reverse proxy + TLS<br/>*.kinetixrisk.ai"]
    ui["ui (React/Vite)"]
    gateway["gateway"]

    subgraph svcs["Application containers"]
        position["position-service"]
        orchestrator["risk-orchestrator"]
        price["price-service"]
        rates["rates-service"]
        vol["volatility-service"]
        corr["correlation-service"]
        refdata["reference-data-service"]
        regulatory["regulatory-service"]
        notification["notification-service"]
        audit["audit-service"]
        fixgw["fix-gateway"]
        aiinsights["ai-insights-service"]
        riskengine["risk-engine"]
        demo["demo-orchestrator"]
    end

    subgraph infra["Infrastructure containers"]
        postgres[("postgres / TimescaleDB")]
        kafka[("kafka")]
        redis[("redis")]
    end

    browser --> caddy
    caddy --> ui
    caddy --> gateway
    gateway --> svcs
    svcs --> postgres
    svcs --> kafka
    svcs --> redis
    orchestrator -->|"gRPC"| riskengine
```

Last regenerated: 2026-06-02 @ `c3ef7922`

Source signals: `docker-compose.services.yml` (service + caddy + ui containers), `infra/docker-compose.infra.yml` (postgres, kafka, redis), `CLAUDE.md` (local URLs, redeploy.sh), `settings.gradle.kts` (module list). Deployment is Docker Compose (not Kubernetes) per project conventions.

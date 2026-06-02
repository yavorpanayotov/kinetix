# Observability stack

The three telemetry signals and where they land (ADR-0008): metrics via Micrometer → Prometheus, structured logs via the OpenTelemetry Collector → Loki, traces via the OTel SDK → Tempo — all visualised in Grafana. A `correlationId` minted at the gateway (ADR-0022) threads through HTTP, Kafka headers, gRPC metadata, and audit rows, so one Tempo/Grafana query stitches UI click → API call → Kafka event → risk run → audit chain.

```mermaid
flowchart LR
    subgraph services["Kotlin/Ktor + Python services"]
        svc["each service<br/>Micrometer + OTel SDK"]
    end

    otelcol["OpenTelemetry Collector"]
    prom["Prometheus<br/>metrics"]
    loki["Loki<br/>logs"]
    tempo["Tempo<br/>traces"]
    grafana["Grafana<br/>dashboards + Explore"]

    svc -->|"metrics scrape"| prom
    svc -->|"structured JSON logs"| otelcol
    svc -->|"spans (correlationId attr)"| otelcol
    otelcol --> loki
    otelcol --> tempo
    prom --> grafana
    loki --> grafana
    tempo --> grafana
```

Last regenerated: 2026-06-02 @ `c3ef7922`

Source signals: ADR-0008 (Grafana stack — Prometheus + Loki + Tempo), ADR-0022 (correlation-id propagation), `infra/docker-compose.observability.yml`, `deploy/observability/`, `common/observability/OtelInit.kt` + `CorrelationIdHttpServerPlugin.kt` (OTel instrumentation in every service).

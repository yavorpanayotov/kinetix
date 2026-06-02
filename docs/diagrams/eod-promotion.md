# EOD promotion lifecycle

The state machine for promoting a risk run to the official end-of-day number (ADR-0019). Scheduled runs land as `SCHEDULED`; once complete they are eligible for promotion to `OFFICIAL_EOD` under a four-eyes rule. Reports and regulatory submissions reference promoted runs only, so EOD numbers are frozen and non-racy. Consult this when touching run labelling, promotion governance, or report sourcing.

```mermaid
stateDiagram-v2
    [*] --> SCHEDULED: scheduled VaR run created
    SCHEDULED --> RUNNING: orchestrator picks up
    RUNNING --> COMPLETED: valuation succeeds
    RUNNING --> FAILED: phase error
    FAILED --> [*]
    COMPLETED --> OFFICIAL_EOD: promote (four-eyes approval)
    COMPLETED --> SUPERSEDED: a later run promoted instead
    OFFICIAL_EOD --> [*]: frozen — used by reports & submissions
    SUPERSEDED --> [*]
```

Last regenerated: 2026-06-02 @ `1023b46b`

Source signals: ADR-0019 (official EOD labelling with promotion governance), `docs/wiki/Architecture.md` (EOD promotion), Kafka topic `risk.official-eod` (consumers: gateway, regulatory). Intermediate `RUNNING`/`FAILED`/`SUPERSEDED` states inferred from the run lifecycle — verify against `ValuationJob` status enum before relying on exact names.

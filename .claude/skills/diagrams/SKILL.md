---
name: diagrams
description: Generate C4, sequence, and data-flow diagrams from the Kinetix codebase as Mermaid blocks — embedded in Markdown under docs/diagrams/. Live, regenerable, no stale Lucid links. Invoke with /diagrams optionally followed by scope (e.g. "full", "risk-flow", "service:gateway", "kafka").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash
---

# Architecture Diagrams (Mermaid)

Generate **regenerable architecture diagrams** as Mermaid blocks embedded in Markdown. The point is to keep diagrams in sync with the code — run `/diagrams` after major architectural changes and overwrite the previous output.

Stale diagrams are worse than no diagrams. This skill exists to make diagrams cheap to refresh.

## Step 1 — Parse scope

- `full` (default) — generate the full set.
- `risk-flow` — focus on the risk calculation pipeline (UI → orchestrator → engine → Kafka → consumers).
- `kafka` — Kafka topology only (producers, topics, consumers, DLQs).
- `service:<name>` — internals of one service (routes, repositories, clients).
- `data-flow:<entity>` — trace one entity (e.g. trade, price update, audit event) end-to-end.

## Step 2 — Source material

Always read:
- `README.md` (architecture diagram and service list).
- `docs/adr/README.md` (decision context).
- `settings.gradle.kts` (module list).

For specific scopes also read the relevant service `Application.kt`, route files, repository interfaces, and Kafka consumer/producer wiring.

For Kafka scope, grep for `topic = "` or `KafkaProducer` / `KafkaConsumer` configuration across services to enumerate topics, producers, consumers.

## Step 3 — Diagrams to produce

For `full` scope, produce all four diagrams. For narrower scopes, produce the relevant subset.

### Diagram 1 — C4 Context (Mermaid `graph TB`)
External actors (Trader, Risk Manager, Compliance Officer, Auditor, Venue, Prime Broker, Market Data Vendor) interacting with the Kinetix platform as a black box.

### Diagram 2 — C4 Container (Mermaid `graph TB` or `C4Container`)
Each Kinetix service as a container, with technology labels (Kotlin/Ktor, Python/gRPC, FastAPI, React/Vite, Postgres, Redis, Kafka). Show the gateway boundary and trust zones.

### Diagram 3 — Risk-flow sequence (Mermaid `sequenceDiagram`)
A single VaR run end-to-end: UI request → gateway → risk-orchestrator → reference-data + market-data lookups → risk-engine valuate → results published to Kafka → audit chain → notification fan-out.

### Diagram 4 — Kafka topology (Mermaid `flowchart LR`)
Topics on the left, consumers on the right, producers on the far left. Show DLQ topics distinctly. Mark topics with their partition count and retention if known.

### Diagram 5 (optional) — Data flow for a specific entity
For `data-flow:<entity>`, trace the entity through every service that touches it. Include storage and replication points.

## Step 4 — Output format

Write to `docs/diagrams/` with one file per diagram:

- `docs/diagrams/c4-context.md`
- `docs/diagrams/c4-container.md`
- `docs/diagrams/risk-flow.md`
- `docs/diagrams/kafka-topology.md`
- `docs/diagrams/data-flow-<entity>.md`

Each file:
1. H1 title.
2. One-paragraph summary of what the diagram shows and when to consult it.
3. The Mermaid block.
4. A "Last regenerated" line with the current date and the git short SHA.
5. A "Source signals" footnote listing the files/grep patterns the diagram was derived from — so the reader can verify or refresh.

Also write/update `docs/diagrams/README.md` as an index of all diagrams.

## Step 5 — Embed checks

Mermaid blocks must:
- Use stable node IDs (e.g. `gateway`, `position_svc`) — not generated/random IDs.
- Quote labels that contain spaces or punctuation.
- Stay under ~40 nodes per diagram — split if needed.
- Render in GitHub Markdown without extensions.

After writing, run a syntax check by parsing the Mermaid blocks (you can use a `node -e` one-liner with `@mermaid-js/mermaid` if available, or manually verify common syntax pitfalls — unclosed quotes, reserved words used as IDs).

## Step 6 — Output summary

Print: which diagrams were generated, which files were written, a note if any source signals were ambiguous (e.g. a Kafka topic was found in code but no consumer was identified).

## Reminders

- The diagram is for a human reader. Aim for legibility over completeness — leave low-value detail out.
- Cite the ADR that drives any non-obvious choice in the diagram description (e.g. "Kafka topic split per ADR-0011").
- This skill is also a `key-decisions` companion — diagrams should reflect what the code actually does, not what an old ADR thought it should do.

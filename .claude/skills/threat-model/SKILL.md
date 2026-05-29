---
name: threat-model
description: Build a STRIDE-based threat model for Kinetix — assets, trust boundaries, threats per component, mitigations in place, gaps. Specific to a market-risk platform (audit-chain forgery, model-output integrity, AI copilot hallucination, market-data tampering). Invoke with /threat-model optionally followed by a scope (e.g. "audit chain", "AI copilot", "gateway").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash, Task
---

# Kinetix Threat Model

Produce a **STRIDE threat model** tailored to a market-risk platform. The audience is a security architect, CISO, or third-party assessor performing vendor due diligence. The document is also a working artefact for the engineering team to track mitigation gaps.

Generic OWASP-style threat models do not impress this audience. Domain-specific threats do.

## Step 1 — Scope

If the user passed a scope (e.g. "audit chain"), focus the model on that component and its immediate boundaries. Otherwise cover the full platform.

Read `README.md` for the architecture diagram, ADRs 0011 (database-per-service), 0013 (Keycloak auth), 0017 (audit chain), 0022 (correlation IDs), 0036 (AI copilot).

## Step 2 — Assets and trust boundaries

List the assets a threat actor would target:
- **Risk numbers** — VaR, capital, P&L (output integrity).
- **Position state** — trades, limits, breaches (state integrity).
- **Market data** — prices, curves, vol surfaces (input integrity).
- **Audit chain** — hash-chained governance events (tamper evidence).
- **Identities and authorisations** — Keycloak realm, four-eyes approvals.
- **AI copilot outputs** — narratives, citations (output trust + prompt injection surface).
- **PII / counterparty data** — minimal but present in reference data.

For each, name the data store, the producing service, and the consumers.

Then list trust boundaries (Internet → Gateway, Gateway → Internal services, Internal services → Risk engine, Internal services → AI copilot, Internal services → Postgres, Internal services → Kafka).

## Step 3 — STRIDE per component

For each major component, produce a STRIDE table:

| Category | Threat | Likelihood | Impact | Existing mitigation | Gap |
|---|---|---|---|---|---|

Components to cover (skip irrelevant ones for narrow scopes):
- Gateway (Keycloak JWT, rate limit, WebSocket fan-out)
- Position service
- Risk orchestrator
- Risk engine (Python, gRPC)
- AI insights service (FastAPI + Claude Agent SDK + MCP)
- Kafka cluster
- Audit service + audit chain
- Postgres / TimescaleDB
- Notification service (WebSocket)

STRIDE categories:
- **S**poofing — identity faking
- **T**ampering — data integrity attacks
- **R**epudiation — actions a user could deny
- **I**nformation disclosure — confidentiality breach
- **D**enial of service — availability attacks
- **E**levation of privilege — authorisation bypass

For each component name 3–6 specific threats. Examples of domain-specific threats to include where applicable:

- **Audit-chain forgery** — attacker inserts a row with a forged previous-hash. Mitigation: pg_advisory_xact_lock serialises chain writes; nightly chain verification job.
- **Risk-number tampering at rest** — attacker mutates `risk.results` to hide a breach. Mitigation: hash-chained audit captures the result; reconstruction from manifests (ADR-0018).
- **AI copilot prompt injection** — adversarial input via a position comment or counterparty name triggers misleading narrative. Mitigation: citation enforcement (ADR-0036); copilot is read-only.
- **Market-data tampering** — attacker submits crafted prices to skew VaR. Mitigation: source authentication, sanity bands, regime classifier divergence.
- **Four-eyes bypass on EOD promotion** — same operator approves their own promotion. Mitigation: ADR-0019 governance check.
- **NMRF identification bypass** — attacker manipulates observability inputs to keep a factor "modellable". (FRTB IMA threat.)
- **Kafka topic spoofing** — attacker produces to a topic without auth.
- **JWT replay across services** — token capture and replay during the validity window.

## Step 4 — Cross-cutting threats

A short section covering threats that span components:
- Supply-chain (dependency compromise — link to `/dep-audit`)
- Insider threat (privileged DB / Kafka access)
- Secret leakage (env vars, container images)
- Time-skew attacks (TimescaleDB ordering, manifest reproducibility)

## Step 5 — Gap register

Distil every "Gap" cell from the STRIDE tables into a prioritised register:

| Priority | Component | Gap | Recommended mitigation | Owner |
|---|---|---|---|---|

Priority is P0 (block ship) / P1 (next quarter) / P2 (backlog). Be honest — empty gaps are not credible.

## Step 6 — Output

Write to `docs/security/threat-model.md` (or `docs/security/threat-model-<scope>.md` for scoped runs). Create `docs/security/` if needed. Print the file path and a summary: total threats catalogued, gaps by priority.

## Reminders

- Cite specific ADRs and code paths for every mitigation. No hand-waving.
- "Defence in depth" is not a mitigation. Name the actual control.
- Pair this skill with `/security-engineer` for adversarial review of the finished model.
- Do not include any production credentials, internal hostnames, or live URLs.

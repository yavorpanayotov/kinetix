---
name: migration-playbook
description: Generate a "from <incumbent vendor system> to Kinetix" migration playbook — assessment, parallel run, data migration, cutover, decommissioning. Invoke with /migration-playbook followed by the source system (e.g. "Murex", "Calypso", "Adaptiv", "Razor", "in-house").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash, WebFetch
---

# Migration Playbook

Produce a **migration playbook** describing how an institution would move from an incumbent risk system to Kinetix. The audience is a programme manager, technology architect, or Head of Risk planning a 12–24 month migration.

Migration documents are how vendors win RFPs. A well-grounded playbook signals that the team has thought about the real path, not just the ideal one.

## Step 1 — Identify source system

The argument names the incumbent. Common ones:
- **Murex** (MX.3) — sell-side, broad asset class.
- **Calypso (Adenza)** — cross-asset, treasury-heavy.
- **Adaptiv (FIS) / Razor (FNZ)** — credit and market risk.
- **Sophis** — equity-derivative-heavy.
- **OpenGamma** — buy-side analytics.
- **In-house** — legacy bespoke system.

If unspecified, default to a generic vendor migration but flag in the document that source-system specifics drive the real shape.

Use WebFetch sparingly to confirm the vendor's data model boundaries (e.g. Murex's trade lifecycle events) — only from official vendor docs or reputable industry sources.

## Step 2 — Structure

### 1. Executive summary
Half a page. Timeline (typical 12–18 months), team size, key risks, decommissioning preconditions.

### 2. Assessment phase (weeks 1–6)
- Inventory: trade types, instrument coverage, model coverage, integrations, reports, users.
- Gap analysis: capability matrix of Kinetix vs source — pull from `/regulatory-map` and the README "Quant & risk methodology" section.
- Architecture mapping: identify Kinetix services (position, risk-orchestrator, audit, etc.) each source-system module maps to.

### 3. Parallel run phase (months 2–8)
- Reference data sync — counterparties, instruments, hierarchies.
- Trade replication — trade ingestion approach, late-trade booking handling.
- Daily risk reconciliation — VaR delta vs source, attribution of differences. Cite Kinetix's reproducibility (ADR-0018, ADR-0029) as the foundation that makes reconciliation possible.
- Tolerance criteria for go/no-go (e.g. <5 bps VaR drift sustained over 20 business days).

### 4. Cutover phase (months 8–10)
- T-30 / T-7 / T-1 checklist.
- EOD official run cutover — pair with ADR-0019 promotion governance.
- Audit chain bootstrap — initial hash anchor.
- Rollback plan — how to fall back to source for N business days if cutover fails.

### 5. Decommissioning phase (months 10–18)
- Read-only window on source.
- Historical data extraction for regulatory retention.
- Licence termination preconditions.
- Final attestation pack.

### 6. Data migration design
For each domain (trades, positions, market data, curves, vol surfaces, limits, audit):
- Source representation → Kinetix representation.
- ETL approach (one-shot, incremental, CDC).
- Reconciliation strategy.
- Owner.

### 7. People and process
- Trader workflow continuity — pair with `/trader` perspective.
- Risk-management workflow — limits, EOD, reports.
- Operations — booking flows, breaks, support.
- Training plan and adoption metrics.

### 8. Risk register
Top 10 migration risks with owner, mitigation, contingency. Include source-system-specific risks (e.g. Murex MXML trade extracts truncating long custom fields).

### 9. Cost & timeline shape
Indicative milestones (don't invent precise numbers — use ranges). Headcount profile. Vendor licence overlap cost.

## Step 3 — Tone

- The reader is sceptical of vendor optimism. Lead with the hard problems, then explain how Kinetix's architecture makes them tractable.
- Cite Kinetix ADRs and specs that de-risk migration: reproducibility (0018), audit chain (0017), discovery-valuation contract (0024, 0029), governance (0019).
- Do not promise things Kinetix doesn't do today. The "Known gaps for migration" section is a credibility multiplier.

## Step 4 — Output

Write to `docs/migration/from-<source>.md` (kebab-case). Create `docs/migration/` if needed. Print the file path and a one-line summary.

## Reminders

- A real migration is mostly data reconciliation and people, not technology. Reflect that weighting in the document length.
- Reuse `/regulatory-map` output to populate the capability matrix instead of duplicating it.
- Pair this skill with `/compliance-officer` and `/architect` for second-opinion review before sending externally.

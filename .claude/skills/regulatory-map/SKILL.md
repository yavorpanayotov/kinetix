---
name: regulatory-map
description: Map Kinetix features to regulatory line items — FRTB SA/IMA, SR 11-7 model risk, BCBS 239 risk data aggregation, EMIR, MiFID II, IFRS 9. Produces a coverage matrix that signals domain credibility to FI buyers. Invoke with /regulatory-map optionally followed by a regime (e.g. "FRTB", "SR 11-7").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash, WebFetch
---

# Regulatory Coverage Map

Produce a **regulatory coverage matrix** that maps Kinetix capabilities to the specific line items of each regime. The audience is a Head of Risk, model validation lead, or regulatory liaison evaluating whether Kinetix covers their obligations.

This document is a signal of domain depth. Every row must be backed by a file path, ADR, or spec — if a capability is partial, say so.

## Step 1 — Pick regimes

If the user named a regime, scope to that. Otherwise cover:

- **FRTB Standardised Approach** (BCBS 352 / d457) — sensitivities-based method, default risk charge, residual risk add-on.
- **FRTB Internal Models Approach** — expected shortfall, NMRFs, P&L attribution, backtesting.
- **SR 11-7 / SS 1/23** — model risk management lifecycle: development, validation, ongoing monitoring, governance.
- **BCBS 239** — risk data aggregation and reporting principles (1–14).
- **EMIR** — trade reporting, risk mitigation, collateral.
- **MiFID II** — best execution, transaction reporting, algo trading controls.
- **IFRS 9** — ECL / staging (only the market-risk-adjacent parts).
- **CFTC / Dodd-Frank** — large trader reporting, swap reporting.

For each regime produce a separate section.

## Step 2 — For each regime, produce a table

Columns: `Requirement · Kinetix capability · Evidence · Coverage`

- **Requirement** — the specific clause or principle (e.g. *FRTB SA — Delta risk capital charge*, *BCBS 239 §3.4 Accuracy*, *SR 11-7 §V Model Validation*).
- **Kinetix capability** — what Kinetix does that satisfies (or partially satisfies) it.
- **Evidence** — file path, ADR number, spec name, code location. Concrete.
- **Coverage** — one of: ✅ Covered · 🟡 Partial · ⚪ Not covered · N/A (out of scope).

Aim for 10–25 rows per regime. Do not pad. If a regime is mostly N/A for a market-risk platform, say so up front and only list the rows that apply.

## Step 3 — Gather evidence

Read in parallel:
- `docs/adr/` — index and key ADRs (0017 audit, 0018 reproducibility, 0019 EOD governance, 0023 hierarchical limits, 0036 AI copilot, etc.).
- `specs/` — `audit.allium`, `limits.allium`, `eod-close.allium`, `counterparty-risk.allium`, etc.
- Service code paths for any capability claimed (e.g. FRTB SA capital — grep for "FRTB" or "sensitivities" under services/).

If a regime's evidence is thin, prefer a 🟡 row with a "TODO: confirm" note over inventing coverage.

## Step 4 — Honest gaps section

After the per-regime tables, add a **"Known gaps"** section listing every ⚪ Not covered row across all regimes, grouped by theme (e.g. "Trade reporting infrastructure", "Backtesting framework"). This honesty section is a credibility multiplier — it tells the buyer you understand the regime well enough to know what is missing.

## Step 5 — Output

Write to `docs/regulatory/coverage.md` (or `docs/regulatory/<regime>.md` if the user named a regime). Create `docs/regulatory/` if needed. Print the file path and a summary: total requirements, ✅ count, 🟡 count, ⚪ count per regime.

## Reminders

- Every ✅ must cite a file path or ADR. No exceptions.
- If unsure whether a requirement is met, mark it 🟡 with the open question stated.
- Use WebFetch sparingly to confirm the precise clause number — official BCBS / FCA / ESMA pages are authoritative; secondary sources are not.
- This document is your most defensible credibility artefact in front of a CRO. Do not overclaim.

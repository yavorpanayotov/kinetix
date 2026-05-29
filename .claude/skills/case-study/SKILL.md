---
name: case-study
description: Generate a polished, buyer-readable case study of Kinetix from the codebase — problem statement, architecture summary, key decisions, results. The artefact for prospect calls and BD outreach. Invoke with /case-study optionally followed by an angle (e.g. "FRTB capital", "AI copilot", "audit trail").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash, Task
---

# Kinetix Case Study

You are producing a **buyer-facing case study** of the Kinetix platform — not a developer README. The reader is a Head of Risk, CTO, or procurement lead at a tier-1 / tier-2 financial institution evaluating a market risk system rewrite. They are skim-reading. They want to see scope, sophistication, decisions, and evidence within 5 minutes.

If the user passed an angle (`/case-study FRTB capital`), foreground that angle and treat the rest as context. Otherwise produce the general case study.

## Step 1 — Gather raw material

Read in parallel:

- `README.md` — the public-facing scope and architecture summary.
- `docs/adr/README.md` and 4–6 ADRs that pattern-match the angle (e.g. for "audit trail" → ADR-0017, ADR-0019; for "FRTB" → look in services + capital code).
- `specs/README.md` to find the spec(s) that match the angle.
- `git log --oneline -50` for recent direction.
- Service-level READMEs (`*/README.md`) for the services involved.

If you cannot find an angle-relevant ADR or spec, say so — do not invent one.

## Step 2 — Structure the document

Produce the case study in this order. Keep prose dense; use tables for facts.

### 1. Executive summary (≤120 words)
One paragraph: what Kinetix is, what business problem it solves, what makes it credible. No marketing fluff — every claim must be backed by something in the repo.

### 2. The problem
Why an institutional desk would replace its incumbent risk system. Cite specific industry frictions: vendor lock-in, FRTB IMA approval, model governance overhead, latency, AI surface.

### 3. The Kinetix approach
3–5 distinct positioning claims. Each one names the ADR or spec that backs it. Example: *"Risk runs are bit-for-bit replayable from manifests (ADR-0018, ADR-0029)."*

### 4. Architecture summary
A 6–10 line ASCII diagram OR a Mermaid block. Then a one-paragraph walk-through. Reuse the README diagram if appropriate.

### 5. Key engineering decisions
A table: Decision · Why · ADR. Pick 5–8 of the most distinctive (hash-chained audit, two-phase discovery/valuation, hierarchical limits, regime-adaptive params, EOD promotion, citation-enforced AI, etc.).

### 6. Quant & risk methodology coverage
A short table mapping capability → method → location (e.g. *VaR · Historical / Parametric / Monte Carlo · `risk-engine/src/kinetix_risk/var.py`*). Pull the rows from the README "Quant & risk methodology" section.

### 7. Compliance & governance posture
3–4 bullets: model governance, four-eyes EOD promotion, hash-chained audit, regulatory mapping. Cite ADRs and the regulatory-map doc if present.

### 8. AI-assisted development story
A short section (this is a differentiator — do not skip it). What was generated, what was specified, what was reviewed. Cite the Allium pipeline (`/distill`, `/weed`, `/propagate`), `docs/ai-impact-report.md` if present, and ADR-0036 for the runtime AI surface.

### 9. By the numbers
A compact table pulled from the README "At a glance" — services, tests, ADRs, specs, topics, migrations. These numbers are evidence.

### 10. What's next
2–3 bullets on direction. Pull from `bd ready` and any open ADRs labelled `accepted-pending`.

## Step 3 — Tone

- Confident, not boastful. State facts; let the buyer infer maturity.
- No hedging language ("we are working on", "planned"). Either it's in the repo or it's in the "what's next" section.
- No emojis. No exclamation marks.
- British English (the user is UK-based).

## Step 4 — Output

Default: write to `docs/case-study.md`, overwriting if it exists. Print the file path and a one-line summary back to the user. If the user passed an angle, suffix the filename: `docs/case-study-<angle>.md`.

## Reminders

- The buyer is not impressed by code snippets. They are impressed by **decisions**, **trade-offs**, and **evidence** (ADRs, specs, numbers).
- If a claim cannot be backed by a file path, ADR number, or spec, cut it.
- Cross-link to ADRs and specs by relative path so the reader can click through.

---
name: pitch-deck
description: Generate a 10-slide pitch-deck outline (Markdown) for a Kinetix prospect call — targeting a CTO, Head of Risk, or procurement lead at a financial institution. Invoke with /pitch-deck optionally followed by audience hint (e.g. "buy-side", "tier-2 bank", "CRO").
user-invocable: true
allowed-tools: Read, Glob, Grep, Bash
---

# Kinetix Pitch Deck

Produce a **10-slide deck outline** in Markdown that can be rendered with reveal.js / Marp / Slidev, or read as-is. The audience is a senior risk or technology decision-maker considering replacing an incumbent risk platform.

If the user passed an audience hint, tilt the deck toward that audience:
- **buy-side** — emphasise speed of iteration, AI copilot, low TCO.
- **sell-side / bank** — emphasise FRTB, model governance, audit chain, regulatory posture.
- **CRO** — emphasise risk methodology depth, regime adaptation, stress testing.
- **CTO** — emphasise architecture, observability, test coverage, deploy story.

## Step 1 — Gather material

Read `README.md`, the ADR index `docs/adr/README.md`, and `specs/README.md`. Skim screenshots in `docs/screenshots/` if present.

Pull live numbers (tests, ADRs, specs, services) from the README "At a glance" block. Never hard-code stale numbers — re-read.

## Step 2 — Slide structure

Produce 10 slides. One H1 per slide, then bulleted speaker notes underneath each slide block. Use `---` between slides (reveal.js / Marp convention).

### Slide 1 — Title
`# Kinetix` · subtitle: "Institutional market-risk platform". One line of positioning.

### Slide 2 — The problem
Why incumbent risk systems are painful: vendor lock-in, slow change cycles, FRTB readiness gap, opaque models, no AI surface. 3 bullets max.

### Slide 3 — Kinetix in one sentence
The positioning claim, then the supporting clause. e.g. *"Polyglot microservices risk platform with citation-enforced AI on top of every risk number."*

### Slide 4 — Architecture at a glance
Embed (or reference) the ASCII / Mermaid diagram from `README.md`. Speaker note: name the 3–4 boundaries that matter (gateway, risk orchestrator, risk engine, Kafka).

### Slide 5 — Risk methodology
Compact table from the README "Quant & risk methodology" section. 5–7 rows. Speaker note: emphasise that VaR has 3 methods and Monte Carlo is reproducible.

### Slide 6 — Governance & audit
3 bullets: hash-chained audit (ADR-0017), four-eyes EOD promotion (ADR-0019), run reproducibility (ADR-0018). One supporting claim each.

### Slide 7 — AI copilot
What it is, what it can do, what it deliberately **cannot** do. Cite ADR-0036. Emphasise: citations enforced, never books or recommends, MCP-backed.

### Slide 8 — AI-assisted delivery
The differentiator slide. Spec-driven development with Allium, agentic skills, evolution report. Pull a headline metric from `docs/ai-impact-report.md` if it exists ("X% of code originated from spec → propagation").

### Slide 9 — By the numbers
Table from README At-a-glance: services, tests, ADRs, specs, migrations, topics. This is the credibility slide.

### Slide 10 — Next steps
3 bullets — what an engagement looks like: scoping workshop, parallel run, cutover. Phone / email CTA.

## Step 3 — Speaker notes

Under each slide, add a `> Notes:` block of 2–4 bullets in plain English — what to say when this slide is on screen. Keep notes terse; the slide is the prop, not the script.

## Step 4 — Output

Default location: `docs/pitch/kinetix-deck.md`. If audience hint was passed, suffix: `docs/pitch/kinetix-deck-<audience>.md`. Create the `docs/pitch/` directory if needed. Print the file path and a one-line summary.

## Reminders

- 10 slides max. If a topic doesn't fit, cut it.
- Every numeric claim must be re-pulled from the README — never trust prior session memory for headline stats.
- No emojis, no exclamation marks. Confident, terse, British English.
- The deck is the pitch, not the case study — see `/case-study` for the long-form artefact.

---
title: "Specs Are the New Source Code: How One Engineer Built a 450k-Line Regulated Risk Platform with AI"
speaker: "Yavor Panayotov"
duration: "45m"
audience: "AI-engineering practitioners & engineering leaders (fintech-adjacent)"
status: draft
---

# Specs Are the New Source Code (Long-form)

> **One takeaway (the slide they remember):**
> When behaviour lives in *executable specs*, AI stops being autocomplete and becomes an implementer — and a single engineer can keep a 13-service, 453k-line *regulated* platform aligned across Kotlin, Python, and TypeScript.

**Audience:** engineers and eng leaders who want the full mechanics of AI-assisted delivery past the demo stage. Quants/FI buyers will recognise the domain, but the talk is about *method*. The extra 15 minutes over the standard cut buys: a second, deeper demo (cross-service drift), a real architecture walk, and a candid "economics + failure log" section.

**Arc:** hook → why it's hard → the method → the architecture it produced → demo 1 (the loop) → demo 2 (cross-service drift) → economics & failure log → takeaway → extended Q&A.

---

## Segment 0 — Title & promise (0:00–0:45) · 0.75 min

- **Goal:** Set expectations so nobody waits for the wrong talk.
- **Speaker notes:**
  - "Not a 'look how clever my prompt is' talk. A workflow that let one contractor build something that normally takes a team a year."
  - "Everything is in a real repo with real commit history. No staged screenshots — and I'll run it live, twice."
- **Visual:** Title + one-line takeaway, greyed until the end.
- **Time:** 0.75 min

---

## Segment 1 — Hook: the uncomfortable number (0:45–3:00) · 2.25 min

- **Goal:** Land a concrete, slightly unbelievable claim.
- **Speaker notes:**
  - Lead with the artefact: "Kinetix — a market-risk platform. 13 services. Trade booking, VaR, Greeks, stress testing, a hash-chained audit trail, regulatory submissions, an AI copilot."
  - Then: "453,407 lines of production code. 25 executable specifications. One person."
  - Honest caveat *immediately*: "AI wrote most of the code. It did **not** make the architecture decisions, validate the quant maths, or sign off the regulatory logic. I'll show you exactly where the line is — twice."
  - "The interesting question isn't 'can AI write code'. It's: *how do you keep 453k lines across three languages from drifting into nonsense?*"
- **Visual:** Big-number table (lines / services / specs / commits / engineers), footer caveat.
- **Time:** 2.25 min

---

## Segment 2 — Why this is hard (4:00 mark) (3:00–7:30) · 4.5 min

- **Goal:** Make the audience feel why "just let the AI build it" fails for this class of system.
- **Speaker notes:**
  - Risk software is *behaviour-dense*: the hard part is rules — "reject a trade when the limit is breached", "every audit record chains to the previous hash", "VaR parameters adapt when the regime flips to crisis".
  - Three failure modes of naive AI-assisted builds:
    1. **Drift** — change one service; the contract, proto, and test elsewhere silently disagree. ×13.
    2. **Plausible-but-wrong** — AI writes a VaR function that *looks* right and is subtly, dangerously incorrect.
    3. **No source of truth** — when behaviour lives only in code + your memory, there's nothing to check the AI against. You become bottleneck *and* single point of failure.
  - Long-form extra: tell a 30-second war story of a real drift you hit before the spec discipline (a proto field that diverged from the service). Concrete > abstract.
  - "So the problem wasn't 'generate code faster'. It was: *give the AI — and me — a single, checkable definition of correct behaviour.*"
- **Visual:** Hub ("behaviour") with 4 spokes that must agree — spec, proto, service code, tests — red "drift" bolts between them.
- **Time:** 4.5 min

---

## Segment 3 — The method: specs as source of truth (7:30–14:00) · 6.5 min

- **Goal:** Introduce Allium and the inversion: spec first, code downstream — with enough depth that the audience could try it Monday.
- **Speaker notes:**
  - "CLAUDE.md has one rule that changes everything: *behaviour is defined in specs, not in code.*"
  - Walk a real spec (audit) line by line — entities, fields, rules, triggers. It reads like a design doc but is machine-checkable.
  - The three verbs, with a sentence each on *when* you reach for them:
    - **`/distill`** — reverse-engineer a spec *from* code. Use when code came first (most retrofits).
    - **`/weed`** — find divergences between spec and implementation. This is your drift CI.
    - **`/propagate`** — generate tests *from* the spec, so coverage is tied to the contract, not to memory.
  - Long-form extra: show *how a spec becomes multiple targets* — one `audit.allium` rule fans out to Kotlin, a proto, and a generated test. Emphasise the AI does the fan-out; you own the spec.
  - ADRs cite specs by line number (ADR-0031 → `counterparty-risk.allium:484`). Decisions anchored to executable text, not prose that rots.
  - The mental model, said plainly: "The spec is the source code. Everything else is a compilation target."
- **Visual:** Left: ~12 lines of `specs/audit.allium`. Right: fan-out arrows to `AuditHasher.kt`, generated tests, `audit.proto`, ADR-0017. Animate the fan-out.
- **Time:** 6.5 min

---

## Segment 4 — The architecture the method produced (14:00–20:00) · 6 min

- **Goal:** Prove the output is a *real* system and that good architecture was a human decision the AI executed.
- **Speaker notes:**
  - Tour 4–5 decisions worth bragging about (all ADRs), ~60–75s each:
    - **Discovery–Valuation two-phase contract (ADR-0029):** the Python risk engine is a *pure calculator* with zero outbound deps. Phase 1 it *declares* the market data it needs; phase 2 it's *handed* that data and computes. Reproducible, testable, decoupled — and a clean seam for the AI to work within.
    - **Hash-chained audit trail (ADR-0017):** tamper-evident, blockchain-style chain over every trade/risk/governance action; incremental verification.
    - **Run reproducibility manifests (ADR-0018):** every calc captures input/output digests + model version + seed → same inputs, same model = same answer. Exactly what a regulator asks.
    - **Regime-adaptive VaR (`specs/regime.allium`):** detects normal/elevated/crisis/recovery from realised vol, vol-of-vol, cross-asset correlation, credit spreads; VaR params and MC counts adapt automatically.
    - **AI Copilot (ADR-0036):** grounded LLM assistant over an in-process MCP server exposing *read-only* tools; citation verification + banned-phrase guards so it can't hallucinate a number.
  - "I made these calls — with help from agent personas: a 'quant', a 'trader', an 'SRE'. The AI then built them. Architecture is judgement; judgement stayed with me."
  - Be precise on infra: deployment is **docker-compose** (postgres, redis, Kafka in KRaft mode, Prometheus/Loki/Tempo) — not Kubernetes. Don't overclaim.
- **Visual:** C4-style container diagram of the 13 services + Kafka/Postgres/Redis, ADR badges on the 4–5 highlighted decisions. Generate via `/diagrams` beforehand.
- **Time:** 6 min

---

## Segment 5 — Demo 1: change a behaviour, watch it propagate (20:00–27:00) · 7 min

> See **Demo 1** below for setup, reveal, fallback.

- **Goal:** Show the loop closing in real time — edit spec → `/weed` → `/propagate` → implement → green.
- **Speaker notes:**
  - "Let me change a rule and show you what 'specs as source code' actually feels like."
  - Narrate every step; the audience should follow without reading code.
  - At `/weed`'s divergence: "*This* is the safety net. Spec and code disagree, and the tool told me before I shipped."
  - At green: "I didn't write that test. The spec did."
- **Visual:** Terminal, large font, spec file split alongside.
- **Time:** 7 min (demo 5, buffer 2)

---

## Segment 6 — Demo 2: cross-service drift, caught (27:00–32:00) · 5 min

> The payoff demo unique to the long-form cut. See **Demo 2** below.

- **Goal:** Show the *real* value — drift across a service boundary that hand-written tests in one module would never catch.
- **Speaker notes:**
  - "Demo 1 was one service. The actual nightmare is *13*. Here's the part that earns its keep."
  - Introduce a divergence at a boundary — a contract the spec owns but two services must agree on (e.g. a discovery-valuation market-data type, or an audit event field).
  - Run `/weed` across the spec → it reports the mismatch *between* the spec and the implementation site that forgot to change.
  - The line: "No single unit test was going to catch that. The spec spans the boundary, so the check does too."
  - Keep it tight — if rehearsal shows it's fragile, demote to a recorded clip and narrate.
- **Visual:** Two service files + the shared spec; `/weed` output highlighting the cross-boundary mismatch.
- **Time:** 5 min (demo 3.5, buffer 1.5)

---

## Segment 7 — Economics & the failure log (32:00–38:00) · 6 min

- **Goal:** Full transparency on cost and limits — the section skeptics came for, expanded.
- **Speaker notes:**
  - **The economics** (from the repo's own AI-impact report): 602 prompts, 126 sessions, ~4.7-min median iteration cadence over the captured window; 3,173 non-merge commits. "This was *driven*, not autopilot. The leverage is breadth-per-session, not magic."
  - **The failure log** — four honest gaps, with a concrete example each:
    1. **Quant correctness.** AI wrote VaR/Greeks that compiled and looked right; a human (and a `/quant` persona) had to validate. AI is *not* a model validator.
    2. **Regulatory nuance.** FRTB/SR 11-7 interpretation — AI drafts, human decides. 95%-right is a fail in regulated software.
    3. **Integration-test stability.** Real Kafka + Postgres via Testcontainers exposed flakiness AI's unit tests sailed past. Higher-level tests still catch what the model can't.
    4. **Architecture.** Every service boundary, every ADR — human judgement. The method makes AI a fast implementer, not an architect.
  - **Still open:** keeping the 25 specs *themselves* drift-free as the system grows; spec review is now my bottleneck — the right one to have, but a real one.
  - Long-form extra: show the "AI did / Human kept" split and dwell on making the human column look *valuable*, not residual. "The skill didn't disappear — it moved."
- **Visual:** Two-panel slide. Left: economics stat block. Right: the four-row failure log with one example per row.
- **Time:** 6 min

---

## Segment 8 — Takeaway & close (38:00–40:00) · 2 min

- **Goal:** Reveal the one-liner and give the transferable idea.
- **Speaker notes:**
  - "One thing to take: **the bottleneck moves up the stack.** AI didn't make me a faster typist; it made the *specification* the thing I spend my time on — and that's the right thing."
  - "You don't need Allium. You need *a single checkable definition of correct behaviour* both you and the AI are accountable to. Tests, types, a DSL — pick your poison. The discipline scales, not the tool."
  - Soft CTA: "I build this way as a contractor through JUXT. If you've got a regulated system drowning in drift, come find me."
- **Visual:** Un-greyed takeaway slide; repo/contact footer.
- **Time:** 2 min

---

## Segment 9 — Extended Q&A (40:00–45:00) · 5 min

See **Q&A prep** — long-form has room for 4–5 questions.

---

## Demo 1 — the propagate loop (Segment 5)

**Setup:** the spec loop is local — no full platform needed. (If you want to show it in the running app after: `/deploy`, then `/health`; UI at `https://kinetixrisk.ai`.) Three panes: the `.allium` spec, a terminal, the impl file. Pick a *one-sentence* rule — recommended a limits rule ("reject a trade when the position limit is exceeded" → add a soft-breach warning band). Rehearse once.

**Reveal:**
1. Edit one rule in `specs/limits.allium`.
2. `/weed` → reports spec declares behaviour the code doesn't enforce.
3. `/propagate` → generates a failing test whose name reads as a sentence.
4. Run tests → red. Implement minimal change → green.
- Landing line: **"I changed the spec. The test wrote itself. The code had nowhere to hide."**

**Fallback:** pre-recorded ~90s screencap (`<<LOOM_LINK_1>>`) + 4 static screenshots (spec diff → `/weed` → generated test → green run). If the network dies, walk the screenshots: "I ran this an hour ago — here's what you'd see." No apology.

---

## Demo 2 — cross-service drift (Segment 6)

**Setup:** pick a spec that owns a *boundary* contract — `specs/discovery-valuation.allium` (a market-data dependency type shared by orchestrator + risk-engine) or `specs/audit.allium` (an event field shared by producers + audit-service). Open the two implementation sites plus the spec.

**Reveal:**
1. Change the shared contract in the spec (add/rename a field on the boundary type).
2. `/weed` → reports the mismatch at the implementation site that didn't change.
3. Point out: a unit test scoped to one module would have stayed green and shipped the bug.
- Landing line: **"The spec spans the boundary, so the check does too. That's the thing 13 services actually need."**

**Fallback:** recorded ~90s clip (`<<LOOM_LINK_2>>`) + 3 screenshots (spec diff → `/weed` cross-boundary output → the un-updated file). This demo is more fragile than Demo 1 — if rehearsal is shaky, run it from the clip and narrate; the point survives.

---

## Q&A prep (anticipated questions)

1. **"How is this different from good tests / TDD?"** TDD with the test *generated from a higher-level contract*, so the spec — not the test — is canonical, and `/weed` actively reports spec↔code drift across all 13 services. Hand-written tests don't do cross-service drift detection.

2. **"Does this scale beyond one person / greenfield?"** *More* valuable with a team — a shared spec is a coordination contract and `/weed` becomes drift CI. `/distill` retrofits specs onto existing code. Open risk: spec review as bottleneck — a process problem, not a tooling wall.

3. **"How do you trust AI code in a *regulated* domain?"** You constrain it. Run-reproducibility manifests (ADR-0018) and the hash-chained audit trail (ADR-0017) make every calculation verifiable and every action tamper-evident regardless of author. Quant/regulatory correctness is explicitly human-validated.

4. **"Product copilot vs your dev workflow — same thing?"** Two different things. Dev workflow (Allium + agents) is *how it was built*. Runtime copilot (ADR-0036) is a *product feature*: grounded assistant over a read-only MCP server with citation verification. Happy to go deep on either.

5. **"How does this compare to Copilot / Cursor / [vendor]?"** Those make typing faster; this changes *what you're accountable for* — the spec. Use Cursor as the editor; the differentiator is the spec-as-source-of-truth discipline and the weed/propagate loop, not the autocomplete underneath.

6. **"Isn't 453k lines just AI bloat?"** Some, fair. But it's 13 services with real infra (Kafka, Postgres, observability), 102 migrations, and full test layers — line count is dominated by breadth, not verbosity. The honest metric is **25 specs** — the surface I maintain.

7. **"What did the AI genuinely fail at?"** *(point back to the failure log)* Quant correctness, regulatory interpretation, integration-test stability, architecture. I'd rather over-disclose than have you discover it.

8. **"How much did it cost — tokens / time?"** From the repo's AI-impact report: 602 prompts, 126 sessions, ~4.7-min median cadence over the captured window. A real engineering effort, not a weekend; the speed-up is breadth covered per session.

9. **"Why Allium specifically?"** *(adjacent — redirect)* It fit, but the claim is tool-agnostic: you need *a* checkable behaviour definition. Not here to sell a DSL.

10. **"How do you keep the specs themselves from drifting / who reviews them?"** That's the honest open problem — spec review is now my bottleneck. With a team it'd be a PR-style review gate on specs, with `/weed` in CI as the backstop. The drift moved from code to spec, which is a smaller, higher-leverage surface to police.

11. **"Can I see it / is it open?"** Portfolio platform — find me after; I'll walk the repo and commit history live.

---

## Speaker briefing — 5 things to remember on stage

1. **The failure log (Segment 7) is your strongest asset, not a weakness.** You have 6 minutes here in the long cut — use them. Specific disclosure is what makes the 453k number credible.
2. **The one stat that has to land: 25 specs.** Not 453k (invites "bloat" cynics) — *25 specs* proves the method. Say it in the hook, in Demo 1, and at the close.
3. **Two demos means two chances to fail — rehearse both, and pre-decide your live/recorded call for Demo 2.** Demo 2 is higher-risk and higher-payoff; if rehearsal is shaky, run it from the clip without ceremony. Never debug live.
4. **Don't oversell autonomy.** Never "the AI built it while I slept." 602 driven prompts, tight iteration. The story is *leverage*, not *autopilot* — overclaiming gets you torn apart in the extended Q&A.
5. **Protect the arc against rabbit holes.** The extra runtime invites VaR-maths and vendor tangents. Redirect: "happy to go deep offline — this talk is about the *method*." You have one takeaway to deliver.

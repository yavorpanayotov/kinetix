---
title: "Specs Are the New Source Code: How One Engineer Built a 450k-Line Regulated Risk Platform with AI"
speaker: "Yavor Panayotov"
duration: "30m"
audience: "AI-engineering practitioners & engineering leaders (fintech-adjacent)"
status: draft
---

# Specs Are the New Source Code

> **One takeaway (the slide they remember):**
> When behaviour lives in *executable specs*, AI stops being autocomplete and becomes an implementer — and a single engineer can keep a 13-service, 453k-line *regulated* platform aligned across Kotlin, Python, and TypeScript.

**Audience:** engineers and eng leaders curious about how AI-assisted delivery actually works past the demo stage. Quants/FI buyers will recognise the domain, but the talk is about *method*, not VaR maths.

**Arc:** hook (the scary claim) → why building regulated risk software is hard → the method that made it tractable (specs as source of truth, AI as implementer) → live proof → what AI did *not* do → takeaway.

---

## Segment 0 — Title & promise (0:00–0:30) · 0.5 min

- **Goal:** Set expectations in one sentence so nobody waits for the wrong talk.
- **Speaker notes:**
  - "This is not a 'look how clever my prompt is' talk. It's about a workflow that let one contractor build something that normally takes a team a year."
  - "Everything I show is in a real repo, with real commit history. No staged screenshots."
- **Visual:** Title slide + the one-line takeaway, greyed until the end.
- **Time:** 0.5 min

---

## Segment 1 — Hook: the uncomfortable number (0:30–2:30) · 2 min

- **Goal:** Land a concrete, slightly unbelievable claim that earns the next 25 minutes.
- **Speaker notes:**
  - Open on the artefact, not the AI: "This is Kinetix — a market-risk platform. 13 services. Trade booking, VaR, Greeks, stress testing, a hash-chained audit trail, regulatory submissions, an AI copilot."
  - Then the number: "453,407 lines of production code. 25 executable specifications. One person."
  - The honest caveat *immediately* (credibility): "AI wrote most of the code. It did **not** make the architecture decisions, validate the quant maths, or sign off the regulatory logic. I'll show you exactly where the line is."
  - "The interesting question isn't 'can AI write code' — yes, obviously. It's: *how do you keep 453k lines across three languages from drifting into nonsense?*"
- **Visual:** One slide, big numbers:
  | | |
  |---|---|
  | Production lines (Kotlin+Py+TS) | **453,407** |
  | Backend services | **13** |
  | Executable specs (Allium) | **25** (14,089 lines) |
  | Non-merge commits | **3,173** |
  | Engineers | **1** |
  *Footer, small:* "AI-assisted. Architecture, quant correctness & regulatory sign-off stayed human."
- **Time:** 2 min

---

## Segment 2 — Why this is hard (problem framing) (2:30–6:30) · 4 min

- **Goal:** Make the audience feel why "just let the AI build it" fails for this class of system.
- **Speaker notes:**
  - Risk software is *behaviour-dense*, not feature-dense. The hard part isn't screens — it's rules: "reject a trade when the position limit is breached", "every audit record chains to the previous hash", "VaR parameters adapt when the market regime flips to crisis".
  - Three failure modes of naive AI-assisted builds:
    1. **Drift.** You prompt a change in one service; the contract in another service, the proto, and the test silently disagree. Multiply by 13 services.
    2. **Plausible-but-wrong.** AI happily writes a VaR function that *looks* right and is subtly, dangerously incorrect.
    3. **No source of truth.** When the behaviour only lives in code + your memory, there's nothing to check the AI against. The reviewer (you) becomes the bottleneck *and* the single point of failure.
  - "So the problem to solve wasn't 'generate code faster'. It was: *give the AI — and me — a single, checkable definition of correct behaviour.*"
- **Visual:** Diagram — a hub ("behaviour") with 4 spokes that must agree: spec, proto contract, service code, tests. Red lightning bolts labelled "drift" between them.
- **Time:** 4 min

---

## Segment 3 — The method: specs as source of truth (6:30–11:30) · 5 min

- **Goal:** Introduce Allium specs and the inversion: spec first, code is downstream.
- **Speaker notes:**
  - "The CLAUDE.md in this repo has one rule that changes everything: *behaviour is defined in specs, not in code.*"
  - Show a real spec snippet (audit). It reads like a design doc but is machine-checkable — entities, fields, rules, triggers.
  - The workflow is three verbs:
    - **`/distill`** — reverse-engineer a spec *from* existing code (when code came first).
    - **`/weed`** — find divergences between spec and implementation; resolve by fixing one side.
    - **`/propagate`** — generate tests *from* the spec, so coverage is tied to the contract, not to whatever the author remembered.
  - The mental model: "The spec is the source code. Kotlin, Python, tests, protos are *compilation targets* the AI produces and keeps in sync."
  - ADRs cite specs *by line number* (e.g. ADR-0031 → `counterparty-risk.allium:484`). Decisions are anchored to executable text, not prose that rots.
- **Visual:** Left: ~12 lines of `specs/audit.allium` (AuditEvent: `record_hash`, `previous_hash`, `sequence_number`, rule: "each record hashes the previous"). Right: arrows fanning out to `AuditHasher.kt`, generated tests, `audit.proto`, ADR-0017.
- **Time:** 5 min

---

## Segment 4 — The architecture the method produced (11:30–15:30) · 4 min

- **Goal:** Prove the output is a *real* system, not a toy — and that good architecture was a human decision the AI executed.
- **Speaker notes:**
  - Quick tour, 30 seconds each, of 3–4 decisions worth bragging about (all are ADRs):
    - **Discovery–Valuation two-phase contract (ADR-0029):** the Python risk engine is a *pure calculator* with zero outbound dependencies. Phase 1 it *declares* what market data it needs; phase 2 it's *handed* that data and computes. Reproducible, testable, decoupled.
    - **Hash-chained audit trail (ADR-0017):** tamper-evident, blockchain-style chain over every trade/risk/governance action; incremental verification.
    - **Run reproducibility manifests (ADR-0018):** every calculation captures input/output digests + model version + seed → same inputs, same model = same answer. That's what a regulator asks for.
    - **AI Copilot (ADR-0036):** grounded LLM assistant over an in-process MCP server exposing *read-only* tools; citation verification + banned-phrase guards so it can't hallucinate a number.
  - The point: "I made these calls — with help from agent personas (a 'quant', a 'trader', an 'SRE'). The AI then built them. Architecture is judgement; judgement stayed with me."
- **Visual:** C4-ish container diagram of the 13 services + Kafka/Postgres/Redis. Highlight the 4 ADR decisions with badges. (Generate via `/diagrams` beforehand.)
- **Time:** 4 min

---

## Segment 5 — Live proof: change a behaviour, watch it propagate (15:30–22:30) · 7 min

> This is the heart of the talk. See **Demo** section below for setup, reveal, and fallback.

- **Goal:** Show the loop closing in real time — edit spec → weed finds the gap → propagate writes the test → implement → green.
- **Speaker notes:**
  - "Let me change a rule and show you what 'specs as source code' actually feels like."
  - Narrate each step out loud — the audience should be able to follow without reading code.
  - When `/weed` flags the divergence: "*This* is the safety net. The spec and the code disagree, and the tool tells me before I ship it."
  - When tests go green: "I didn't write that test. The spec did."
- **Visual:** Terminal, large font. Split with the spec file on one side.
- **Time:** 7 min (demo 5, narration buffer 2)

---

## Segment 6 — What AI got wrong / what's still open (22:30–25:30) · 3 min

- **Goal:** Earn lasting credibility by being specific about the limits. This is the slide skeptics came for.
- **Speaker notes:**
  - Be concrete, not performatively humble. Four honest gaps from the build:
    1. **Quant correctness.** AI wrote VaR/Greeks code that compiled and looked right; correctness needed a human (and a `/quant` persona) to validate. AI is *not* a model validator.
    2. **Regulatory nuance.** FRTB/SR 11-7 interpretation — AI drafts, human decides. Getting a rule 95% right in regulated software is a fail.
    3. **Integration-test stability.** Real Kafka + Postgres via Testcontainers exposed flakiness AI's unit tests sailed past. Higher-level tests still catch what the model can't.
    4. **Architecture.** Every service boundary, every ADR — human judgement. The method makes AI a fast implementer, not an architect.
  - The cost honesty: 602 prompts, 126 sessions over the captured window; ~4.7-min median iteration cadence. This was *driven*, not autopilot.
  - Still open: keeping 25 specs themselves drift-free as the system grows; spec review is now *my* bottleneck — the right one to have.
- **Visual:** Two columns — "AI did" (boilerplate, cross-cutting rollouts, test generation, 11 services instrumented in one session) vs "Human kept" (architecture, quant validation, regulatory sign-off, spec authorship). Make the right column look *valuable*, not residual.
- **Time:** 3 min

---

## Segment 7 — Takeaway & close (25:30–27:00) · 1.5 min

- **Goal:** Reveal the one-liner and give them the transferable idea.
- **Speaker notes:**
  - "If you take one thing: **the bottleneck moves up the stack.** AI didn't make me a faster typist; it made the *specification* the thing I spend my time on — and that's the right thing to spend time on."
  - "You don't need Allium. You need *a single checkable definition of correct behaviour* that both you and the AI are accountable to. Tests, types, a DSL — pick your poison. The discipline is what scales, not the tool."
  - Soft CTA: "I build this way as a contractor through JUXT. If you've got a regulated system that's drowning in drift, come find me."
- **Visual:** The takeaway slide (un-greyed). Repo/contact footer.
- **Time:** 1.5 min

---

## Segment 8 — Q&A (27:00–30:00) · 3 min

See **Q&A prep** below.

---

## Demo — the propagate loop (Segment 5)

**Setup (do before you walk on stage):**
- Platform need not be fully running for the *spec* loop — the spec→weed→propagate→test cycle is local. But if you want to show it live in the app afterwards: `/deploy` (full redeploy) and `/health` to confirm green; UI at `https://kinetixrisk.ai`.
- Open three panes: (1) the chosen `.allium` spec, (2) a terminal, (3) the implementation file.
- Pick a *small, legible* rule. Recommended: a limits rule (e.g. "reject a trade when the position limit is exceeded" → tighten a threshold or add a warning band) — it's a one-sentence behaviour the audience grasps instantly.
- Pre-run the loop once in rehearsal so you know the exact output and timing.

**The reveal (the 5–20 lines that make the point):**
1. Edit one rule in `specs/limits.allium` (e.g. add a soft-breach warning band).
2. Run `/weed` → it reports: spec now declares behaviour the code doesn't enforce.
3. Run `/propagate` → it generates a failing test that encodes the new rule (show the test name reading as a sentence: *"warns when a trade pushes utilisation past the soft-breach band"*).
4. Run the module's tests → red.
5. Implement the minimal change in the service → tests green.
- The line that lands: **"I changed the spec. The test wrote itself. The code had nowhere to hide."**

**Fallback (demos fail ~50% of the time):**
- A pre-recorded ~90s screencap (Loom placeholder: `<<LOOM_LINK>>`) of the exact same loop.
- Backup static slides: 4 screenshots (spec diff → `/weed` output → generated test → green run). Have these in the deck regardless, behind a "if the gods are unkind" hidden slide.
- If the network dies entirely: walk the 4 screenshots and say "I ran this an hour ago in the hotel; here's what you'd see." Honesty plays well.

---

## Q&A prep (anticipated questions)

1. **"How is this different from just writing good tests / TDD?"**
   It's TDD with the test *generated from a higher-level contract*, so the spec — not the test — is canonical. Tests can drift from intent; here intent is the artefact, and `/weed` actively reports when code and spec disagree across all 13 services, which hand-written tests don't do for you.

2. **"Does this scale beyond one person / a greenfield repo?"**
   The method is *more* valuable with a team — a shared spec is a coordination contract, and `/weed` becomes drift CI. Greenfield made it cleaner, but `/distill` exists precisely to retrofit specs onto existing code. The open risk is spec review becoming a bottleneck; that's a human-process problem, not a tooling wall.

3. **"How do you trust AI-written code in a *regulated* domain?"**
   You don't trust it — you constrain it. Run reproducibility manifests (ADR-0018) and the hash-chained audit trail (ADR-0017) mean every calculation is verifiable and every action is tamper-evident, regardless of who or what wrote the code. And quant/regulatory correctness is explicitly human-validated — that's the gap slide.

4. **"What's the AI copilot inside the product — is that the same thing as your dev workflow?"**
   No, two different things. The *dev* workflow (Allium + agents) is how it was built. The *runtime* copilot (ADR-0036) is a product feature: a grounded assistant over a read-only MCP server with citation verification. Happy to go deeper on either offline.

5. **"How does this compare to Copilot / Cursor / [vendor]?"**
   Those make the typing faster. This changes *what you're accountable for* — the spec. You can absolutely use Cursor as the editor; the differentiator is the spec-as-source-of-truth discipline and the weed/propagate loop on top, not the autocomplete engine underneath.

6. **"Isn't 453k lines just AI bloat? Would a human have written less?"**
   Probably some, yes — and a fair criticism. But it's 13 services with real infra (Kafka, Postgres, observability), 102 migrations, and full test layers; the line count is dominated by breadth, not verbosity. The honest metric is the spec count (25) — that's the surface I actually maintain.

7. **"What did the AI genuinely fail at?"** *(redirect to the gap slide)*
   Quant correctness, regulatory interpretation, integration-test stability, and all architecture. See the previous slide — I'd rather over-disclose this than have you discover it.

8. **"How much did this cost in tokens / time?"**
   Measured in the repo's own AI-impact report: 602 prompts, 126 sessions, ~4.7-min median cadence over the captured window. It's a real engineering effort, not a weekend — the speed-up is in breadth covered per session, not magic.

9. **"Why Allium specifically?"** *(adjacent — redirect)*
   Allium happened to fit, but the talk's claim is tool-agnostic: you need *a* checkable behaviour definition. I'm not here to sell a DSL.

10. **"Can I see it / is it open?"**
    It's a portfolio platform — come find me after, I'll walk you through the repo and the commit history live.

---

## Speaker briefing — 5 things to remember on stage

1. **The honesty slide (Segment 6) is your strongest asset, not a weakness.** Slow down there. Skeptics decide whether to believe you in those 3 minutes — being specific about what AI got wrong is what makes the 453k number credible.
2. **The one stat that has to land: 25 specs.** Not 453k lines (that invites "bloat" cynics) — *25 specs* is the number that proves the method. Say it twice: in the hook and in the close.
3. **Pace the demo, don't race it.** Narrate every command before you hit enter. If it fails, switch to the screenshots *without apology* — "here's the same run from this morning." Never debug live; you'll lose the room.
4. **Don't oversell autonomy.** Never say "the AI built it while I slept." It was driven — 602 prompts, tight iteration. The story is *leverage*, not *autopilot*. Overclaiming here gets you torn apart in Q&A.
5. **Resist the rabbit holes.** People will pull you into VaR maths or "which model do you use". Redirect: "happy to go deep offline — the talk is about the *method*." Protect the arc; you have one takeaway to deliver.

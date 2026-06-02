---
title: "Specs Are the New Source Code: A 450k-Line Regulated Platform, One Engineer, AI"
speaker: "Yavor Panayotov"
duration: "15m"
audience: "AI-engineering practitioners & engineering leaders (fintech-adjacent)"
status: draft
---

# Specs Are the New Source Code (Lightning)

> **One takeaway (the slide they remember):**
> When behaviour lives in *executable specs*, AI stops being autocomplete and becomes an implementer — and a single engineer can keep a 13-service, 453k-line *regulated* platform aligned across Kotlin, Python, and TypeScript.

**Audience:** engineers and eng leaders who want to know how AI-assisted delivery works past the demo stage. This is about *method*, not VaR maths.

**Lightning discipline:** one idea, one demo, one honest caveat. Cut everything that isn't load-bearing. No architecture grand tour — name-drop and move.

---

## Segment 1 — Hook + problem, fused (0:00–2:30) · 2.5 min

- **Goal:** Land the unbelievable number *and* the reason it's hard, in one breath.
- **Speaker notes:**
  - "Kinetix — a market-risk platform. 13 services. VaR, Greeks, a hash-chained audit trail, regulatory submissions, an AI copilot. **453,407 lines. 25 specs. One person.**"
  - Honest caveat immediately: "AI wrote most of the code. It did **not** do the architecture, the quant maths, or the regulatory sign-off. Hold me to that."
  - The hard part: "Risk software is behaviour-dense — it's rules, not screens. And AI's failure mode is *drift*: you change one service, the proto and the test in another silently disagree. Times 13."
  - The question: "How do you keep 453k lines across three languages from rotting into nonsense?"
- **Visual:** Big-number slide:
  | | |
  |---|---|
  | Production lines | **453,407** |
  | Backend services | **13** |
  | Executable specs | **25** |
  | Engineers | **1** |
  *Footer:* "AI-assisted. Architecture, quant & regulatory stayed human."
- **Time:** 2.5 min

---

## Segment 2 — The method (2:30–6:30) · 4 min

- **Goal:** Introduce the inversion — spec is canonical, code is downstream.
- **Speaker notes:**
  - "This repo's CLAUDE.md has one rule that changes everything: *behaviour is defined in specs, not in code.*"
  - Show a real spec snippet (audit). Reads like a design doc, but machine-checkable: entities, fields, rules.
  - Three verbs: **`/distill`** (code → spec), **`/weed`** (find spec↔code drift), **`/propagate`** (spec → tests).
  - Mental model: "The spec is the source code. Kotlin, Python, tests, protos are *compilation targets* the AI keeps in sync. ADRs even cite specs by line number."
- **Visual:** Left: ~10 lines of `specs/audit.allium` (hash-chain rule). Right: arrows to `AuditHasher.kt`, generated tests, `audit.proto`, ADR-0017.
- **Time:** 4 min

---

## Segment 3 — Live proof (6:30–11:00) · 4.5 min

> The heart of a lightning talk. See **Demo** below.

- **Goal:** Watch the loop close: edit spec → `/weed` finds the gap → `/propagate` writes the test → implement → green.
- **Speaker notes:**
  - "Let me change one rule and show you what this feels like."
  - Narrate every command before hitting enter.
  - When `/weed` flags it: "*That's* the safety net — spec and code disagree, and the tool told me first."
  - When tests pass: "I didn't write that test. The spec did."
- **Visual:** Terminal, large font, spec file split alongside.
- **Time:** 4.5 min (demo 3, narration buffer 1.5)

---

## Segment 4 — The honest caveat + takeaway (11:00–14:00) · 3 min

- **Goal:** Earn credibility, then deliver the one-liner.
- **Speaker notes:**
  - Don't skip this even under time pressure — it's the credibility. Fast and specific:
    - "AI wrote VaR code that *looked* right — correctness needed a human. Regulatory nuance: AI drafts, human decides. Integration tests on real Kafka/Postgres caught flakiness the unit tests sailed past. Architecture: every service boundary was my call."
  - Takeaway: "**The bottleneck moves up the stack.** AI didn't make me a faster typist — it made the *spec* the thing I spend time on. And that's the right thing to spend time on."
  - "You don't need Allium. You need *a single checkable definition of correct behaviour* both you and the AI are accountable to. The discipline scales, not the tool."
  - Soft CTA: "I build this way as a contractor through JUXT — come find me."
- **Visual:** Two-column "AI did / Human kept", then the un-greyed takeaway line.
- **Time:** 3 min

---

## Segment 5 — One question, hard stop (14:00–15:00) · 1 min

- Take exactly one. Pre-pick your favourite so you control the close (see below).

---

## Demo — the propagate loop

**Setup:** the spec loop is local — no full platform needed. Three panes: the `.allium` spec, a terminal, the impl file. Pick a *one-sentence* rule (recommended: a limits rule — "reject a trade when the position limit is exceeded"). Rehearse once so timing is known.

**Reveal (the lines that make the point):**
1. Edit one rule in `specs/limits.allium` (add a soft-breach warning band).
2. `/weed` → reports spec declares behaviour the code doesn't enforce.
3. `/propagate` → generates a failing test whose name reads as a sentence.
4. Run tests → red. Implement minimal change → green.
- Landing line: **"I changed the spec. The test wrote itself. The code had nowhere to hide."**

**Fallback (demos fail ~50%):** pre-recorded ~60s screencap (`<<LOOM_LINK>>`) + 4 backup screenshots (spec diff → `/weed` → generated test → green run). If the network dies, walk the screenshots: "I ran this an hour ago — here's what you'd see." No apology.

---

## Q&A prep — pick ONE to take

1. **"How is this different from TDD?"** It's TDD where the test is *generated from a higher-level contract*, so the spec is canonical and `/weed` actively reports drift across all 13 services — hand-written tests don't.
2. **"How do you trust AI code in a *regulated* domain?"** You constrain it: run-reproducibility manifests (ADR-0018) and a hash-chained audit trail (ADR-0017) make every calculation verifiable regardless of who wrote it. Quant/regulatory correctness is explicitly human-validated.
3. **"Isn't 453k lines just AI bloat?"** Some, fair — but it's 13 services with real infra and full test layers; the honest metric is **25 specs**, the surface I actually maintain.
4. **"vs Copilot/Cursor?"** Those make typing faster; this changes *what you're accountable for* — the spec. Use Cursor as the editor if you like; the differentiator is the weed/propagate discipline on top.

*Best closer to take:* #3 — it lets you re-land the **25 specs** number on the way out.

---

## Speaker briefing — 5 things to remember

1. **The one stat that must land: 25 specs.** Not 453k (invites "bloat") — *25 specs* proves the method. Say it in the hook and again at the close.
2. **Do NOT cut the honest caveat** to save time — cut architecture detail instead. The caveat is what makes the big number believable.
3. **Pace the demo, don't race it.** Narrate before every enter. If it fails, switch to screenshots without apology. Never debug live in a lightning slot — you have no recovery time.
4. **Don't oversell autonomy.** It was *driven* — 602 prompts, tight iteration — not autopilot. Story is *leverage*, not magic.
5. **One question, hard stop.** Pre-pick it. Redirect maths/vendor rabbit holes with "happy to go deep offline."

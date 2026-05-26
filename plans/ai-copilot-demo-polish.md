# AI Copilot demo polish — make it easy to spot, build trust

## Context

The Copilot foundation from `plans/ai-v2.md` is shipped (74/75 checkboxes): MCP server + 10 read tools, `ChatRequest`/`ChatChunk`, streaming SSE chat endpoint, gateway proxy, citation verifier, policy guard, ⌘K command palette, `<StreamingNarrative>`, `<CitationFootnote>`/`<CitationList>`, `<NotificationStrip>` with morning brief and intraday push, saved-query chips, and inline `<ExplainButton>` rollout on the Risk / P&L / Alerts surfaces.

What's **missing** is everything Marcus (senior FX/rates trader) called out in his review of an interactive copilot on a professional risk system:

- **No visible discovery affordance.** ⌘K is the only entry point; a demo viewer who doesn't know the shortcut never finds the AI.
- **Freshness is plain text, not urgent.** `42s ago` renders alongside other metadata — no red badge when a cited number is stale.
- **Error codes leak through.** When the policy guard or citation verifier fires, the UI prints `CITATION_UNVERIFIABLE` verbatim instead of a human-friendly message.
- **No conversation reset on book switch.** Switching `bookId` mid-stream silently carries the old conversation context — exactly the failure mode Marcus flagged ("it'll cite wrong numbers from the prior context").
- **No tool-call reasoning panel.** Citations show *which tool produced a number*; nothing surfaces the *chain of tool calls* the model executed.
- **No "dashboard wins" framing.** Nothing in the UI tells the user that when the Copilot disagrees with the dashboard, the dashboard is source of truth.

Demo positioning: ⌘K modal, dismissible, secondary surface — never the front door. The dashboards stay the headline. The Copilot is a power-user tool for cross-book queries, onboarding, and the investigation tail.

## Status

In progress.

## Decisions applied

- **Launcher placement.** Header right cluster, between `<ScenarioIndicator>` and `<RegimeIndicator>` so it sits with other indicators rather than competing with primary nav. Visual: Sparkles icon + `Ask Kinetix` label + `⌘K` keyboard chip. Click opens the existing `<CommandPalette>` in `copilotMode`.
- **No FAB.** Marcus's feedback rules out a bottom-right floating button — too attention-seeking for a secondary surface.
- **Freshness thresholds.** `freshness_seconds ≤ 30` → neutral, `> 30 && ≤ 60` → amber, `> 60` → red badge. Pure visual treatment in `<CitationList>` — no backend change needed; the data is already there.
- **Friendly error copy.** `CITATION_UNVERIFIABLE` → "I couldn't verify one of the numbers in this answer. Please cross-check on the dashboard." `POLICY_VIOLATION` → "I can only narrate data, not advise on actions. Try rephrasing as a question about what the numbers show." Any other code → "Something went wrong generating this answer."
- **Conversation reset on book switch.** When the active `bookId` changes while a copilot conversation is open or has messages, drop the `conversation_id`, clear streamed state, and show a one-line in-palette banner: `Conversation reset — switched to {bookName}.` No toast (Marcus: keep it unintrusive).
- **Tool-call reasoning surface.** Extend `ChatChunk` (the `done` variant) with an optional `tool_calls: ToolCall[]` list — `{name, params, status, started_at, completed_at}`. Render as a `<details>` block at the bottom of `<StreamingNarrative>` labelled `Show reasoning (N tool calls)`. Collapsed by default; opens on click.
- **"Dashboard wins" copy.** One-line footnote in the ⌘K empty state and the morning brief inbox: `Dashboards remain the source of truth. The Copilot narrates them, it doesn't replace them.` No big banner — copy, not chrome.
- **No changes to MCP tools, gateway, or the orchestrator for this plan.** All work lives in `ui/` + a single additive backend change in `ai-insights-service/src/kinetix_insights/chat/` to populate `tool_calls` on the final SSE chunk.

## CI/CD & guardrail approvals (pre-approved)

- **No new dependencies.** All UI changes use existing React + lucide-react. Backend `tool_calls` is a pydantic model addition only.
- **No new services, topics, tables, or migrations.**
- **No CI/CD pipeline file edits.**
- **No test deletions.** Existing Playwright specs (`copilot-chat.spec.ts`, `morning-brief.spec.ts`, `intraday-push.spec.ts`, `var-explainer.spec.ts`) must continue to pass — every checkbox below runs them as part of its acceptance.

## Out of scope

- Per-user Claude billing / OAuth (still single-host subscription; deferred to a future plan).
- Cross-book RBAC for risk-manager scope (deferred — `get_active_alerts` etc. stay single-book).
- New MCP tools beyond the 10 already shipped.
- Conversation persistence to Redis (already covered as PR 10 in `ai-v2.md`).
- Plan §11.2 README hero update — that's still owned by `ai-v2.md`.
- Ad-hoc stress scenarios — out of scope for v2 per ADR-0036.
- Marketing/positioning copy beyond the strings called out above.

## Execution plan

### 1 — Visible discovery affordance

- [ ] 1.1 Add `<CopilotLauncher>` button in `ui/src/components/CopilotLauncher.tsx` — Sparkles icon + `Ask Kinetix` label + dimmed `⌘K` chip; styled to match the rest of the header right cluster (`text-slate-300 hover:text-white`, no border). Accepts an `onOpen` callback. Vitest covers render + click.
      Acceptance: `cd ui && npm run test -- src/components/CopilotLauncher.test.tsx`
- [ ] 1.2 Wire `<CopilotLauncher onOpen={() => setCommandPaletteOpen(true)} />` into the header right cluster in `ui/src/App.tsx`, between `<ScenarioIndicator>` and `<RegimeIndicator>`. Update `ui/src/App.test.tsx` (if present) or add a smoke test asserting the launcher button is in the document and clicking it opens the palette in copilot mode.
      Acceptance: `cd ui && npm run test -- src/App` && cd ui && npm run lint
- [ ] 1.3 Add Playwright `ui/e2e/copilot-launcher.spec.ts` — asserts the header button is visible in demo mode, click opens the palette, ⌘K shortcut still works as fallback, and the badge shows `⌘K` on macOS / `Ctrl K` on other platforms via `navigator.platform`.
      Acceptance: `cd ui && npx playwright test e2e/copilot-launcher.spec.ts`

### 2 — Freshness trust signal

- [ ] 2.1 Refactor `formatFreshness` in `ui/src/components/CitationList.tsx` to return `{ label: string, urgency: 'fresh' | 'aging' | 'stale' }` based on `freshness_seconds` thresholds (≤30, ≤60, >60). Render the badge with `bg-emerald-500/10` (fresh), `bg-amber-500/15` (aging), `bg-rose-500/20 text-rose-300 ring-1 ring-rose-500/40` (stale). Vitest covers all three thresholds + boundary cases.
      Acceptance: `cd ui && npm run test -- src/components/CitationList.test.tsx`
- [ ] 2.2 Mirror the same urgency badge inline next to each `<CitationFootnote>` superscript when urgency is `aging` or `stale` (no badge when fresh, to keep neutral citations unintrusive). Vitest covers inline render per urgency.
      Acceptance: `cd ui && npm run test -- src/components/CitationFootnote.test.tsx`

### 3 — Friendly error messages

- [ ] 3.1 Add `mapChatErrorCode(code: string | undefined): { title: string, body: string }` in `ui/src/api/copilot.ts`. Map `CITATION_UNVERIFIABLE`, `POLICY_VIOLATION`, and a default fallback per the "Decisions applied" strings. Unit-tested.
      Acceptance: `cd ui && npm run test -- src/api/copilot.test.ts`
- [ ] 3.2 Replace the raw `errorCode` render in `ui/src/components/StreamingNarrative.tsx` (search for `state === 'error'`) with the mapped title + body, rendered in a rose-tinted info panel — no scary modals. Vitest covers each error code's UI.
      Acceptance: `cd ui && npm run test -- src/components/StreamingNarrative.test.tsx`

### 4 — Book-boundary conversation reset

- [ ] 4.1 Add a `bookId` prop to `<CommandPalette>` in `ui/src/components/CommandPalette.tsx`. When `bookId` changes and the palette holds a non-empty `copilotStream` / `copilotAnswered` / `conversationId`, clear them and set a one-line `bookResetBanner: { fromBookName, toBookName }` state. Render the banner inside the palette body, dismissible on next user input. Vitest covers reset behaviour and banner render.
      Acceptance: `cd ui && npm run test -- src/components/CommandPalette.test.tsx`
- [ ] 4.2 Pass `bookId={effectiveBookId ?? bookId}` from `ui/src/App.tsx` into `<CommandPalette>`. Extend `ui/e2e/copilot-chat.spec.ts` (or add a focused spec) to assert: open palette, send a message, switch book in the header, palette banner appears, conversation_id is cleared on the next chat request.
      Acceptance: `cd ui && npx playwright test e2e/copilot-chat.spec.ts`

### 5 — Tool-call reasoning panel

- [ ] 5.1 Extend `ChatChunk` (`done` variant) in `ai-insights-service/src/kinetix_insights/chat/models.py` with `tool_calls: list[ToolCall] | None` and add a `ToolCall` pydantic model `{name, params, status: ok|error|timeout, started_at, completed_at}`. Update the existing `ChatChunk` serialisation test and `_FakeStreamingSdk` to populate the field. No SDK behaviour change yet.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_chat_models.py tests/test_chat_acceptance.py`
- [ ] 5.2 Populate `tool_calls` in `ClaudeAgentCopilotChatClient` (`chat/claude_agent_chat_client.py`) by accumulating SDK tool-use events alongside content. `CannedCopilotChatClient` reads from the existing transcript fixtures (extend `fixtures/chat_transcripts/*.json` to include a `tool_calls` block). Unit tests cover both paths.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_claude_agent_chat_client.py tests/test_canned_chat_client.py`
- [ ] 5.3 Mirror the type extension in `ui/src/api/copilot.ts` and add a `<ToolCallList>` component in `ui/src/components/ToolCallList.tsx`. Render as a `<details>` element below `<StreamingNarrative>` labelled `Show reasoning ({N} tool calls)`. Collapsed by default. Each call shows name, status icon, and a `<details>` for params. Vitest covers collapsed/expanded, status icons, and empty list (no panel).
      Acceptance: `cd ui && npm run test -- src/components/ToolCallList.test.tsx`
- [ ] 5.4 Wire `<ToolCallList>` into `<StreamingNarrative>` and `<CommandPalette>` chat surfaces. Extend `ui/e2e/copilot-chat.spec.ts` to assert the panel renders with a non-zero tool-call count in demo mode.
      Acceptance: `cd ui && npx playwright test e2e/copilot-chat.spec.ts`

### 6 — Source-of-truth messaging

- [ ] 6.1 Add a one-line footnote `Dashboards remain the source of truth. The Copilot narrates them, it doesn't replace them.` in the ⌘K empty-state (above the saved-query chips) and at the bottom of `<MorningBriefCard>`. `text-xs text-slate-400`, never sticky. Vitest covers presence on both surfaces.
      Acceptance: `cd ui && npm run test -- src/components/CommandPalette.test.tsx src/components/MorningBriefCard.test.tsx`

### 7 — Demo seeding for the launcher

- [ ] 7.1 Verify `ai-insights-service`'s canned-mode transcript fixtures cover the 5 saved queries with realistic citations + tool calls (so the demo viewer who clicks a chip gets a believable answer in <1s). Extend any thin fixtures and re-run the brief / chat acceptance tests.
      Acceptance: `cd ai-insights-service && uv run pytest tests/test_brief_acceptance.py tests/test_chat_acceptance.py tests/test_canned_chat_client.py`
- [ ] 7.2 Update `docs/wiki/AI-Features.md` "Status" table — flip v2 chat / morning brief / intraday push / ⌘K rows from `In flight` to `Shipped`, add a new "Demo polish" row pointing at this plan.
      Acceptance: `grep -q 'In flight' docs/wiki/AI-Features.md && exit 1 || grep -q 'Demo polish' docs/wiki/AI-Features.md`

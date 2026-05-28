# ADR-0023: Hierarchical Limit Management

## Status
Accepted

## Related specs
- [`specs/limits.allium`](../../specs/limits.allium) — limit hierarchy, pre-trade checks, and temporary increase governance.

## Context
Trading firms enforce exposure limits at multiple organisational levels: firm-wide, division, desk, book (portfolio), trader, and counterparty. A trade that passes a book-level limit may still breach a desk or firm limit. Limits also differ between intraday and overnight horizons, and temporary increases may be granted.

## Decision
Implement a hierarchical limit checking system in `position-service` with 6 levels:

**Hierarchy:** `FIRM` → `DIVISION` → `DESK` → `BOOK` / `TRADER` / `COUNTERPARTY`

`LimitHierarchyService` checks limits bottom-up: starting at the entity's own level, then walking up the parent hierarchy. For example, a book-level check evaluates: BOOK → DESK → DIVISION → FIRM.

Key features:
- **Intraday vs. overnight limits**: Each `LimitDefinition` can specify separate `intradayLimit` and `overnightLimit` values alongside a base `limitValue`
- **Temporary limit increases**: `TemporaryLimitIncreaseRepository` stores time-bounded overrides that automatically expire
- **Warning threshold**: Configurable percentage (default 80%) that triggers a `WARNING` status before a `BREACHED` status
- **Parent resolution**: `LimitHierarchyService` can auto-resolve parent entity IDs via `ReferenceDataServiceClient` (e.g., looking up a desk's division from reference data)
- **Three-state result**: `OK`, `WARNING`, `BREACHED` — each with the effective limit, current exposure, and breach level

## Applies when
- Adding a new limit type, a new limit dimension, or a new entity that needs limit enforcement.
- Touching `LimitHierarchyService`, `LimitDefinition`, or `TemporaryLimitIncrease`.
- Implementing pre-trade checks anywhere in the platform.

## Rules
- **DO** check limits bottom-up via `LimitHierarchyService.checkLimits(...)`. A book check evaluates BOOK → DESK → DIVISION → FIRM in one call.
- **DO** record `intradayLimit` and `overnightLimit` distinctly when they differ. Don't conflate them.
- **DO** use `TemporaryLimitIncreaseRepository` for time-bounded overrides. Never edit `LimitDefinition` rows for short-term breaches.
- **DO** emit all three states (`OK`, `WARNING`, `BREACHED`) and surface them in the UI. WARNING is the early signal the desk relies on.
- **DO** call `parentHierarchyFor(entityType)` rather than hardcoding parent relationships.
- **DON'T** add a parallel limit-checking path for "performance" — bottom-up traversal is the contract. Acceptable for pre-trade; not for HFT-style hot paths (which aren't in scope).
- **DON'T** invent a new hierarchy level (e.g. "sub-desk") without updating `parentHierarchyFor`, `LimitLevel`, and the reference-data lookups in one coherent change.
- **DON'T** silently fall back to `OK` when parent resolution fails — surface the error.

## Consequences

### Positive
- A single API call checks all levels in the hierarchy — no need for separate calls per level
- Temporary increases are time-bounded and automatically expire — no manual cleanup
- Warning state enables proactive risk management before a hard breach
- Parent auto-resolution reduces the data callers need to supply

### Negative
- Bottom-up traversal means multiple database queries per check (one per hierarchy level)
- Parent resolution via HTTP call to reference-data-service adds latency — acceptable for pre-trade checks, not for high-frequency scenarios
- The hierarchy is fixed in code (`parentHierarchyFor`) — a different organisational structure would require code changes

### Alternatives Considered
- **Flat limits (one level)**: Simpler, but cannot enforce firm-wide aggregate limits across desks. A desk could breach the firm limit without any individual desk limit being exceeded.
- **Pre-computed aggregate exposure**: Maintain running totals at each level. Faster to check, but complex to keep consistent across concurrent trades.
- **External limit management system**: Dedicated vendor solution (e.g., OpenGamma). Overkill for our scale; introduces external dependency and licensing cost.

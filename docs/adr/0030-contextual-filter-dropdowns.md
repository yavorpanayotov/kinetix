# ADR-0030: Contextual Filter Dropdowns

## Status
Accepted

## Context
The UI has several filter dropdowns (e.g. "All Types" for instrument types) that show a static list of all possible values regardless of what data is present. This creates noise — a rates-only book shows 13 instrument type options when only 2-3 are relevant — and includes legacy aliases (`FUTURES`, `COMMODITY`) that never match real data because the components use `Object.keys(INSTRUMENT_TYPE_COLORS)` instead of the curated `INSTRUMENT_TYPE_OPTIONS` list.

The `useBookSelector` hook already derives its options from live API data, establishing a precedent for data-driven filter options.

## Decision
Filter dropdowns for **data-driven types** (instrument types in PositionGrid and TradeBlotter) derive their options from the current dataset, showing only types that are actually present, with counts (e.g. "Cash Equity (42)").

**Closed enums** (scenario types in ScenarioLibraryGrid, trade sides BUY/SELL) remain static — they are small, fixed vocabularies where confirming "zero exist" has value.

**Creation forms** (WhatIfPanel instrument type selector) always show the full domain list, since the user may be creating something that does not yet exist in the dataset.

### Rules
1. Options are derived from the **unfiltered** dataset to avoid cascading filter confusion (selecting one filter must not cause options to disappear from another).
2. Options are sorted in **canonical domain order** (using `INSTRUMENT_TYPE_OPTIONS` from `instrumentTypes.ts`), not alphabetically or by count.
3. When a selected filter value disappears from the dataset (e.g. on book switch), the filter **auto-resets** to "All" with an inline notice.
4. The dropdown is **suppressed** when only one type exists in the dataset.

## Applies when
- Adding a filter dropdown to a data grid or list view in the UI.
- Touching `PositionGrid`, `TradeBlotter`, or any component that filters by instrument type / asset class / book.
- Tempted to derive options from `Object.keys(SOME_COLOR_MAP)` or from a hardcoded constant.

## Rules
- **DO** derive options for **data-driven** filters from the unfiltered dataset (`useMemo` over the source array) with counts appended (e.g. `"Cash Equity (42)"`).
- **DO** sort options in canonical domain order (e.g. `INSTRUMENT_TYPE_OPTIONS` from `instrumentTypes.ts`). Not alphabetical, not by count.
- **DO** auto-reset the filter to "All" (with an inline notice) when the selected value disappears from the dataset.
- **DO** suppress the dropdown entirely when the dataset has only one distinct value for that dimension.
- **DO** leave **closed enums** static — scenario types in `ScenarioLibraryGrid`, trade sides BUY/SELL. The user value is "confirm zero exist".
- **DO** show the **full domain list** in creation forms (e.g. `WhatIfPanel`). The user may be creating something not yet in the dataset.
- **DON'T** use `Object.keys(INSTRUMENT_TYPE_COLORS)` as a source of options — it leaks legacy aliases (`FUTURES`, `COMMODITY`). Use the curated `INSTRUMENT_TYPE_OPTIONS`.
- **DON'T** make options depend on the *filtered* dataset — that creates cascading-filter confusion.
- **DON'T** introduce a shared `useFilterOptions` hook. A single `useMemo` per component is preferred over indirection.

## Consequences

### Positive
- Eliminates dead options that can never produce results, reducing cognitive noise
- Counts provide at-a-glance distribution information without requiring a separate chart
- Fixes the existing bug where legacy aliases appeared as filter options
- Consistent with the `useBookSelector` pattern already in the codebase

### Negative
- Filter options now depend on data, introducing a stale-selection edge case that requires the auto-reset mechanism
- Slightly more complex component logic (a `useMemo` + `useEffect` per filterable component)

### Alternatives Considered
- **Shared `useFilterOptions` hook**: Rejected — the derivation is a single `useMemo` expression; a hook adds indirection without value.
- **Show unavailable types as greyed-out/disabled**: Rejected — disabled options in native `<select>` elements are poorly supported by screen readers, and showing types that don't exist implies a broader universe that isn't there.
- **Make ScenarioLibraryGrid contextual too**: Rejected — it's a closed 3-member enum where hiding an option prevents users from confirming "there are none of this type."

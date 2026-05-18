# ADR-0010: Use React + Vite for Frontend

## Status
Accepted

## Context
The system needs a modern UI for risk dashboards, position management, and regulatory reporting. This is a single-page application (SPA) for internal users — not a public content site. Options: React + Vite, Next.js, Angular, Vue.

## Decision
Use React 19 with Vite 7.3.1, TypeScript, and Tailwind CSS 4.

## Applies when
- Adding a new UI tab, panel, dialog, route, or interactive workflow.
- Choosing a UI library, charting library, or styling approach.
- Tempted to introduce SSR, CSS-in-JS, Bootstrap, Material UI, or a state-management framework.

## Rules
- **DO** write components as TypeScript function components with hooks. Strict mode is on — fix typing errors, don't `any` them away.
- **DO** style with Tailwind utility classes. Co-locate component-specific styles in the JSX.
- **DO** import icons from `lucide-react`.
- **DO** test every new tab/panel/dialog/workflow with both Vitest unit tests **and** Playwright E2E tests under `ui/e2e/` (CLAUDE.md mandate). Unit tests alone are never sufficient.
- **DO** run `cd ui && npm run lint` before committing UI changes — ESLint catches `react-hooks/set-state-in-effect` and similar that tests miss.
- **DO** add new dependencies via `npm install` and commit the lockfile.
- **DON'T** introduce Next.js, server components, or any SSR concept — this is an SPA.
- **DON'T** introduce a CSS-in-JS library (styled-components, Emotion), a competing CSS framework (Bootstrap, MUI, Chakra), or a global state library (Redux, Zustand, MobX) without ADR approval.
- **DON'T** add a new charting library if `recharts` (or whatever the existing component uses) already covers the case.
- **DON'T** call backend services directly from the UI — always go through the gateway (ADR-0012).

## Consequences

### Positive
- React 19 has the largest ecosystem for component libraries, charting, and data tables
- Vite provides fast dev server (HMR in milliseconds) and modern bundling
- SPA architecture is the right fit — no SSR complexity needed for an internal dashboard
- Tailwind CSS avoids CSS-in-JS runtime overhead
- Lucide React provides a consistent icon set

### Negative
- No SSR — not suitable if requirements change to public-facing (unlikely for risk management)
- React ecosystem churn — library choices may need updating over time

### Key Libraries
- **Tailwind CSS 4** with `@tailwindcss/vite` plugin for styling
- **Lucide React** for icons
- **Playwright** for E2E acceptance tests
- **Vitest** + **Testing Library** for unit tests

### Alternatives Considered
- **Next.js**: Adds SSR/SSG complexity that an internal SPA doesn't need. App Router introduces server components — unnecessary abstraction for a dashboard.
- **Angular**: Full framework with opinionated structure. Heavier, steeper learning curve, smaller community for greenfield projects in 2026.
- **Vue**: Viable alternative but smaller ecosystem for financial charting and data-heavy dashboards.

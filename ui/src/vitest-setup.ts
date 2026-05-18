import '@testing-library/jest-dom/vitest'

// Plan §9 — Kinetix is desktop-only with a 1280px viewport floor. jsdom
// defaults to a 1024px innerWidth, which would trigger the small-viewport
// warning in every App-level test and hide the rest of the UI. Default the
// test viewport to a desktop-class width so tests render the full app;
// tests that specifically exercise the warning override this via
// Object.defineProperty.
if (typeof window !== 'undefined') {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    writable: true,
    value: 1440,
  })
  Object.defineProperty(window, 'innerHeight', {
    configurable: true,
    writable: true,
    value: 900,
  })
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof globalThis.ResizeObserver
}

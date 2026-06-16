// Plan §mobile — the mobile surface is a single-column, phone-first tree with a
// bottom tab bar. It exposes a curated subset of the desktop tabs as a small
// union of views. Navigation is local state in <MobileApp>, mirroring the
// `activeTab` pattern in App.tsx — no router, no new dependency.
export type MobileView = 'risk' | 'pnl' | 'alerts' | 'positions'

// The default view a fresh mobile session lands on.
export const DEFAULT_MOBILE_VIEW: MobileView = 'risk'

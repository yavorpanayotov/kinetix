import { Activity } from 'lucide-react'

// kx-42wk.5 — Until this component existed, an unauthenticated visitor to
// kinetixrisk.ai saw only the word "Kinetix" rendered by the AuthProvider's
// loading state. That gave them no way to start the login flow and no signal
// that the platform was up. The landing surface is deliberately minimal:
// product name, one-sentence description, login CTA, and a small link to a
// status page. No marketing copy.
//
// The component is presentation-only: the `onLogin` callback wires through
// to keycloak-js so the AuthProvider stays the single owner of auth state.

interface UnauthenticatedLandingProps {
  /** Invoked when the visitor clicks "Log in". Wires to keycloak.login(). */
  onLogin: () => void
}

export function UnauthenticatedLanding({ onLogin }: UnauthenticatedLandingProps) {
  return (
    <div
      data-testid="unauthenticated-landing"
      className="min-h-screen bg-surface-900 text-white flex items-center justify-center px-6"
    >
      <div className="max-w-md w-full text-center flex flex-col items-center gap-6">
        <div className="flex items-center gap-2">
          <Activity className="h-8 w-8 text-primary-500" aria-hidden="true" />
          <h1 className="text-3xl font-bold tracking-tight">Kinetix</h1>
        </div>
        <p
          data-testid="landing-description"
          className="text-sm text-slate-300 leading-relaxed"
        >
          Real-time risk management and trading analytics for institutional
          portfolios.
        </p>
        <button
          data-testid="landing-login-button"
          type="button"
          onClick={onLogin}
          className="inline-flex items-center justify-center px-6 py-2 text-sm font-semibold rounded-md bg-primary-500 hover:bg-primary-400 text-white transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-400 focus-visible:ring-offset-2 focus-visible:ring-offset-surface-900"
        >
          Log in
        </button>
        <a
          data-testid="landing-status-link"
          href="/status"
          className="text-xs text-slate-400 hover:text-slate-200 underline-offset-2 hover:underline transition-colors"
        >
          System status
        </a>
      </div>
    </div>
  )
}

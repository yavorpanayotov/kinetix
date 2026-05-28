import { useEffect, useRef, useState, type ReactNode } from 'react'
import KeycloakReal from 'keycloak-js'
import { AuthContext, type AuthState } from './useAuth'
import { setAuthToken } from './authFetch'
import { UnauthenticatedLanding } from '../components/UnauthenticatedLanding'

const keycloakConfig = {
  url: 'https://auth.kinetixrisk.ai',
  realm: 'kinetix',
  clientId: 'kinetix-api',
}

// Allow E2E tests to inject a mock Keycloak constructor via window.Keycloak
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const Keycloak = ((window as any).Keycloak ?? KeycloakReal) as typeof KeycloakReal

interface AuthProviderProps {
  children: ReactNode
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [state, setState] = useState<AuthState>({
    authenticated: false,
    initialising: true,
    token: null,
    username: null,
    roles: [],
    logout: () => {},
  })

  const keycloakRef = useRef<InstanceType<typeof Keycloak> | null>(null)
  const initCalled = useRef(false)

  useEffect(() => {
    if (initCalled.current) return
    initCalled.current = true

    const kc = new Keycloak(keycloakConfig)
    keycloakRef.current = kc

    // kx-42wk.5 — switched from `login-required` to `check-sso` so a visitor
    // with no session is *not* auto-redirected to Keycloak. Instead the
    // AuthProvider lands them on <UnauthenticatedLanding/>, which gives them
    // a branded entry point, a Log in button (which calls kc.login()), and a
    // /status link. The redirect-on-401 behaviour for *authenticated* refresh
    // failures is preserved by the kc.login() call in the token-refresh
    // catch block below.
    kc.init({ onLoad: 'check-sso', pkceMethod: 'S256', checkLoginIframe: false })
      .then((authenticated) => {
        if (authenticated) {
          setAuthToken(kc.token ?? null)
          setState({
            authenticated: true,
            initialising: false,
            token: kc.token ?? null,
            username: kc.tokenParsed?.preferred_username ?? null,
            roles: kc.tokenParsed?.roles ?? [],
            logout: () => kc.logout({ redirectUri: window.location.origin }),
          })
        } else {
          setState((prev) => ({ ...prev, initialising: false }))
        }
      })
      .catch(() => {
        setState((prev) => ({ ...prev, initialising: false }))
      })

    // Refresh token every 30 seconds; refresh if expiring within 60s (per trader review)
    const refreshInterval = setInterval(() => {
      kc.updateToken(60)
        .then((refreshed) => {
          if (refreshed) {
            setAuthToken(kc.token ?? null)
            setState((prev) => ({
              ...prev,
              token: kc.token ?? null,
              username: kc.tokenParsed?.preferred_username ?? prev.username,
              roles: kc.tokenParsed?.roles ?? prev.roles,
            }))
          }
        })
        .catch(() => {
          // Session expired — force re-login
          // Save active tab so it can be restored after login
          const activeTab = document.querySelector('[role="tab"][aria-selected="true"]')?.id
          if (activeTab) sessionStorage.setItem('kinetix:pre-auth-tab', activeTab)
          kc.login()
        })
    }, 30_000)

    return () => clearInterval(refreshInterval)
  }, [])

  if (state.initialising) {
    return (
      <div
        className="min-h-screen bg-surface-900 flex items-center justify-center"
        aria-busy="true"
        aria-label="Loading, please wait"
      >
        <div className="text-center">
          <h1 className="text-2xl font-bold text-white tracking-tight">Kinetix</h1>
          <p className="text-sm text-slate-400 mt-2">Authenticating...</p>
        </div>
      </div>
    )
  }

  if (!state.authenticated) {
    // kx-42wk.5 — render the public landing so unauthenticated visitors get
    // a branded welcome screen with a Log in CTA and a status link, rather
    // than a blank app or an immediate Keycloak redirect.
    return (
      <UnauthenticatedLanding
        onLogin={() => keycloakRef.current?.login()}
      />
    )
  }

  return <AuthContext.Provider value={state}>{children}</AuthContext.Provider>
}

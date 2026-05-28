import { test, expect, type Route } from '@playwright/test'

// Closes kx-42wk.5 — kinetixrisk.ai's unauthenticated landing page must give
// a visitor enough signal to (a) know they're in the right place, (b) start
// the Keycloak login flow, and (c) check whether the platform is up. This
// spec mocks the Keycloak constructor with an *unauthenticated* stub so the
// AuthProvider falls through to the landing page, then asserts on the
// observable behaviour: product name, log-in button, status link.
//
// Note: this differs from fixtures.ts#mockKeycloakAuth, which mocks an
// *already-authenticated* session. Here we deliberately want the
// unauthenticated path.

async function mockUnauthenticatedKeycloak(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/auth/realms/**', (route: Route) => {
    route.fulfill({ status: 200, contentType: 'text/html', body: '' })
  })

  await page.addInitScript(`
    window.Keycloak = function MockKeycloakUnauthenticated() {
      this.authenticated = false;
      this.token = null;
      this.tokenParsed = null;
      let _loginCalls = 0;
      this.init = function() {
        // Simulate check-sso returning "not logged in" — AuthProvider should
        // resolve initialising:false with authenticated:false.
        return Promise.resolve(false);
      };
      this.updateToken = function() { return Promise.resolve(false); };
      this.logout = function() {};
      this.login = function(opts) {
        _loginCalls += 1;
        window.__KC_LOGIN_CALLS__ = _loginCalls;
        window.__KC_LAST_LOGIN_OPTS__ = opts || null;
      };
    };
  `)
}

test.describe('Unauthenticated landing (kx-42wk.5)', () => {
  test.beforeEach(async ({ page }) => {
    await mockUnauthenticatedKeycloak(page)
  })

  test('renders the Kinetix product name', async ({ page }) => {
    await page.goto('/')
    await expect(
      page.getByRole('heading', { name: /kinetix/i, level: 1 }),
    ).toBeVisible()
  })

  test('renders a one-line product description', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByTestId('landing-description')).toBeVisible()
  })

  test('shows a Log in button with an accessible role', async ({ page }) => {
    await page.goto('/')
    const loginButton = page.getByRole('button', { name: /log in/i })
    await expect(loginButton).toBeVisible()
  })

  test('clicking Log in starts the Keycloak login flow', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: /log in/i }).click()
    // Keycloak's login() redirects to the IdP's authorisation endpoint:
    //   /auth/realms/kinetix/protocol/openid-connect/auth?...
    // Our stub records that login() was called rather than performing a
    // real redirect (which would require a live Keycloak). Asserting that
    // the call happened proves the button is wired to the Keycloak flow.
    await expect.poll(async () => page.evaluate(() => (window as unknown as { __KC_LOGIN_CALLS__?: number }).__KC_LOGIN_CALLS__ ?? 0)).toBeGreaterThan(0)
  })

  test('exposes a /status link so visitors can check whether the platform is up', async ({ page }) => {
    await page.goto('/')
    const statusLink = page.getByRole('link', { name: /status/i })
    await expect(statusLink).toBeVisible()
    await expect(statusLink).toHaveAttribute('href', '/status')
  })
})

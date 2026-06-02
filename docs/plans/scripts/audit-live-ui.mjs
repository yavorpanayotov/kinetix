// Final live-UI audit for docs/plans/ui-fix-v1.md checkbox 10.3.
//
// Drives every tab of the live UI in a real browser and asserts the
// post-fix invariants: zero console errors, zero api.kinetixrisk.ai 4xx/5xx,
// non-empty ticker strip, populated Firm Summary, a single rollup breach
// banner, and the Regulatory tab's SBM table. Writes a JSON report to
// /tmp/kinetix-audit/report.json and exits non-zero if any invariant fails.
//
// Usage: node docs/plans/scripts/audit-live-ui.mjs
//   UI / GATEWAY env vars override the default kinetixrisk.ai hosts.
//
// Requires Playwright's chromium — resolved from ui/node_modules.
import { chromium } from '../../ui/node_modules/playwright/index.mjs'
import { writeFileSync, mkdirSync } from 'node:fs'

const UI = process.env.UI ?? 'https://kinetixrisk.ai'
const API_HOST = process.env.GATEWAY ?? 'https://api.kinetixrisk.ai'
const OUT_DIR = '/tmp/kinetix-audit'
const OUT = `${OUT_DIR}/report.json`

const TABS = [
  'positions', 'trades', 'pnl', 'risk', 'eod',
  'scenarios', 'counterparty-risk', 'regulatory',
  'reports', 'alerts', 'system',
]

const report = {
  ranAt: new Date().toISOString(),
  ui: UI,
  consoleErrors: [],
  networkFailures: [],
  pageErrors: [],
  checks: {},
  pass: false,
}

function check(name, ok, detail) {
  report.checks[name] = { ok, detail: detail ?? null }
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? ' — ' + detail : ''}`)
  return ok
}

;(async () => {
  mkdirSync(OUT_DIR, { recursive: true })
  const browser = await chromium.launch({ headless: true })
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, ignoreHTTPSErrors: true })
  const page = await ctx.newPage()

  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      report.consoleErrors.push({ text: msg.text().slice(0, 500), url: msg.location()?.url })
    }
  })
  page.on('response', (resp) => {
    const s = resp.status()
    if (s >= 400 && resp.url().startsWith(API_HOST)) {
      report.networkFailures.push({ status: s, method: resp.request().method(), url: resp.url() })
    }
  })
  page.on('pageerror', (e) => report.pageErrors.push(e.message.slice(0, 300)))

  try {
    await page.goto(UI, { waitUntil: 'load', timeout: 30_000 })
    await page.waitForTimeout(3500)

    // Walk every tab so console/network listeners see all routes.
    for (const tab of TABS) {
      const sel = `[data-testid="tab-${tab}"]`
      if (await page.locator(sel).count()) {
        await page.click(sel)
        await page.waitForTimeout(1800)
      }
    }

    // Invariant: ticker strip NAV is non-empty / non-zero.
    await page.click('[data-testid="tab-positions"]')
    await page.waitForTimeout(2000)
    const navText = (await page.getByTestId('ticker-nav').textContent().catch(() => '')) ?? ''
    check('ticker NAV populated', navText.trim() !== '' && navText.trim() !== '—' && navText.trim() !== '$0.00', `NAV="${navText.trim()}"`)

    // Invariant: Firm Summary card shows a non-zero total NAV.
    const firmNav = (await page.getByTestId('total-nav').textContent().catch(() => '')) ?? ''
    check('Firm Summary NAV populated', firmNav.trim() !== '' && firmNav.trim() !== '$0.00', `total-nav="${firmNav.trim()}"`)

    // Invariant: breach banners are rolled up — at most one rollup row.
    const rollupCount = await page.getByTestId('breach-banner-count').count()
    check('breach banners rolled up', rollupCount <= 1, `${rollupCount} rollup banner(s)`)

    // Invariant: Regulatory tab renders the SBM table after a calc.
    await page.click('[data-testid="tab-regulatory"]')
    await page.waitForTimeout(1200)
    const calcBtn = page.getByTestId('frtb-calculate-btn')
    if (await calcBtn.count()) {
      await calcBtn.click()
      await page.waitForTimeout(2500)
    }
    check('Regulatory SBM table renders', await page.getByTestId('frtb-sbm-table').count() > 0)

    check('zero console errors', report.consoleErrors.length === 0, `${report.consoleErrors.length} error(s)`)
    check('zero api 4xx/5xx', report.networkFailures.length === 0, `${report.networkFailures.length} failure(s)`)
    check('zero uncaught page errors', report.pageErrors.length === 0, `${report.pageErrors.length} error(s)`)
  } catch (e) {
    report.fatal = e.message
    check('audit ran without fatal error', false, e.message)
  } finally {
    report.pass = Object.values(report.checks).every((c) => c.ok)
    writeFileSync(OUT, JSON.stringify(report, null, 2))
    await browser.close()
  }

  console.log(`\nReport: ${OUT}`)
  console.log(report.pass ? '✅ AUDIT PASSED' : '❌ AUDIT FAILED')
  process.exit(report.pass ? 0 : 1)
})()

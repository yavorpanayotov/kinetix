import { chromium } from '@playwright/test'
import fs from 'node:fs'

const BASE = 'https://kinetixrisk.ai'
const OUT = '/Users/yavorpanayotov/IdeaProjects/kinetixlk/plans/ui-review-4.8-artifacts'
const SHOTS = `${OUT}/shots`

const TABS = [
  'positions','trades','pnl','risk','eod','scenarios',
  'counterparty-risk','regulatory','reports','activity','alerts','system'
]

const report = { startedAt: new Date().toISOString(), base: BASE, tabs: {}, globalConsole: [], globalNetwork: [], links: {} }
const consoleErrors = []
const networkErrors = []

const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, ignoreHTTPSErrors: true })
const page = await ctx.newPage()

page.on('console', (m) => {
  if (m.type() === 'error' || m.type() === 'warning') {
    consoleErrors.push({ type: m.type(), text: m.text().slice(0, 500) })
  }
})
page.on('pageerror', (e) => consoleErrors.push({ type: 'pageerror', text: String(e).slice(0, 500) }))
page.on('requestfailed', (r) => networkErrors.push({ url: r.url(), method: r.method(), failure: r.failure()?.errorText }))
page.on('response', (r) => {
  const s = r.status()
  if (s >= 400) networkErrors.push({ url: r.url(), method: r.request().method(), status: s })
})

function drainScoped() {
  const c = consoleErrors.splice(0)
  const n = networkErrors.splice(0)
  return { console: c, network: n }
}

async function snapshot(name) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: false }).catch(() => {})
}

async function bodyText() {
  return (await page.evaluate(() => document.body.innerText)).slice(0, 4000)
}

// detect signals of empty / broken state
function analyze(text) {
  const signals = []
  const lc = text.toLowerCase()
  for (const kw of ['no data','no results','nothing to show','empty','failed to load','error loading','something went wrong','unavailable','not available','--','n/a','loading','no positions','no trades','no alerts','no scenarios','no reports','coming soon']) {
    if (lc.includes(kw)) signals.push(kw)
  }
  return signals
}

console.error('Loading', BASE)
await page.goto(BASE, { waitUntil: 'networkidle', timeout: 60000 }).catch(e => console.error('goto err', e.message))
await page.waitForTimeout(3500)
await snapshot('00-landing')
report.landingText = (await bodyText()).slice(0, 1500)
report.initialLoad = drainScoped()

// capture header links
report.links.github = await page.getAttribute('[data-testid="header-github-link"]', 'href').catch(() => null)
report.links.demoBadge = await page.isVisible('[data-testid="demo-mode-badge"]').catch(() => false)

for (const tab of TABS) {
  const sel = `[data-testid="tab-${tab}"]`
  const entry = { clicked: false }
  try {
    const exists = await page.isVisible(sel)
    entry.tabVisible = exists
    if (exists) {
      await page.click(sel, { timeout: 10000 })
      entry.clicked = true
      await page.waitForTimeout(3000)
      // wait for any spinners to settle
      await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {})
      await page.waitForTimeout(1500)
    }
  } catch (e) {
    entry.clickError = e.message
  }
  const text = await bodyText().catch(() => '')
  entry.signals = analyze(text)
  entry.textSample = text.slice(0, 1200)
  entry.scoped = drainScoped()
  await snapshot(`tab-${tab}`)
  report.tabs[tab] = entry
  console.error('tab', tab, 'signals=', entry.signals.join('|'), 'errs=', entry.scoped.console.length, 'net=', entry.scoped.network.length)
}

fs.writeFileSync(`${OUT}/crawl.json`, JSON.stringify(report, null, 2))
console.error('DONE. report at', `${OUT}/crawl.json`)
await browser.close()

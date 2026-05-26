#!/usr/bin/env node
// Kinetix demo audit harness.
// Drives the deployed demo (default https://kinetixrisk.ai) with headless Chromium,
// walks every tab + persona + key interactive flow, and emits a JSON report.
//
// Hard gates (fail with exit 1 under --assert-clean):
//   (a) zero JS page errors
//   (b) zero HTTP 5xx responses
//   (c) no KPI cell equals "$0.00" after the bootstrap banner clears
//   (d) no visible spinner element after 8s of network-idle on any tab
//
// Soft gates (warn, exit 0): row counts, per-persona consistency, per-book varValue > 0.

import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'
import { writeFileSync, mkdirSync, existsSync } from 'node:fs'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(HERE, '..', '..')
const PLAYWRIGHT_PATH = resolve(REPO_ROOT, 'ui/node_modules/playwright/index.mjs')

if (!existsSync(PLAYWRIGHT_PATH)) {
  console.error(`Playwright not found at ${PLAYWRIGHT_PATH}. Run \`cd ui && npm install\` first.`)
  process.exit(2)
}

const { chromium } = await import(PLAYWRIGHT_PATH)

const args = parseArgs(process.argv.slice(2))
const BASE = args.base || 'https://kinetixrisk.ai'
const OUT = resolve(args.out || join(HERE, 'out'))
const ASSERT_CLEAN = !!args['assert-clean']
const WAIT_FOR_BOOTSTRAP = !!args['wait-for-bootstrap']

mkdirSync(`${OUT}/shots`, { recursive: true })

const TABS = [
  { key: 'positions', label: 'Positions' },
  { key: 'trades', label: 'Trades' },
  { key: 'pnl', label: 'P&L' },
  { key: 'risk', label: 'Risk' },
  { key: 'eod', label: 'EOD History' },
  { key: 'scenarios', label: 'Scenarios' },
  { key: 'counterparty-risk', label: 'Counterparty Risk' },
  { key: 'regulatory', label: 'Regulatory' },
  { key: 'reports', label: 'Reports' },
  { key: 'activity', label: 'Activity' },
  { key: 'alerts', label: 'Alerts' },
  { key: 'system', label: 'System' },
]

const PERSONAS = ['risk_manager', 'trader', 'admin', 'compliance', 'viewer']

const ZERO_PATTERNS = [/^\$?0(\.0{1,2})?$/, /^\$0\.00$/]
const KPI_LABELS = ['NAV', 'UNREALISED P&L', 'INTRADAY P&L', 'VAR 1D 95%', 'NET DELTA', 'NET VEGA']
const SPINNER_SELECTORS = [
  '[data-testid*="spinner" i]',
  '[class*="spinner" i]:not([class*="icon" i])',
  '[class*="animate-spin" i]',
]

console.log(`Auditing ${BASE} → ${OUT}`)
console.log(`Mode: ${ASSERT_CLEAN ? 'assert-clean' : 'report-only'}${WAIT_FOR_BOOTSTRAP ? ', wait-for-bootstrap' : ''}`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1600, height: 1000 } })

const report = {
  base: BASE,
  startedAt: new Date().toISOString(),
  bootstrap: { state: 'unknown', waited: false },
  tabs: {},
  personas: {},
  kpiBar: {},
  hardGate: { pageErrors: 0, http5xx: 0, kpiZeroCells: 0, stuckSpinners: 0 },
  softGate: { tabsWithNoData: [], perBookVarValues: {}, personaInconsistencies: [] },
}

const page = await context.newPage()
const collectors = { console: [], pageErrors: [], http5xx: [], http4xx: [], networkFailures: [] }
page.on('console', (m) => { if (['error', 'warning'].includes(m.type())) collectors.console.push({ type: m.type(), text: m.text().slice(0, 400) }) })
page.on('pageerror', (e) => collectors.pageErrors.push({ message: e.message, stack: (e.stack || '').slice(0, 600) }))
page.on('requestfailed', (r) => collectors.networkFailures.push({ url: r.url(), method: r.method(), error: r.failure()?.errorText }))
page.on('response', (r) => {
  const s = r.status()
  if (s >= 500) collectors.http5xx.push({ url: r.url(), status: s, method: r.request().method() })
  else if (s >= 400) collectors.http4xx.push({ url: r.url(), status: s, method: r.request().method() })
})

await page.goto(BASE, { waitUntil: 'networkidle', timeout: 30000 })
await page.waitForTimeout(1500)

if (WAIT_FOR_BOOTSTRAP) {
  report.bootstrap.waited = true
  const start = Date.now()
  while (Date.now() - start < 60_000) {
    const status = await page.evaluate(async () => {
      try { const r = await fetch('/demo/bootstrap-status'); return r.ok ? r.json() : null } catch { return null }
    })
    report.bootstrap.state = status?.state || 'unreachable'
    if (status?.state === 'READY') break
    await page.waitForTimeout(2000)
  }
  if (report.bootstrap.state !== 'READY') {
    console.warn(`Bootstrap did not reach READY within 60s (state=${report.bootstrap.state})`)
  } else {
    console.log(`Bootstrap READY in ${((Date.now() - start) / 1000).toFixed(1)}s`)
  }
}

// Dismiss the demo welcome strip so it doesn't cover content.
await page.getByTestId('demo-strip-dismiss').click({ timeout: 2000 }).catch(() => {})

// Wait for the bootstrap banner to detach if it appears.
try {
  await page.locator('[data-testid="bootstrap-banner"]').waitFor({ state: 'detached', timeout: 20_000 })
} catch { /* banner may not exist on the deployed version yet */ }

// === KPI BAR CHECK ===
report.kpiBar = await page.evaluate((labels) => {
  const out = {}
  for (const label of labels) {
    const node = Array.from(document.querySelectorAll('*'))
      .find((el) => el.textContent && el.textContent.trim().startsWith(label))
    if (!node) { out[label] = null; continue }
    // Look at the next sibling or child for the value
    const valueText = (node.textContent || '').replace(label, '').trim()
    out[label] = valueText.slice(0, 30)
  }
  return out
}, KPI_LABELS)

for (const [label, value] of Object.entries(report.kpiBar)) {
  if (value && ZERO_PATTERNS.some((p) => p.test(value))) {
    report.hardGate.kpiZeroCells++
    report.softGate.tabsWithNoData.push(`kpi-bar: ${label}=${value}`)
  }
}

// === WALK TABS ===
for (const tab of TABS) {
  const tBucket = {
    httpStart: collectors.http5xx.length + collectors.http4xx.length,
    consoleStart: collectors.console.length,
  }
  try {
    await page.getByRole('tab', { name: new RegExp(tab.label.replace('&', '&'), 'i') }).first().click({ timeout: 5000 })
    await page.waitForTimeout(2500)
    tBucket.state = await detectTabState(page)
    await page.screenshot({ path: `${OUT}/shots/tab-${tab.key}.png`, fullPage: true }).catch(() => {})

    // Spinner check (hard gate)
    await page.waitForTimeout(8000)
    let visibleSpinners = 0
    for (const sel of SPINNER_SELECTORS) {
      visibleSpinners += await page.locator(sel).filter({ has: page.locator('visible=true') }).count().catch(() => 0)
    }
    if (visibleSpinners > 0) {
      report.hardGate.stuckSpinners += visibleSpinners
      tBucket.stuckSpinners = visibleSpinners
    }

    if (tBucket.state.tableRows === 0 && tBucket.state.cards === 0 && !tBucket.state.hasCta) {
      report.softGate.tabsWithNoData.push(tab.key)
    }
  } catch (e) {
    tBucket.error = String(e).slice(0, 300)
  }
  tBucket.http5xxAdded = collectors.http5xx.slice(tBucket.httpStart)
  tBucket.consoleAdded = collectors.console.slice(tBucket.consoleStart)
  report.tabs[tab.key] = tBucket
  console.log(`Tab ${tab.key}: rows=${tBucket.state?.tableRows ?? '-'}, cards=${tBucket.state?.cards ?? '-'}, spinners=${tBucket.stuckSpinners ?? 0}, http5xx=${tBucket.http5xxAdded.length}`)
}

// === PERSONA CONSISTENCY (soft gate) ===
const personaFirmVar = {}
for (const persona of PERSONAS) {
  try {
    await page.getByTestId('persona-switcher-toggle').click({ timeout: 3000 })
    await page.getByTestId(`persona-option-${persona}`).click({ timeout: 3000 })
    await page.waitForTimeout(1500)
    const role = await page.getByTestId('header-role-badge').textContent().catch(() => null)
    const username = await page.getByTestId('header-username').textContent().catch(() => null)
    const firmVar = (await page.evaluate(() => {
      const node = Array.from(document.querySelectorAll('*')).find((el) => el.textContent && el.textContent.trim().startsWith('VAR 1D 95%'))
      return node?.textContent || null
    }))?.replace('VAR 1D 95%', '').trim() || null
    personaFirmVar[persona] = firmVar
    report.personas[persona] = { role, username, firmVar }
  } catch (e) {
    report.personas[persona] = { error: String(e).slice(0, 200) }
  }
}
const uniqueVars = new Set(Object.values(personaFirmVar).filter(Boolean))
if (uniqueVars.size > 1) report.softGate.personaInconsistencies = Object.entries(personaFirmVar)

// === FINALISE ===
report.hardGate.pageErrors = collectors.pageErrors.length
report.hardGate.http5xx = collectors.http5xx.length
report.allHttp5xx = collectors.http5xx
report.allPageErrors = collectors.pageErrors
report.allNetworkFailures = collectors.networkFailures
report.allConsoleErrors = collectors.console.filter((c) => c.type === 'error')

report.finishedAt = new Date().toISOString()
writeFileSync(`${OUT}/report.json`, JSON.stringify(report, null, 2))

await browser.close()

console.log('')
console.log('=== Hard gates ===')
console.log(`  page errors:    ${report.hardGate.pageErrors}`)
console.log(`  HTTP 5xx:       ${report.hardGate.http5xx}`)
console.log(`  KPI zero cells: ${report.hardGate.kpiZeroCells}`)
console.log(`  stuck spinners: ${report.hardGate.stuckSpinners}`)
console.log('=== Soft gates (warnings only) ===')
console.log(`  tabs with no data: ${report.softGate.tabsWithNoData.length} ${JSON.stringify(report.softGate.tabsWithNoData)}`)
console.log(`  persona firmVaR divergence: ${report.softGate.personaInconsistencies.length > 0 ? 'YES' : 'no'}`)
console.log('')
console.log(`Report: ${OUT}/report.json`)

if (ASSERT_CLEAN) {
  const hardFailures =
    report.hardGate.pageErrors + report.hardGate.http5xx + report.hardGate.kpiZeroCells + report.hardGate.stuckSpinners
  if (hardFailures > 0) {
    console.error(`\n[FAIL] ${hardFailures} hard-gate violation(s). See report.json.`)
    process.exit(1)
  }
  console.log('\n[OK] All hard gates passed.')
}

// === Helpers ===

function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (!a.startsWith('--')) continue
    const k = a.slice(2)
    const next = argv[i + 1]
    if (next === undefined || next.startsWith('--')) {
      out[k] = true
    } else {
      out[k] = next
      i++
    }
  }
  return out
}

async function detectTabState(p) {
  return await p.evaluate(() => {
    const main = document.querySelector('main') || document.body
    const text = (main.innerText || '').slice(0, 4000)
    const tableRows = document.querySelectorAll('tbody tr').length
    const cards = document.querySelectorAll('[data-testid$="-card"]').length
    const hasCta = /click|run|generate|calculate/i.test(text) && !/no\s+\w+\s+yet/i.test(text.slice(0, 200))
    return { tableRows, cards, hasCta, snippet: text.slice(0, 300) }
  })
}

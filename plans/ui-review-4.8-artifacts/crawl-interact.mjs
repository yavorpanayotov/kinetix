import { chromium } from '@playwright/test'
import fs from 'node:fs'

const BASE = 'https://kinetixrisk.ai'
const GRAF = 'https://grafana.kinetixrisk.ai'
const OUT = '/Users/yavorpanayotov/IdeaProjects/kinetixlk/plans/ui-review-4.8-artifacts'
const SHOTS = `${OUT}/shots`
const out = { interactions: {}, grafana: {} }

const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, ignoreHTTPSErrors: true })
const page = await ctx.newPage()
const errs = []
page.on('pageerror', e => errs.push(String(e).slice(0,300)))
page.on('response', r => { if (r.status() >= 400) errs.push(`${r.status()} ${r.url()}`) })

const shot = (n) => page.screenshot({ path: `${SHOTS}/${n}.png` }).catch(()=>{})
const text = async () => (await page.evaluate(() => document.body.innerText)).slice(0, 3000)

async function go(tab) {
  await page.click(`[data-testid="tab-${tab}"]`).catch(()=>{})
  await page.waitForTimeout(2500)
}

await page.goto(BASE, { waitUntil: 'networkidle', timeout: 60000 })
await page.waitForTimeout(3000)

// 1. Regulatory — Calculate FRTB
try {
  await go('regulatory')
  errs.length = 0
  await page.getByText('Calculate FRTB', { exact: false }).first().click({ timeout: 8000 })
  await page.waitForTimeout(6000)
  out.interactions.frtb = { afterText: await text(), errs: [...errs] }
  await shot('act-frtb')
} catch (e) { out.interactions.frtb = { error: e.message } }

// 2. Scenarios — run a scenario
try {
  await go('scenarios')
  await shot('act-scenarios-before')
  out.interactions.scenariosBefore = await text()
  errs.length = 0
  // try common run buttons
  const btn = page.getByRole('button', { name: /run|apply|calculate|stress/i }).first()
  if (await btn.count()) { await btn.click({ timeout: 8000 }); await page.waitForTimeout(7000) }
  out.interactions.scenarios = { afterText: await text(), errs: [...errs] }
  await shot('act-scenarios-after')
} catch (e) { out.interactions.scenarios = { error: e.message } }

// 3. Reports — select template + generate
try {
  await go('reports')
  errs.length = 0
  const sel = page.locator('select').first()
  if (await sel.count()) {
    const opts = await sel.locator('option').allTextContents()
    out.interactions.reportTemplates = opts
    await sel.selectOption({ index: 1 }).catch(()=>{})
  }
  await page.getByRole('button', { name: /generate/i }).first().click({ timeout: 8000 }).catch(()=>{})
  await page.waitForTimeout(6000)
  out.interactions.reports = { afterText: await text(), errs: [...errs] }
  await shot('act-reports')
} catch (e) { out.interactions.reports = { error: e.message } }

// 4. Ask Kinetix copilot
try {
  errs.length = 0
  await page.getByText('Ask Kinetix', { exact: false }).first().click({ timeout: 8000 })
  await page.waitForTimeout(2500)
  await shot('act-copilot-open')
  const input = page.locator('textarea, input[type="text"]').last()
  if (await input.count()) {
    await input.fill('What is my current VaR and biggest risk?')
    await input.press('Enter')
    await page.waitForTimeout(12000)
  }
  out.interactions.copilot = { afterText: await text(), errs: [...errs] }
  await shot('act-copilot-answer')
} catch (e) { out.interactions.copilot = { error: e.message } }

// 5. Positions — expand a row
try {
  await go('positions')
  errs.length = 0
  const row = page.locator('tbody tr').first()
  if (await row.count()) { await row.click({ timeout: 6000 }).catch(()=>{}); await page.waitForTimeout(2500) }
  await shot('act-position-expand')
  out.interactions.positionExpand = { errs: [...errs] }
} catch (e) { out.interactions.positionExpand = { error: e.message } }

// === GRAFANA ===
const dashes = [
  ['risk-overview','kinetix-risk-overview/risk-overview'],
  ['service-logs','kinetix-service-logs/service-logs'],
  ['system-health','kinetix-system-health/system-health'],
  ['trade-flow','kinetix-trade-flow/trade-flow'],
  ['risk-engine','kinetix-risk-engine/risk-engine'],
  ['kafka-health','kinetix-kafka-health/kafka-health'],
  ['api-gateway','kinetix-gateway/api-gateway'],
  ['pnl','kinetix-pnl/pandl'],
]
for (const [name, path] of dashes) {
  try {
    await page.goto(`${GRAF}/d/${path}?from=now-24h&to=now&refresh=`, { waitUntil: 'networkidle', timeout: 45000 })
    await page.waitForTimeout(7000)
    const t = await page.evaluate(() => document.body.innerText)
    const noData = (t.match(/No data/gi) || []).length
    const panelErr = (t.match(/error|failed|datasource/gi) || []).length
    out.grafana[name] = { noDataPanels: noData, panelErrMentions: panelErr, sample: t.slice(0, 600) }
    await page.screenshot({ path: `${SHOTS}/graf-${name}.png` }).catch(()=>{})
    console.error('grafana', name, 'noData=', noData)
  } catch (e) { out.grafana[name] = { error: e.message } }
}

fs.writeFileSync(`${OUT}/interact.json`, JSON.stringify(out, null, 2))
console.error('DONE')
await browser.close()

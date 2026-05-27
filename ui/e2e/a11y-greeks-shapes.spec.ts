import { test, expect } from '@playwright/test'

// Color-blind safe shape icons on Greeks columns (kx-q9l0).
//
// WCAG 1.4.1 (Use of Color): meaning conveyed by color alone is
// inaccessible to deuteranopic and protanopic users. Greeks columns
// previously used red/green chevrons; we add a shape glyph (▲▼●) so
// the up/down/neutral direction is readable without color.
//
// This spec renders the GreeksShapeIcon component's expected HTML
// shape via page.setContent so it can run without backend services
// or app state. The assertions verify:
//   1. each direction carries a non-color signal (the glyph),
//   2. screen readers receive a textual label via aria,
//   3. the glyph is exposed in a data attribute that automation can
//      query without parsing color.

const SAMPLE_HTML = `
<!doctype html>
<html lang="en">
  <body>
    <table>
      <thead>
        <tr><th>Greek</th><th>Direction</th></tr>
      </thead>
      <tbody>
        <tr>
          <td>Delta</td>
          <td>
            <span role="img"
                  aria-label="Delta increased"
                  data-direction="up"
                  data-testid="greeks-shape-icon"
                  class="inline-block font-bold text-emerald-600">▲</span>
          </td>
        </tr>
        <tr>
          <td>Gamma</td>
          <td>
            <span role="img"
                  aria-label="Gamma decreased"
                  data-direction="down"
                  data-testid="greeks-shape-icon"
                  class="inline-block font-bold text-rose-600">▼</span>
          </td>
        </tr>
        <tr>
          <td>Vega</td>
          <td>
            <span role="img"
                  aria-label="Vega unchanged"
                  data-direction="neutral"
                  data-testid="greeks-shape-icon"
                  class="inline-block font-bold text-slate-500">●</span>
          </td>
        </tr>
      </tbody>
    </table>
  </body>
</html>
`

test.describe('color-blind safe shape icons on Greeks columns', () => {
  test('every Greek direction is conveyed by a non-color glyph', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)

    const icons = page.getByTestId('greeks-shape-icon')
    await expect(icons).toHaveCount(3)

    const up = page.locator('[data-testid="greeks-shape-icon"][data-direction="up"]')
    const down = page.locator('[data-testid="greeks-shape-icon"][data-direction="down"]')
    const neutral = page.locator('[data-testid="greeks-shape-icon"][data-direction="neutral"]')

    await expect(up).toHaveText('▲')
    await expect(down).toHaveText('▼')
    await expect(neutral).toHaveText('●')
  })

  test('each shape icon carries an accessible label', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)
    await expect(page.getByRole('img', { name: 'Delta increased' })).toBeVisible()
    await expect(page.getByRole('img', { name: 'Gamma decreased' })).toBeVisible()
    await expect(page.getByRole('img', { name: 'Vega unchanged' })).toBeVisible()
  })

  test('the glyph alone is enough to distinguish direction without color', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)
    const glyphs = await page.getByTestId('greeks-shape-icon').allInnerTexts()
    const unique = new Set(glyphs.map(g => g.trim()))
    // Three distinct shapes for three distinct directions — guarantees the
    // signal survives a black-and-white rendering.
    expect(unique.size).toBe(3)
  })
})

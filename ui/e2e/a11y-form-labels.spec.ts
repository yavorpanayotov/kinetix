import { test, expect } from '@playwright/test'

// Form labels associated with inputs via htmlFor (kx-wwip).
//
// WCAG 1.3.1 (Info and Relationships) and 4.1.2 (Name, Role, Value):
// every form control must have a programmatically determinable label so
// assistive tech can announce the field name when focus lands on it.
// Wrapping the input inside the <label> works, but adopting the explicit
// `htmlFor` / `id` pairing is the more portable pattern and is what we
// audit here. The spec renders the FormLabelledInput component's expected
// HTML shape via page.setContent so it can run without backend services
// or app state. The assertions verify:
//   1. each <label> targets a real input via htmlFor / id,
//   2. clicking the label focuses the linked input (the canonical screen-
//      reader / keyboard behaviour),
//   3. getByLabel locates every input — proving accessible-name resolution.

const SAMPLE_HTML = `
<!doctype html>
<html lang="en">
  <body>
    <form>
      <div>
        <label for="trade-symbol">Symbol</label>
        <input id="trade-symbol" name="symbol" type="text" />
      </div>
      <div>
        <label for="trade-quantity">Quantity</label>
        <input id="trade-quantity" name="quantity" type="number" />
      </div>
      <div>
        <label for="trade-side">Side</label>
        <select id="trade-side" name="side">
          <option>BUY</option>
          <option>SELL</option>
        </select>
      </div>
      <div>
        <label for="trade-comment">Comment</label>
        <textarea id="trade-comment" name="comment"></textarea>
      </div>
    </form>
  </body>
</html>
`

test.describe('form labels associate with inputs via htmlFor', () => {
  test('every label points at a real input id via htmlFor', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)
    const labels = page.locator('label')
    const count = await labels.count()
    expect(count).toBeGreaterThan(0)
    for (let i = 0; i < count; i++) {
      const htmlFor = await labels.nth(i).getAttribute('for')
      expect(htmlFor, 'label is missing for=').not.toBeNull()
      const target = page.locator(`#${htmlFor}`)
      await expect(target).toHaveCount(1)
    }
  })

  test('clicking each label focuses the linked input', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)

    await page.getByText('Symbol').click()
    await expect(page.locator('#trade-symbol')).toBeFocused()

    await page.getByText('Quantity').click()
    await expect(page.locator('#trade-quantity')).toBeFocused()

    await page.getByText('Comment').click()
    await expect(page.locator('#trade-comment')).toBeFocused()
  })

  test('getByLabel resolves every form control', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)
    await expect(page.getByLabel('Symbol')).toBeVisible()
    await expect(page.getByLabel('Quantity')).toBeVisible()
    await expect(page.getByLabel('Side')).toBeVisible()
    await expect(page.getByLabel('Comment')).toBeVisible()
  })

  test('no input lacks an accessible name', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)
    const inputs = page.locator('input, select, textarea')
    const total = await inputs.count()
    expect(total).toBeGreaterThan(0)
    for (let i = 0; i < total; i++) {
      const id = await inputs.nth(i).getAttribute('id')
      expect(id, 'input is missing id').not.toBeNull()
      const labelCount = await page.locator(`label[for="${id}"]`).count()
      expect(labelCount, `no label points at id=${id}`).toBe(1)
    }
  })
})

import { test, expect } from '@playwright/test'

// Focus trap on explainer modal panels (kx-yrqx).
//
// WAI-ARIA APG (Authoring Practices Guide) for the dialog pattern requires:
//   1. focus moves into the dialog when it opens,
//   2. Tab from the last focusable wraps to the first,
//   3. Shift+Tab from the first focusable wraps to the last,
//   4. focus returns to the trigger when the dialog closes.
// This spec exercises those rules against a self-contained HTML fixture
// (mirroring the existing a11y-form-labels pattern) so it runs without
// backend services or app state and remains deterministic in CI. The same
// contract is enforced inside the app by ExplainerModalFocusTrap.tsx.

const FIXTURE_HTML = `
<!doctype html>
<html lang="en">
  <body>
    <button id="trigger" type="button">Open explainer</button>
    <button id="outside" type="button">Outside button</button>
    <div id="dialog" role="dialog" aria-modal="true" aria-label="Explainer" hidden tabindex="-1">
      <button id="first" type="button">First</button>
      <button id="middle" type="button">Middle</button>
      <button id="last" type="button">Last</button>
      <button id="close" type="button">Close</button>
    </div>
    <script>
      (function () {
        const FOCUSABLE = 'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';
        const dialog = document.getElementById('dialog');
        const trigger = document.getElementById('trigger');
        const close = document.getElementById('close');
        let previouslyFocused = null;

        function focusableInDialog() {
          return Array.from(dialog.querySelectorAll(FOCUSABLE))
            .filter(el => !el.hasAttribute('disabled') && !el.hidden);
        }

        function open() {
          previouslyFocused = document.activeElement;
          dialog.hidden = false;
          const items = focusableInDialog();
          (items[0] || dialog).focus();
          document.addEventListener('keydown', onKeyDown);
          document.addEventListener('focusin', onFocusIn);
        }

        function closeDialog() {
          document.removeEventListener('keydown', onKeyDown);
          document.removeEventListener('focusin', onFocusIn);
          dialog.hidden = true;
          if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
            previouslyFocused.focus();
          }
        }

        function onKeyDown(e) {
          if (e.key === 'Escape') {
            e.preventDefault();
            closeDialog();
            return;
          }
          if (e.key !== 'Tab') return;
          const items = focusableInDialog();
          if (items.length === 0) {
            e.preventDefault();
            dialog.focus();
            return;
          }
          const first = items[0];
          const last = items[items.length - 1];
          const active = document.activeElement;
          if (e.shiftKey) {
            if (active === first || !dialog.contains(active)) {
              e.preventDefault();
              last.focus();
            }
          } else {
            if (active === last || !dialog.contains(active)) {
              e.preventDefault();
              first.focus();
            }
          }
        }

        function onFocusIn(e) {
          if (!dialog.contains(e.target)) {
            const items = focusableInDialog();
            (items[0] || dialog).focus();
          }
        }

        trigger.addEventListener('click', open);
        close.addEventListener('click', closeDialog);
      })();
    </script>
  </body>
</html>
`

test.describe('explainer modal focus trap', () => {
  test('moves focus into the dialog when it opens', async ({ page }) => {
    await page.setContent(FIXTURE_HTML)
    await page.locator('#trigger').click()
    await expect(page.locator('#first')).toBeFocused()
  })

  test('wraps Tab from the last focusable back to the first', async ({ page }) => {
    await page.setContent(FIXTURE_HTML)
    await page.locator('#trigger').click()
    await page.locator('#close').focus()
    await page.keyboard.press('Tab')
    await expect(page.locator('#first')).toBeFocused()
  })

  test('wraps Shift+Tab from the first focusable to the last', async ({ page }) => {
    await page.setContent(FIXTURE_HTML)
    await page.locator('#trigger').click()
    await page.locator('#first').focus()
    await page.keyboard.press('Shift+Tab')
    await expect(page.locator('#close')).toBeFocused()
  })

  test('pulls stray focus back inside when an outside element steals focus', async ({ page }) => {
    await page.setContent(FIXTURE_HTML)
    await page.locator('#trigger').click()
    // Programmatically focus an element outside the dialog — simulates a
    // background widget that races for focus while the modal is open.
    await page.locator('#outside').focus()
    await expect(page.locator('#first')).toBeFocused()
  })

  test('Escape closes the dialog and restores focus to the trigger', async ({ page }) => {
    await page.setContent(FIXTURE_HTML)
    await page.locator('#trigger').click()
    await expect(page.locator('#dialog')).toBeVisible()

    await page.keyboard.press('Escape')

    await expect(page.locator('#dialog')).toBeHidden()
    await expect(page.locator('#trigger')).toBeFocused()
  })

  test('Close button restores focus to the trigger', async ({ page }) => {
    await page.setContent(FIXTURE_HTML)
    await page.locator('#trigger').click()
    await page.locator('#close').click()
    await expect(page.locator('#trigger')).toBeFocused()
  })
})

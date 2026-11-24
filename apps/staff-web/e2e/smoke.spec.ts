import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

/**
 * Synthetic end-to-end and WCAG 2.2 AA smoke checks for the staff web
 * (WEB-10, UX-01). No real patient data, endpoint or credential is used.
 */

/** The one required keyboard affordance checked by the first test. */
const skipLink = 'a.skip-link';

/** Elements that participate in sequential keyboard order, in DOM order. */
const tabbableSelector = [
  'a[href]',
  'area[href]',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  'button:not([disabled])',
  'iframe',
  '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Run axe against the current page and keep only the findings that block this
 * suite. Moderate and minor findings are reported by the full accessibility
 * review; serious and critical findings fail the build (WEB-10).
 */
async function expectNoBlockingViolations(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  );
  expect(blocking.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([]);
}

test('home page has the expected title and the skip link is first in tab order', async ({
  page,
}) => {
  await page.goto('/');
  await expect(page).toHaveTitle('Orevia Staff Portal');

  const firstTabStopClass = await page.evaluate(
    (selector) => document.querySelector(selector)?.getAttribute('class') ?? null,
    tabbableSelector,
  );
  expect(firstTabStopClass).toContain('skip-link');

  // The shell programmatically focuses <main> on load, which leaves the
  // sequential focus starting point there. Reset it to the document start so
  // one Tab proves the skip link is the first stop a keyboard user reaches.
  await page.evaluate(() => {
    const body = document.body;
    body.setAttribute('tabindex', '-1');
    body.focus();
  });
  await page.keyboard.press('Tab');
  await expect(page.locator(skipLink)).toBeFocused();
  await expectNoBlockingViolations(page);
});

test('design preview has no serious or critical accessibility violations', async ({ page }) => {
  await page.goto('/design-preview');
  await expect(page.locator('main#main h2').first()).toBeVisible();
  await expectNoBlockingViolations(page);
});

test('language toggle switches the document to right-to-left without violations', async ({
  page,
}) => {
  await page.goto('/design-preview');
  await expect(page.locator('main#main h2').first()).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');

  // The application owns locale in the I18nProvider, not a URL parameter, so
  // the visible toggle is the supported way to reach Arabic.
  await page.getByRole('button', { name: 'Language' }).click();

  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  expect(await page.evaluate(() => document.dir)).toBe('rtl');
  await expectNoBlockingViolations(page);
});

test('compact 768 px layout has no horizontal scroll', async ({ page }) => {
  await page.setViewportSize({ width: 768, height: 900 });
  await page.goto('/design-preview');
  await expect(page.locator('main#main h2').first()).toBeVisible();

  const widths = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
  expect(widths.scrollWidth).toBeLessThanOrEqual(widths.clientWidth);
});

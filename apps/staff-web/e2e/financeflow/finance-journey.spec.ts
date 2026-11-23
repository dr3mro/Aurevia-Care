import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { expectNoBlockingViolations } from '../accessibility';
import { syntheticFacility } from '../../src/fixtures/synthetic';
import { createSyntheticFinanceApi } from './synthetic-finance-api';
import type { SyntheticFinanceApi } from './synthetic-finance-api';
import { postedEntries, reconciliationDate, totalMinorUnits } from './synthetic-finance-api';

/**
 * Bilingual finance journey (WEB-10, FIN-01).
 *
 * No finance screen is routed in this checkout, so a dev-only workstation
 * reads a synthetic daily reconciliation through the real typed boundary
 * shape. A reception role is denied with 403 `FORBIDDEN` and no aggregate is
 * read. Every locale gets a fresh page and store, with serious/critical axe
 * checks on both the successful and the forbidden state.
 */

/** Words asserted verbatim in one locale. */
interface JourneyWords {
  locale: 'en' | 'ar';
  direction: 'ltr' | 'rtl';
  switchToArabic: boolean;
  heading: string;
  roleLabel: string;
  financeRole: string;
  receptionRole: string;
  run: string;
  pending: string;
  totalLabel: string;
  entriesLabel: string;
  forbidden: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  switchToArabic: false,
  heading: 'Daily reconciliation',
  roleLabel: 'Workspace role',
  financeRole: 'finance',
  receptionRole: 'reception',
  run: 'Read reconciliation',
  pending: 'No reconciliation has been read.',
  totalLabel: 'Total in minor units',
  entriesLabel: 'Posted entries',
  forbidden: 'You do not have access to reconciliation.',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  switchToArabic: true,
  heading: 'التسوية اليومية',
  roleLabel: 'دور مساحة العمل',
  financeRole: 'المالية',
  receptionRole: 'الاستقبال',
  run: 'قراءة التسوية',
  pending: 'لم تُقرأ أي تسوية بعد.',
  totalLabel: 'الإجمالي بوحدات صغيرة',
  entriesLabel: 'القيود المُرحّلة',
  forbidden: 'لا تملك صلاحية الوصول إلى التسوية.',
};

/** Open one fresh harness page in the selected synthetic role. */
async function openHarness(
  page: Page,
  api: SyntheticFinanceApi,
  words: JourneyWords,
  role: 'finance' | 'reception',
): Promise<void> {
  await api.installOn(page, undefined);
  const suffix = role === 'finance' ? '' : '?role=reception';
  await page.goto(`/e2e/financeflow/finance-harness.html${suffix}`);
  await expect(page.getByTestId('finance-harness')).toBeVisible();
  if (words.switchToArabic) {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
  await expect(page.getByRole('heading', { name: words.heading })).toBeVisible();
  await expect(page.getByTestId('finance-role')).toHaveText(
    `${words.roleLabel}: ${role === 'finance' ? words.financeRole : words.receptionRole}`,
  );
}

/** Read the totals as finance, then prove the same command denies reception. */
async function runJourney(page: Page, words: JourneyWords): Promise<void> {
  const api = createSyntheticFinanceApi();
  await openHarness(page, api, words, 'finance');
  const status = page.getByTestId('finance-status');

  await test.step('a finance role reads integer-minor-unit totals only', async () => {
    await expect(status).toHaveText(words.pending);
    await page.getByRole('button', { name: words.run }).click();
    await expect(status).toHaveAttribute('data-status', 'shown');
    await expect(status).toContainText(`${words.totalLabel}: ${totalMinorUnits}`);
    await expect(status).toContainText(`${words.entriesLabel}: ${postedEntries}`);
    await expect.poll(() => api.store.reads).toEqual([reconciliationDate]);
    expect(api.store.requests[0]?.organizationId).toBe(syntheticFacility.organizationId);
    expect(api.store.requests[0]?.correlationId).not.toBe('');
    expect(api.store.requests[0]?.csrf).toBe('synthetic-csrf-token');
    expect(api.store.requests[0]?.role).toBe('finance');
    await expectNoBlockingViolations(page);
  });

  await test.step('a reception role is denied and reads no aggregate', async () => {
    const before = api.store.reads.length;
    await page.goto('/e2e/financeflow/finance-harness.html?role=reception');
    await expect(page.getByTestId('finance-harness')).toBeVisible();
    if (words.switchToArabic) {
      await page.getByRole('button', { name: 'العربية' }).click();
    }
    await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
    await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
    await page.getByRole('button', { name: words.run }).click();
    await expect(status).toHaveText(words.forbidden);
    await expect(status).toHaveAttribute('data-status', 'forbidden');
    expect(api.store.reads).toHaveLength(before);
    expect(api.store.requests.at(-1)?.role).toBe('reception');
    const denied = await page.evaluate(async (date) => {
      const response = await fetch(`/api/v1/finance/reconciliation?date=${date}`, {
        headers: {
          'X-Active-Organization': '11111111-1111-1111-1111-111111111111',
          'X-Correlation-Id': 'synthetic-correlation-finance',
          'X-XSRF-TOKEN': 'synthetic-csrf-token',
          'X-Active-Role': 'reception',
        },
      });
      const body: unknown = await response.json();
    return { status: response.status, body };
    }, reconciliationDate);
    expect(denied.status).toBe(403);
    expect(denied.body).toEqual({ code: 'FORBIDDEN' });
    expect(api.store.reads).toHaveLength(before);
    await expectNoBlockingViolations(page);
  });
}

test('finance reads reconciliation and denies reception in English', async ({ page }) => {
  await runJourney(page, english);
});

test('finance reads reconciliation and denies reception in Arabic RTL', async ({ page }) => {
  await runJourney(page, arabic);
});

test('finance denies a reconciliation read without the boundary headers', async ({ page }) => {
  const api = createSyntheticFinanceApi();
  await openHarness(page, api, english, 'finance');
  const denied = await page.evaluate(async (date) => {
    const response = await fetch(`/api/v1/finance/reconciliation?date=${date}`);
    const body: unknown = await response.json();
    return { status: response.status, body };
  }, reconciliationDate);
  expect(denied.status).toBe(403);
  expect(denied.body).toEqual({ code: 'FORBIDDEN' });
  const missingPath = await page.evaluate(async () => {
    const response = await fetch('/api/v1/finance/unknown', {
      headers: {
        'X-Active-Organization': '11111111-1111-1111-1111-111111111111',
        'X-Correlation-Id': 'synthetic-correlation-finance',
        'X-XSRF-TOKEN': 'synthetic-csrf-token',
        'X-Active-Role': 'finance',
      },
    });
    const body: unknown = await response.json();
    return { status: response.status, body };
  });
  expect(missingPath.status).toBe(404);
  expect(missingPath.body).toEqual({ code: 'NOT_FOUND' });
  expect(api.store.reads).toHaveLength(0);
});

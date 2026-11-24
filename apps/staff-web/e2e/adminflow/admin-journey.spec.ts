import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { expectNoBlockingViolations } from '../accessibility';
import { syntheticFacility } from '../../src/fixtures/synthetic';
import { checksum, createSyntheticAdminApi, jobId, recordCount } from './synthetic-admin-api';
import type { SyntheticAdminApi } from './synthetic-admin-api';

/**
 * Bilingual tenant-admin journey (WEB-10, OPS-07).
 *
 * The real export screen is mounted in a dev-only harness with its real
 * providers and typed client; only `/api/v1/**` is stubbed. The same export
 * command succeeds for the tenant administrator and is denied for a reception
 * role, both in the screen and at the boundary. Every locale gets a fresh
 * page and store, with serious/critical axe checks on both states.
 */

/** Words asserted verbatim in one locale. */
interface JourneyWords {
  locale: 'en' | 'ar';
  direction: 'ltr' | 'rtl';
  switchToArabic: boolean;
  title: string;
  noExports: string;
  start: string;
  started: string;
  download: string;
  downloaded: string;
  forbidden: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  switchToArabic: false,
  title: 'Tenant export',
  noExports: 'No exports have been started in this session.',
  start: 'Start export',
  started: 'Export started. The job is in the download list.',
  download: 'Download manifest',
  downloaded: 'Manifest download requested.',
  forbidden: 'You do not have access',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  switchToArabic: true,
  title: 'تصدير المستأجر',
  noExports: 'لم يُبدأ أي تصدير في هذه الجلسة.',
  start: 'بدء التصدير',
  started: 'بدأ التصدير. المهمة الآن في قائمة التنزيل.',
  download: 'تنزيل البيان',
  downloaded: 'تم طلب تنزيل البيان.',
  forbidden: 'لا تملك صلاحية الوصول',
};

/** Open one fresh harness page in the selected role and locale. */
async function openHarness(
  page: Page,
  api: SyntheticAdminApi,
  words: JourneyWords,
  reception: boolean,
): Promise<void> {
  await api.installOn(page, undefined);
  await page.goto(`/e2e/adminflow/admin-harness.html${reception ? '?role=reception' : ''}`);
  await expect(page.getByTestId('admin-harness')).toBeVisible();
  if (words.switchToArabic) {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
}

/** Start and download a synthetic export, then deny the same command as reception. */
async function runJourney(page: Page, words: JourneyWords): Promise<void> {
  const api = createSyntheticAdminApi();
  await openHarness(page, api, words, false);

  await test.step('the tenant admin starts and downloads one export', async () => {
    await expect(page.getByRole('heading', { name: words.title })).toBeVisible();
    await expect(page.getByText(words.noExports)).toBeVisible();
    await page.getByRole('button', { name: words.start }).click();
    await expect(page.getByRole('status')).toContainText(words.started);
    await expect(page.getByText(jobId)).toBeVisible();
    await expect(page.getByText(checksum)).toBeVisible();
    await expect(page.getByText(String(recordCount), { exact: true })).toBeVisible();
    await expect.poll(() => api.store.exports).toEqual([jobId]);
    const write = api.store.requests[0];
    expect(api.store.calls).toContain('POST /api/v1/tenant/exports');
    expect(write?.organizationId).toBe(syntheticFacility.organizationId);
    expect(write?.correlationId).not.toBe('');
    expect(write?.csrf).toBe('synthetic-csrf-token');
    expect(write?.idempotencyKey).not.toBe('');
    await expectNoBlockingViolations(page);

    await page.getByRole('button', { name: words.download }).click();
    await expect(page.getByRole('status')).toContainText(words.downloaded);
    expect(api.store.calls).toContain(`GET /api/v1/tenant/exports/${jobId}`);
    expect(api.store.requests[1]?.organizationId).toBe(syntheticFacility.organizationId);
    // A direct read that names the tenant-admin role takes the alternate
    // authorization branch and returns the same synthetic manifest.
    const namedRoleRead = await page.evaluate(async (identifier) => {
      const response = await fetch(`/api/v1/tenant/exports/${identifier}`, {
        headers: {
          'X-Active-Organization': '11111111-1111-1111-1111-111111111111',
          'X-Correlation-Id': 'synthetic-correlation-admin',
          'X-XSRF-TOKEN': 'synthetic-csrf-token',
          'X-Active-Role': 'tenant_admin',
        },
      });
      const body: unknown = await response.json();
    return { status: response.status, body };
    }, jobId);
    expect(namedRoleRead.status).toBe(200);
    expect(namedRoleRead.body).toEqual({ job_id: jobId, checksum });
    await expectNoBlockingViolations(page);
  });

  await test.step('the same command is denied for a reception role', async () => {
    const before = api.store.exports.length;
    const callsBefore = api.store.calls.length;
    await openHarness(page, api, words, true);
    await expect(page.getByRole('heading', { name: words.forbidden })).toBeVisible();
    await expect(page.getByRole('button', { name: words.start })).toHaveCount(0);
    // The forbidden screen performs no export command at all: the reception
    // role is refused before the typed client is called.
    expect(api.store.calls).toHaveLength(callsBefore);

    const denied = await page.evaluate(async () => {
      const response = await fetch('/api/v1/tenant/exports', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Active-Organization': '11111111-1111-1111-1111-111111111111',
          'X-Correlation-Id': 'synthetic-correlation-admin',
          'X-XSRF-TOKEN': 'synthetic-csrf-token',
          'Idempotency-Key': 'synthetic-reception-export-attempt',
          'X-Active-Role': 'reception',
        },
        body: '{}',
      });
      const body: unknown = await response.json();
    return { status: response.status, body };
    });
    expect(denied.status).toBe(403);
    expect(denied.body).toEqual({ code: 'FORBIDDEN' });

    // The same role cannot read the manifest either: the GET denial branch is
    // as important as the command denial for tenant data protection.
    const deniedRead = await page.evaluate(async (identifier) => {
      const response = await fetch(`/api/v1/tenant/exports/${identifier}`, {
        headers: {
          'X-Active-Organization': '11111111-1111-1111-1111-111111111111',
          'X-Correlation-Id': 'synthetic-correlation-admin',
          'X-XSRF-TOKEN': 'synthetic-csrf-token',
          'X-Active-Role': 'reception',
        },
      });
      const body: unknown = await response.json();
    return { status: response.status, body };
    }, jobId);
    expect(deniedRead.status).toBe(403);
    expect(deniedRead.body).toEqual({ code: 'FORBIDDEN' });
    expect(api.store.exports).toHaveLength(before);
    await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
    await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
    await expectNoBlockingViolations(page);
  });
}

test('tenant admin exports and reception is denied in English', async ({ page }) => {
  await runJourney(page, english);
});

test('tenant admin exports and reception is denied in Arabic RTL', async ({ page }) => {
  await runJourney(page, arabic);
});

test('tenant export denies a command without the boundary headers', async ({ page }) => {
  const api = createSyntheticAdminApi();
  await openHarness(page, api, english, false);
  const denied = await page.evaluate(async () => {
    const response = await fetch('/api/v1/tenant/exports', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
    const body: unknown = await response.json();
    return { status: response.status, body };
  });
  expect(denied.status).toBe(403);
  expect(denied.body).toEqual({ code: 'FORBIDDEN' });
  const missingPath = await page.evaluate(async () => {
    const response = await fetch('/api/v1/tenant/unknown', {
      headers: {
        'X-Active-Organization': '11111111-1111-1111-1111-111111111111',
        'X-Correlation-Id': 'synthetic-correlation-admin',
        'X-XSRF-TOKEN': 'synthetic-csrf-token',
        'Idempotency-Key': 'synthetic-unknown-command',
      },
    });
    const body: unknown = await response.json();
    return { status: response.status, body };
  });
  expect(missingPath.status).toBe(404);
  expect(missingPath.body).toEqual({ code: 'NOT_FOUND' });
  expect(api.store.exports).toHaveLength(0);
});

import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { expectNoBlockingViolations } from '../accessibility';
import { syntheticFacility } from '../../src/fixtures/synthetic';
import { createSyntheticTherapyApi } from './synthetic-therapy-api';
import type { SyntheticTherapyApi } from './synthetic-therapy-api';
import { expectedPatientId } from './synthetic-therapy-api';

/**
 * Bilingual physical-therapy journey (WEB-10, PT-01).
 *
 * No therapy screen is routed in this checkout, so the harness mounts a
 * dev-only workstation under the real providers and a synthetic
 * `/api/v1/therapy/**` boundary. Each locale gets a fresh page and store, and
 * asserts an accepted episode, a refused mismatched-patient episode, the
 * boundary headers, right-to-left direction and zero serious/critical axe
 * findings. No real patient identifier or credential is used.
 */

/** Words asserted verbatim in one locale. */
interface JourneyWords {
  locale: 'en' | 'ar';
  direction: 'ltr' | 'rtl';
  switchToArabic: boolean;
  heading: string;
  patientLabel: string;
  planLabel: string;
  accept: string;
  pending: string;
  accepted: string;
  refused: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  switchToArabic: false,
  heading: 'Therapy referral',
  patientLabel: 'Patient alias from the referral',
  planLabel: 'Synthetic plan code',
  accept: 'Accept episode',
  pending: 'No episode has been accepted.',
  accepted: 'Episode accepted.',
  refused: 'Episode refused: the patient alias does not match this referral.',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  switchToArabic: true,
  heading: 'إحالة العلاج الطبيعي',
  patientLabel: 'معرّف المريض من الإحالة',
  planLabel: 'رمز الخطة التوضيحية',
  accept: 'قبول الحلقة',
  pending: 'لم تُقبل أي حلقة بعد.',
  accepted: 'قُبلت الحلقة.',
  refused: 'رُفضت الحلقة: معرّف المريض لا يطابق هذه الإحالة.',
};

/** Open one fresh harness page and its synthetic boundary. */
async function openHarness(page: Page, api: SyntheticTherapyApi, words: JourneyWords) {
  await api.installOn(page, undefined);
  await page.goto('/e2e/therapyflow/therapy-harness.html');
  await expect(page.getByTestId('therapy-harness')).toBeVisible();
  if (words.switchToArabic) {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
  await expect(page.getByRole('heading', { name: words.heading })).toBeVisible();
}

/** Accept the matching referral, then prove a mismatched alias writes nothing. */
async function runJourney(page: Page, words: JourneyWords): Promise<void> {
  const api = createSyntheticTherapyApi();
  await openHarness(page, api, words);
  const status = page.getByTestId('therapy-status');

  await test.step('the matching referral is accepted at the boundary', async () => {
    await expect(status).toHaveText(words.pending);
    await page.getByLabel(words.patientLabel).fill(expectedPatientId);
    await page.getByLabel(words.planLabel).fill('PT-SYNTHETIC-PLAN');
    await page.getByRole('button', { name: words.accept }).click();
    await expect(status).toHaveText(words.accepted);
    await expect(status).toHaveAttribute('data-status', 'accepted');
    await expect.poll(() => api.store.episodes).toHaveLength(1);
    expect(api.store.episodes[0]?.state).toBe('accepted');
    expect(api.store.episodes[0]?.plan).toBe('PT-SYNTHETIC-PLAN');
    expect(api.store.requests[0]?.organizationId).toBe(syntheticFacility.organizationId);
    expect(api.store.requests[0]?.correlationId).not.toBe('');
    expect(api.store.requests[0]?.csrf).toBe('synthetic-csrf-token');
    await expectNoBlockingViolations(page);
  });

  await test.step('a mismatched patient alias is refused and the store is unchanged', async () => {
    const before = api.store.episodes.length;
    await page.getByLabel(words.patientLabel).fill('SYN-PT-PATIENT-999');
    await page.getByRole('button', { name: words.accept }).click();
    await expect(status).toHaveText(words.refused);
    await expect(status).toHaveAttribute('data-status', 'refused');
    expect(api.store.episodes).toHaveLength(before);
    expect(api.store.episodes.map((episode) => episode.patientId)).not.toContain(
      'SYN-PT-PATIENT-999',
    );
    expect(api.store.calls).toHaveLength(2);
    await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
    await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
    await expectNoBlockingViolations(page);
  });
}

test('therapy accepts a matched referral and refuses a mismatch in English', async ({ page }) => {
  await runJourney(page, english);
});

test('therapy accepts a matched referral and refuses a mismatch in Arabic RTL', async ({ page }) => {
  await runJourney(page, arabic);
});

test('therapy refuses a command without the synthetic boundary headers', async ({ page }) => {
  const api = createSyntheticTherapyApi();
  await openHarness(page, api, english);
  const refused = await page.evaluate(async () => {
    const response = await fetch('/api/v1/therapy/episodes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ patientId: 'SYN-PT-PATIENT-001', plan: 'PT-SYNTHETIC-PLAN' }),
    });
    const body: unknown = await response.json();
      return { status: response.status, body };
  });
  expect(refused.status).toBe(403);
  expect(refused.body).toEqual({ code: 'FORBIDDEN' });
  const missingPath = await page.evaluate(async () => {
    const response = await fetch('/api/v1/therapy/unknown', {
      headers: {
        'X-Active-Organization': '11111111-1111-1111-1111-111111111111',
        'X-Correlation-Id': 'synthetic-correlation-therapy',
        'X-XSRF-TOKEN': 'synthetic-csrf-token',
      },
    });
    const body: unknown = await response.json();
    return { status: response.status, body };
  });
  expect(missingPath.status).toBe(404);
  expect(missingPath.body).toEqual({ code: 'NOT_FOUND' });
  expect(api.store.episodes).toHaveLength(0);
  await expect(page.getByTestId('therapy-status')).toHaveText(english.pending);
});

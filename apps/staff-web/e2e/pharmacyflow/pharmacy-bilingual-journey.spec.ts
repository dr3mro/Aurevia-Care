import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { expectNoBlockingViolations } from '../accessibility';
import { createSyntheticPharmacyApi } from './synthetic-pharmacy-api';
import type { SyntheticPharmacyApi } from './synthetic-pharmacy-api';

/**
 * Bilingual pharmacy workflow journey (WEB-10, PHARM-08, RX-02).
 *
 * The real `/pharmacy` route is denied by the pre-login reception role, so the
 * harness mounts the real inbox item screen with its production providers and
 * typed API client. Only the `/api/v1/pharmacy/**` boundary is stubbed, with
 * one shared append-only fact trail. This test is additive: the existing
 * stale-`If-Match` test in `pharmacy-journey.spec.ts` stays untouched.
 */

/** Words asserted verbatim in one locale. */
interface JourneyWords {
  locale: 'en' | 'ar';
  direction: 'ltr' | 'rtl';
  switchToArabic: boolean;
  route: string;
  routed: string;
  detail: string;
  accept: string;
  beginDispensing: string;
  recordEvidence: string;
  acceptedNo: string;
  acceptedYes: string;
  dispensedNo: string;
  dispensedYes: string;
  commandDone: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  switchToArabic: false,
  route: 'Prescribe and route',
  routed: 'Delivery confirmed',
  detail: 'Prescription detail',
  accept: 'Accept',
  beginDispensing: 'Record dispensing evidence',
  recordEvidence: 'Record evidence',
  acceptedNo: 'Accepted: No',
  acceptedYes: 'Accepted: Yes',
  dispensedNo: 'Dispensed: No',
  dispensedYes: 'Dispensed: Yes',
  commandDone: 'Command sent to the pharmacy API',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  switchToArabic: true,
  route: 'وصف وتوجيه',
  routed: 'تم تأكيد التسليم',
  detail: 'تفاصيل الوصفة',
  accept: 'قبول',
  beginDispensing: 'تسجيل بيانات الصرف',
  recordEvidence: 'تسجيل البيانات',
  acceptedNo: 'مقبولة: لا',
  acceptedYes: 'مقبولة: نعم',
  dispensedNo: 'مُصروفة: لا',
  dispensedYes: 'مُصروفة: نعم',
  commandDone: 'تم إرسال الأمر إلى خدمة الصيدلية',
};

/** Open the harness, switch locale and route one synthetic prescription. */
async function openRoutedHarness(page: Page, api: SyntheticPharmacyApi, words: JourneyWords) {
  await page.goto('/e2e/pharmacyflow/pharmacy-bilingual-harness.html');
  await expect(page.getByTestId('pharmacy-bilingual-harness')).toBeVisible();
  if (words.switchToArabic) {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);

  await expect(page.getByTestId('pharmacy-waiting')).toBeVisible();
  await page.getByRole('button', { name: words.route }).click();
  await expect(page.getByTestId('pharmacy-route-status')).toHaveText(words.routed);
  await expect.poll(() => api.store.calls).toContain('POST /api/v1/pharmacy/inbox/route');
  await expect(page.getByRole('heading', { level: 2, name: words.detail })).toBeVisible();
  await expect(page.getByTestId('accepted-flag')).toHaveText(words.acceptedNo);
  await expect(page.getByTestId('dispensed-flag')).toHaveText(words.dispensedNo);
}

/** Drive the accept-and-dispense journey with one forbidden command first. */
async function runJourney(page: Page, words: JourneyWords): Promise<void> {
  const api = createSyntheticPharmacyApi();
  await api.installOn(page, undefined);
  await openRoutedHarness(page, api, words);

  await test.step('dispensing before acceptance writes no fact and leaves the flags unchanged', async () => {
    const before = api.store.events.length;
    const refused = await page.evaluate(async () => {
      const response = await fetch(
        '/api/v1/pharmacy/status/rx-synthetic-001/dispensing-evidence',
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Active-Organization': '11111111-1111-1111-1111-111111111111',
            'X-Correlation-Id': 'synthetic-correlation-pharmacy',
            'X-XSRF-TOKEN': 'synthetic-csrf-token',
            'If-Match': '2',
            'Idempotency-Key': 'synthetic-early-dispense-attempt',
          },
          body: JSON.stringify({
            dispensedBy: 'synthetic.pharmacist',
            dispensedAt: '2026-11-22T10:30:00Z',
            handedOver: '30 synthetic tablets',
          }),
        },
      );
      const body: unknown = await response.json();
      return { status: response.status, body };
    });
    expect(refused.status).toBe(409);
    expect(refused.body).toEqual({ code: 'INVALID_TRANSITION' });
    expect(api.store.events).toHaveLength(before);
    expect(api.store.events.map((event) => event.type)).not.toContain('dispensed');
    expect(api.store.prescription?.state).toBe('delivery_confirmed');
    await expect(page.getByTestId('accepted-flag')).toHaveText(words.acceptedNo);
    await expect(page.getByTestId('dispensed-flag')).toHaveText(words.dispensedNo);
    await expectNoBlockingViolations(page);
  });

  await test.step('acceptance and dispensing remain separate recorded facts', async () => {
    await page.getByRole('button', { name: words.accept, exact: true }).click();
    await expect(page.getByTestId('accepted-flag')).toHaveText(words.acceptedYes);
    await expect(page.getByTestId('dispensed-flag')).toHaveText(words.dispensedNo);
    expect(api.store.events.map((event) => event.type)).toContain('accepted');
    expect(api.store.events.map((event) => event.type)).not.toContain('dispensed');

    await page.getByRole('button', { name: words.beginDispensing }).click();
    await page.locator('#dispensed-by').fill('synthetic.pharmacist');
    await page.locator('#dispensed-at').fill('2026-11-22T10:30:00Z');
    await page.locator('#handed-over').fill('30 synthetic tablets');
    await page.getByRole('button', { name: words.recordEvidence }).click();
    await expect(page.getByTestId('dispensed-flag')).toHaveText(words.dispensedYes);
    await expect(page.getByTestId('accepted-flag')).toHaveText(words.acceptedYes);
    await expect(page.getByText(words.commandDone)).toBeVisible();
    expect(api.store.events.map((event) => event.type)).toContain('dispensed');
    expect(api.store.prescription?.state).toBe('dispensed');
    expect(page.url()).not.toContain('SYN-PATIENT-001');
    await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
    await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
    await expectNoBlockingViolations(page);
  });
}

test('pharmacy refuses dispensing before acceptance in English', async ({ page }) => {
  await runJourney(page, english);
});

test('pharmacy refuses dispensing before acceptance in Arabic RTL', async ({ page }) => {
  await runJourney(page, arabic);
});

test('a refused route keeps the harness on its waiting state', async ({ page }) => {
  const api = createSyntheticPharmacyApi();
  await api.installOn(page, undefined);
  // A route registered after the stub takes precedence, so the harness sees a
  // denied command and must not open a detail screen for an unrouted recipe.
  await page.route('**/api/v1/pharmacy/inbox/route', async (route) => {
    await route.fulfill({
      status: 403,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'FORBIDDEN' }),
    });
  });
  await page.goto('/e2e/pharmacyflow/pharmacy-bilingual-harness.html');
  await expect(page.getByTestId('pharmacy-waiting')).toBeVisible();
  await page.getByRole('button', { name: english.route }).click();
  await expect(page.getByTestId('pharmacy-route-status')).toHaveText('Refused');
  await expect(page.getByTestId('pharmacy-waiting')).toBeVisible();
  await expect(page.getByRole('heading', { name: english.detail })).toHaveCount(0);
});

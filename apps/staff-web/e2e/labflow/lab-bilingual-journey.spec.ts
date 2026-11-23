import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { expectNoBlockingViolations } from '../accessibility';
import { installSyntheticLabApi } from './synthetic-lab-api';
import type { SyntheticLabApi } from './synthetic-lab-api';

/**
 * Small bilingual laboratory journey: collect a matched specimen and verify its
 * result (WEB-10, LAB-01, LAB-02). It reuses the existing synthetic lab API and
 * mounts the real collection and result-entry pages in a dev-only harness: the
 * pre-login shell hard-codes a reception role and denies lab routes. The
 * existing `lab-journey.spec.ts` is left unchanged; this file adds the missing
 * EN/AR pair and the accessibility gate. Only the `/api/v1/lab/**` boundary is
 * stubbed.
 */

/** Words the interface shows for one locale, asserted verbatim in the browser. */
interface JourneyWords {
  locale: 'en' | 'ar';
  direction: 'ltr' | 'rtl';
  collectionDesk: string;
  confirmNames: string;
  confirmAccession: string;
  mismatchTitle: string;
  matchOk: string;
  recordCollection: string;
  collectionRecorded: string;
  resultTitle: string;
  valueLabel: string;
  verifyRegion: string;
  summaryPrefix: string;
  verifyButton: string;
  verifiedPrefix: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  collectionDesk: 'Specimen collection desk',
  confirmNames: 'I matched both patient names on the wristband and requisition',
  confirmAccession: 'I matched the accession number and issuer on the tube',
  mismatchTitle: 'Identity mismatch — collection is blocked',
  matchOk: 'Patient and accession match. You may continue.',
  recordCollection: 'Record collection',
  collectionRecorded: 'Collection recorded.',
  resultTitle: 'Laboratory result entry and verification',
  valueLabel: 'Result value for Hemoglobin',
  verifyRegion: 'Verification and release summary',
  summaryPrefix: 'Hemoglobin:',
  verifyButton: 'Verify and prepare release',
  verifiedPrefix: 'Verified by synthetic.lab.verifier',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  collectionDesk: 'مكتب سحب العينات',
  confirmNames: 'طابقت اسمَي المريض على السوار وطلب الفحص',
  confirmAccession: 'طابقت رقم التسليم والجهة على الأنبوب',
  mismatchTitle: 'عدم تطابق الهوية — السحب محظور',
  matchOk: 'تطابق المريض والتسليم. يمكنك المتابعة.',
  recordCollection: 'تسجيل السحب',
  collectionRecorded: 'تم تسجيل السحب.',
  resultTitle: 'إدخال نتائج المختبر والتحقق منها',
  valueLabel: 'قيمة نتيجة Hemoglobin',
  verifyRegion: 'التحقق وملخص الإصدار',
  summaryPrefix: 'النتيجة Hemoglobin:',
  verifyButton: 'تحقق وجهّز الإصدار',
  verifiedPrefix: 'تم التحقق بواسطة synthetic.lab.verifier',
};

/** The collect command the stub records for the synthetic accession. */
const collectCommand = 'POST /api/v1/lab/specimens/accession-100/collect';

/**
 * Open the harness and switch the document to the journey's locale. The harness
 * owns the direction for the whole journey, so the in-harness screen change does
 * not reset it.
 */
async function openHarness(page: Page, words: JourneyWords): Promise<void> {
  await page.goto('/e2e/labflow/lab-harness.html');
  await expect(page.getByTestId('lab-harness')).toBeVisible();
  if (words.locale === 'ar') {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
}

/** Drive the collect and verify journey in the chosen locale. */
async function runJourney(page: Page, api: SyntheticLabApi, words: JourneyWords): Promise<void> {
  await openHarness(page, words);

  await test.step('a mismatched identity blocks collection', async () => {
    await expect(page.getByRole('heading', { name: words.collectionDesk })).toBeVisible();

    await page.locator('#lab-find-query').fill('ACC-100');
    await page.getByRole('button', { name: new RegExp('ACC-100') }).click();
    await page.locator('#lab-presented-patient').fill('patient-999');
    await page.locator('#lab-presented-accession').fill('ACC-100');
    await page.locator('#lab-presented-issuer').fill('SYN-ISSUER');
    await page.getByLabel(words.confirmNames).check();
    await page.getByLabel(words.confirmAccession).check();

    await expect(page.getByTestId('match-status')).toContainText(words.mismatchTitle);
    await expect(page.getByRole('button', { name: words.recordCollection })).toBeDisabled();
    expect(api.calls).not.toContain(collectCommand);
  });

  await test.step('a matched specimen is collected', async () => {
    await page.locator('#lab-presented-patient').fill('patient-100');
    await expect(page.getByTestId('match-status')).toContainText(words.matchOk);
    await page.locator('#lab-specimen-type').fill('SERUM');
    await page.locator('#lab-site-code').fill('MAIN');
    await page.locator('#lab-collector').fill('synthetic.collector');
    await page.locator('#lab-collected-at').fill('2026-03-09T09:00:00Z');
    await page.locator('#lab-quality').fill('ACCEPTABLE');
    await page.getByRole('button', { name: words.recordCollection }).click();

    await expect(page.getByTestId('collection-status')).toHaveText(words.collectionRecorded);
    await expect.poll(() => api.calls).toContain(collectCommand);
    expect(api.accessions.find((item) => item.id === 'accession-100')?.state).toBe('collected');
    await expectNoBlockingViolations(page);
  });

  await test.step('the entered result is verified', async () => {
    await page.getByTestId('lab-open-results').click();
    await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
    await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
    await expect(page.getByRole('heading', { name: words.resultTitle })).toBeVisible();
    await page.getByRole('textbox', { name: words.valueLabel }).fill('7.6');
    // Verification is a local decision with no command of its own, so prove what
    // would be released: the typed value must reach the summary before it is
    // attested, otherwise the success text alone would pass by construction.
    const verification = page.getByRole('region', { name: words.verifyRegion });
    const summaryItem = verification.getByRole('listitem').filter({ hasText: words.summaryPrefix });
    await expect(summaryItem).toContainText('7.6');
    await verification.getByRole('button', { name: words.verifyButton }).click();
    await expect(page.getByTestId('verification-status')).toContainText(words.verifiedPrefix);
    await expectNoBlockingViolations(page);
  });
}

test('lab collects and verifies in English', async ({ page, baseURL }) => {
  const api = await installSyntheticLabApi(page, baseURL);
  await runJourney(page, api, english);
});

test('lab collects and verifies in Arabic RTL', async ({ page, baseURL }) => {
  const api = await installSyntheticLabApi(page, baseURL);
  await runJourney(page, api, arabic);
});

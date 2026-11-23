import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { expectNoBlockingViolations } from '../accessibility';
import { installSyntheticNoteApi } from './synthetic-note-api';
import type { SyntheticNoteApi } from './synthetic-note-api';

/**
 * Synthetic clinician journey: document, save and sign a note (WEB-10, CLN-05).
 *
 * The real `NotePage` is mounted through a dev-only harness with its real
 * providers and a deterministic route binding. Only the clinical-note and
 * patient boundaries are stubbed. The journey runs in English and Arabic RTL,
 * and a separate case proves signing is refused while the draft is dirty.
 */

/** Words the interface shows for one locale, asserted verbatim in the browser. */
interface JourneyWords {
  locale: 'en' | 'ar';
  direction: 'ltr' | 'rtl';
  clinicalNote: string;
  subjective: string;
  saveDraft: string;
  savedNotice: string;
  attestation: string;
  sign: string;
  signTitle: string;
  signedNotice: string;
  saveBeforeSign: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  clinicalNote: 'Clinical note',
  subjective: 'Chief concern and history',
  saveDraft: 'Save draft',
  savedNotice: 'Draft saved to the patient record.',
  attestation: 'I attest to this note and its patient context',
  sign: 'Sign draft',
  signTitle: 'Sign clinical note',
  signedNotice: 'Note signed. The revision is now read only.',
  saveBeforeSign: 'Save your changes before signing. Unsaved text is not signed.',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  clinicalNote: 'الملاحظة السريرية',
  subjective: 'الشكوى الرئيسية والتاريخ',
  saveDraft: 'حفظ المسودة',
  savedNotice: 'تم حفظ المسودة في ملف المريض.',
  attestation: 'أقرّ بهذه الملاحظة وسياق المريض',
  sign: 'توقيع المسودة',
  signTitle: 'توقيع ملاحظة سريرية',
  signedNotice: 'تم توقيع الملاحظة؛ المراجعة الآن للقراءة فقط.',
  saveBeforeSign: 'احفظ تغييراتك قبل التوقيع. النص غير المحفوظ لا يُوقّع.',
};

/** Opaque note id minted by the synthetic API and bound in the harness route. */
const noteId = 'note-syn-1';
const saveCommand = `PUT /api/v1/clinical-notes/${noteId}`;
const signCommand = `POST /api/v1/clinical-notes/${noteId}/sign`;

/** Open the harness in the requested locale and assert the document direction. */
async function openHarness(page: Page, words: JourneyWords): Promise<void> {
  await page.goto('/e2e/clinicalnote/clinicalnote-harness.html');
  await expect(page.getByTestId('clinicalnote-harness')).toBeVisible();
  if (words.locale === 'ar') {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
}

/** Drive document, save and sign in the chosen locale. */
async function runJourney(page: Page, api: SyntheticNoteApi, words: JourneyWords): Promise<void> {
  await openHarness(page, words);
  const editor = page.getByRole('region', { name: words.clinicalNote });

  await test.step('document and save a draft note', async () => {
    await editor.getByRole('textbox', { name: words.subjective }).fill('Synthetic edited chief concern.');
    await expect(page.getByTestId('unsaved-changes')).toBeVisible();
    await page.getByRole('button', { name: words.saveDraft }).click();
    await expect(page.getByTestId('note-status')).toContainText(words.savedNotice);
    await expect.poll(() => api.calls).toContain(saveCommand);
    await expect(page.getByTestId('unsaved-changes')).toHaveCount(0);
  });

  await test.step('attest and sign the saved note', async () => {
    await editor.getByRole('checkbox', { name: words.attestation }).check();
    await editor.getByRole('button', { name: words.sign }).click();
    const confirmation = page.getByRole('dialog', { name: words.signTitle });
    await expect(confirmation).toBeVisible();
    await confirmation.getByRole('button', { name: words.sign }).click();
    await expect(page.getByTestId('note-status')).toContainText(words.signedNotice);
    await expect.poll(() => api.calls).toContain(signCommand);
    await expect(page.getByTestId('signer-attestation')).toBeVisible();
  });

  await expectNoBlockingViolations(page);
}

test('clinician documents and signs a note in English', async ({ page, baseURL }) => {
  const api = await installSyntheticNoteApi(page, baseURL);
  await runJourney(page, api, english);
});

test('clinician documents and signs a note in Arabic RTL', async ({ page, baseURL }) => {
  const api = await installSyntheticNoteApi(page, baseURL);
  await runJourney(page, api, arabic);
});

test('sign is refused while the draft is dirty', async ({ page, baseURL }) => {
  const api = await installSyntheticNoteApi(page, baseURL);
  await openHarness(page, english);
  const editor = page.getByRole('region', { name: english.clinicalNote });

  await editor
    .getByRole('textbox', { name: english.subjective })
    .fill('Synthetic text that has not been saved.');
  await expect(page.getByTestId('unsaved-changes')).toBeVisible();
  await editor.getByRole('checkbox', { name: english.attestation }).check();
  await editor.getByRole('button', { name: english.sign }).click();

  await expect(page.getByTestId('note-status')).toContainText(english.saveBeforeSign);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(api.calls).not.toContain(signCommand);
  await expectNoBlockingViolations(page);
});

import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { defined } from '../src/components/invariant';
import { syntheticAlphaChart } from '../src/features/chart/fixtures';
import { breakpointFor } from '../src/theme/breakpoints';
import { expectNoBlockingViolations } from './accessibility';
import { installSyntheticNoteApi } from './clinicalnote/synthetic-note-api';
import type { SyntheticNoteApi } from './clinicalnote/synthetic-note-api';

/**
 * Responsive end-to-end proof for the chart feature (WEB-07, UX-01).
 *
 * The only chart-feature screen a browser harness mounts today is `NotePage`,
 * so this spec drives that screen through its real providers and transport in
 * every configured device project, in English and Arabic RTL. It proves that a
 * fixed synthetic SOAP value stays present from a 360 px phone to a 1920 px
 * desktop, that all four SOAP fields are reachable, that no page-wide
 * horizontal scroll appears, and that document, save, attest and sign work by
 * keyboard and by touch. The longitudinal-chart, document and revision screens
 * have no harness and are recorded as `test.fixme` below with a task request.
 *
 * Inputs: the Playwright page/baseURL fixtures and the synthetic note stub.
 * Outputs: assertions on direction, layout band, clinical values, status
 * messages and recorded commands. Nothing here contacts a patient record or a
 * real endpoint; all identifiers and text are synthetic.
 */

/** Opaque note id pinned by the clinical-note harness route. */
const noteId = 'note-syn-1';
/** Commands the note task must send to the synthetic boundary. */
const saveCommand = `PUT /api/v1/clinical-notes/${noteId}`;
const signCommand = `POST /api/v1/clinical-notes/${noteId}/sign`;

/**
 * The SOAP value the synthetic stub seeds, read from the chart fixture so the
 * expectation tracks the fixture. The stub currently holds the same literal in
 * `freshRevision().content`; both must stay in sync, and this assertion fails
 * if either drifts. The editor stores one content string, which `contentToSoap`
 * maps back into `subjective`; the other three SOAP fields stay empty.
 */
const syntheticSoapValue = defined(
  syntheticAlphaChart.revisions[0],
  'synthetic SOAP revision',
).content.subjective;

/** Marker typed into the draft to make it dirty without replacing the SOAP value. */
const editedMarker = ' [responsive check]';

/** Approved en/ar labels used as the assertion oracle, mirroring chart/messages.ts. */
interface ChartWords {
  readonly locale: 'en' | 'ar';
  readonly direction: 'ltr' | 'rtl';
  readonly clinicalNote: string;
  readonly subjective: string;
  readonly objective: string;
  readonly assessment: string;
  readonly plan: string;
  readonly attestation: string;
  readonly saveDraft: string;
  readonly savedNotice: string;
  readonly unsavedChanges: string;
  readonly sign: string;
  readonly signTitle: string;
  readonly signedNotice: string;
  readonly cancel: string;
}

const english: ChartWords = {
  locale: 'en',
  direction: 'ltr',
  clinicalNote: 'Clinical note',
  subjective: 'Chief concern and history',
  objective: 'Examination and findings',
  assessment: 'Assessment and diagnoses',
  plan: 'Plan',
  attestation: 'I attest to this note and its patient context',
  saveDraft: 'Save draft',
  savedNotice: 'Draft saved to the patient record.',
  unsavedChanges: 'This note has unsaved changes.',
  sign: 'Sign draft',
  signTitle: 'Sign clinical note',
  signedNotice: 'Note signed. The revision is now read only.',
  cancel: 'Cancel',
};

const arabic: ChartWords = {
  locale: 'ar',
  direction: 'rtl',
  clinicalNote: 'الملاحظة السريرية',
  subjective: 'الشكوى الرئيسية والتاريخ',
  objective: 'الفحص والنتائج',
  assessment: 'التقييم والتشخيصات',
  plan: 'الخطة',
  attestation: 'أقرّ بهذه الملاحظة وسياق المريض',
  saveDraft: 'حفظ المسودة',
  savedNotice: 'تم حفظ المسودة في ملف المريض.',
  unsavedChanges: 'هذه الملاحظة تحتوي على تغييرات غير محفوظة.',
  sign: 'توقيع المسودة',
  signTitle: 'توقيع ملاحظة سريرية',
  signedNotice: 'تم توقيع الملاحظة؛ المراجعة الآن للقراءة فقط.',
  cancel: 'إلغاء',
};

/** True when the current Playwright project emulates a coarse touch pointer. */
function hasTouchPointer(): boolean {
  return test.info().project.use.hasTouch === true;
}

/**
 * Fail when the document is wider than its layout viewport. `clientWidth`
 * excludes the vertical scrollbar, so this is stricter than comparing against
 * `innerWidth` and catches a scrollbar-sized overflow on wide projects too.
 */
async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const widths = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
  expect(widths.scrollWidth).toBeLessThanOrEqual(widths.clientWidth);
}

/**
 * Open the real-note harness and settle the requested locale. The locale
 * toggle is driven by tap on touch projects and by click elsewhere; the
 * harness owns the document `dir` and `lang` through its I18nProvider.
 */
async function openHarness(page: Page, words: ChartWords): Promise<void> {
  await page.goto('/e2e/clinicalnote/clinicalnote-harness.html');
  await expect(page.getByTestId('clinicalnote-harness')).toBeVisible();
  await expect(page.getByRole('button').first()).toBeVisible();
  if (words.locale === 'ar') {
    const toArabic = page.getByRole('button', { name: 'العربية' });
    if (hasTouchPointer()) {
      await toArabic.tap();
    } else {
      await toArabic.click();
    }
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
}

/**
 * Tab through each expected control and assert it actually receives focus.
 * With `reset`, sequential focus starts at the document beginning: the harness
 * does not mount AppShell, so its locale toggle is the first tabbable element
 * and the first SOAP field is next. Without `reset`, walking continues from
 * the current focus, which proves the real order between SOAP fields and the
 * save, attestation and sign actions instead of activating them by proxy.
 */
async function tabThrough(page: Page, stops: readonly Locator[], reset = false): Promise<void> {
  if (reset) {
    await page.evaluate(() => {
      const body = document.body;
      body.setAttribute('tabindex', '-1');
      body.focus();
    });
  }
  for (const stop of stops) {
    await page.keyboard.press('Tab');
    await expect(stop).toBeFocused();
  }
}

/** Assert that the note page rendered the layout band the viewport requests. */
async function expectResponsiveBand(page: Page, editor: Locator): Promise<void> {
  const viewport = defined(page.viewportSize(), 'browser viewport');
  const band = breakpointFor(viewport.width);
  await expect(page.locator('[data-note-page-view]')).toHaveAttribute(
    'data-note-page-view',
    band === 'compact' ? 'sticky-save-action' : 'inline-save-action',
  );
  await expect(editor).toHaveAttribute('data-breakpoint', band);
  await expect(editor).toHaveAttribute('data-fields-layout', 'single-column');
  await expect(editor).toHaveAttribute(
    'data-signature-layout',
    band === 'compact' ? 'sticky-footer' : 'inline',
  );
}

/**
 * Drive document, save, attest and sign on the loaded note. Only the input
 * primitive branches on pointer capability; all clinical, direction, overflow
 * and accessibility assertions run in every project. The extra Tab-stop checks
 * are keyboard-only concerns and therefore live in the non-touch branch; touch
 * projects still run every shared assertion and are never skipped or weakened.
 */
async function driveNoteTask(
  page: Page,
  api: SyntheticNoteApi,
  words: ChartWords,
  hasTouch: boolean,
): Promise<void> {
  const editor = page.getByRole('region', { name: words.clinicalNote });
  const toggle = page.getByRole('button').first();
  const subjective = editor.getByRole('textbox', { name: words.subjective });
  const objective = editor.getByRole('textbox', { name: words.objective });
  const assessment = editor.getByRole('textbox', { name: words.assessment });
  const plan = editor.getByRole('textbox', { name: words.plan });
  const save = page.getByRole('button', { name: words.saveDraft });
  const attestation = editor.getByRole('checkbox', { name: words.attestation });
  const sign = editor.getByRole('button', { name: words.sign });

  // The SOAP value assertion is the real load gate: a loading or error state
  // has no textboxes and therefore cannot satisfy it.
  await expect(subjective).toHaveValue(syntheticSoapValue);
  await expectNoHorizontalScroll(page);
  await expectResponsiveBand(page, editor);
  await tabThrough(page, [toggle, subjective], true);

  for (const field of [subjective, objective, assessment, plan]) {
    await expect(field).toBeVisible();
    await field.scrollIntoViewIfNeeded();
    await expect(field).toBeInViewport();
  }

  // End places the caret in the field; insertText fires the native input event
  // React's onChange listens for. Assert containment so RTL caret placement
  // cannot turn a directional detail into a false failure.
  await page.keyboard.press('End');
  await page.keyboard.insertText(editedMarker);
  const dirtyValue = await subjective.inputValue();
  expect(dirtyValue).toContain(syntheticSoapValue);
  expect(dirtyValue).toContain(editedMarker.trim());
  await expect(page.getByTestId('unsaved-changes')).toContainText(words.unsavedChanges);
  await expectNoHorizontalScroll(page);

  // The sign button is disabled until attestation, so it is not a tab stop
  // yet: the real order after the last SOAP field is checkbox then save.
  await tabThrough(page, [objective, assessment, plan]);
  await expect(save).toBeEnabled();
  if (hasTouch) {
    await save.tap();
  } else {
    await tabThrough(page, [attestation, save]);
    await page.keyboard.press('Enter');
  }
  await expect(page.getByTestId('note-status')).toContainText(words.savedNotice);
  await expect.poll(() => api.calls).toContain(saveCommand);
  await expect(page.getByTestId('unsaved-changes')).toHaveCount(0);
  const savedValue = await subjective.inputValue();
  expect(savedValue).toContain(syntheticSoapValue);
  expect(savedValue).toContain(editedMarker.trim());
  await expectNoHorizontalScroll(page);

  if (hasTouch) {
    await attestation.tap();
  } else {
    await tabThrough(page, [toggle, subjective, objective, assessment, plan, attestation], true);
    await page.keyboard.press('Space');
  }
  await expect(attestation).toBeChecked();
  await expect(sign).toBeEnabled();

  if (hasTouch) {
    await sign.tap();
  } else {
    await tabThrough(page, [sign]);
    await page.keyboard.press('Enter');
  }
  const dialog = page.getByRole('dialog', { name: words.signTitle });
  await expect(dialog).toBeVisible();
  const cancel = dialog.getByRole('button', { name: words.cancel });
  const dialogSign = dialog.getByRole('button', { name: words.sign });
  // The dialog focuses Cancel on mount; its sign Button is the previous
  // tabbable in DOM order, and its sticky action row is a compact overflow risk.
  await expect(cancel).toBeFocused();
  await expectNoHorizontalScroll(page);
  if (hasTouch) {
    await dialogSign.tap();
  } else {
    await page.keyboard.press('Shift+Tab');
    await expect(dialogSign).toBeFocused();
    await page.keyboard.press('Enter');
  }

  await expect(page.getByTestId('note-status')).toContainText(words.signedNotice);
  await expect.poll(() => api.calls).toContain(signCommand);
  await expect(page.getByTestId('signer-attestation')).toBeVisible();
  await expect(page.getByTestId('signer-attestation')).toContainText(words.attestation);
  const signedValue = await subjective.inputValue();
  expect(signedValue).toContain(syntheticSoapValue);
  await expectNoHorizontalScroll(page);
  await expectNoBlockingViolations(page);
}

test('note screen stays usable by keyboard and touch across the device matrix in English', async ({
  page,
  baseURL,
}) => {
  const api = await installSyntheticNoteApi(page, baseURL);
  await openHarness(page, english);
  await driveNoteTask(page, api, english, hasTouchPointer());
});

test('note screen stays usable by keyboard and touch across the device matrix in Arabic RTL', async ({
  page,
  baseURL,
}) => {
  const api = await installSyntheticNoteApi(page, baseURL);
  await openHarness(page, arabic);
  await driveNoteTask(page, api, arabic, hasTouchPointer());
});

test.fixme(
  'longitudinal chart screen stays usable by keyboard and touch across the device matrix in English and Arabic',
  () => {
    test.info().annotations.push({
      type: 'blocked-by',
      description:
        'No e2e browser harness mounts /patients/:id/chart; only clinicalnote-harness.html mounts a chart-feature screen and the synthetic shell role is reception, so the route renders ForbiddenPage. Needs apps/staff-web/e2e/chart/chart-harness.html and chart-harness.tsx.',
    });
  },
);

test.fixme(
  'document list screen stays usable by keyboard and touch across the device matrix in English and Arabic',
  () => {
    test.info().annotations.push({
      type: 'blocked-by',
      description:
        'No e2e browser harness mounts the chart documents region. ChartScreen holds DocumentList, but the only browser harness mounts NotePage; coded associations and document actions cannot be observed. Needs apps/staff-web/e2e/chart/chart-harness.html and chart-harness.tsx.',
    });
  },
);

test.fixme(
  'revision history screen stays usable by keyboard and touch across the device matrix in English and Arabic',
  () => {
    test.info().annotations.push({
      type: 'blocked-by',
      description:
        'No e2e browser harness seeds or mounts the chart revision history. The clinical-note stub returns history: [], so a browser assertion on revisions would be vacuous. Needs apps/staff-web/e2e/chart/chart-harness.html, chart-harness.tsx and a non-empty synthetic chart API.',
    });
  },
);

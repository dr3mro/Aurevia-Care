import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { installSyntheticPrintApi } from './synthetic-print-api';
import type { PrintAuditEntry, SyntheticPrintApi } from './synthetic-print-api';
import { must } from '../../tests/must';

/**
 * Synthetic browser journey for patient handout printing (WEB-09, RX-02, LAB-01).
 *
 * A clinician signs a prescription and prints the patient copy, places a lab
 * order and prints its requisition sheet, then prints a chart summary. The same
 * journey is repeated in Arabic. The three print buttons are exported by the
 * print feature but are not yet mounted on their owning screens, so this suite
 * mounts them through a dev-only harness with the real providers and synthetic
 * fixtures. Only the `/api/v1/**` boundary is stubbed: no patient record, printer
 * or credential is contacted, and the PDF is produced by the browser itself.
 */

/** Words the interface shows for one locale, asserted verbatim in the browser. */
interface JourneyWords {
  locale: 'en' | 'ar';
  direction: 'ltr' | 'rtl';
  signPrescription: string;
  placeOrder: string;
  draftPrescription: string;
  draftOrder: string;
  printPrescription: string;
  prescriptionDialog: string;
  handoutTitle: string;
  handoutLanguage: string;
  reasonGroup: string;
  reasonGive: string;
  reasonReferral: string;
  reasonPatientRequest: string;
  reasonRequired: string;
  recordNotice: string;
  printForPatient: string;
  requisitionDialog: string;
  preparation: string;
  preparationText: string;
  orderReasonRequired: string;
  printChart: string;
  previewRegion: string;
  leftOut: string;
  unsignedReason: string;
  withheldReason: string;
  print: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  signPrescription: 'Sign prescription',
  placeOrder: 'Place order',
  draftPrescription: 'A draft prescription cannot be printed.',
  draftOrder: 'Draft orders cannot be printed. Place the order first.',
  printPrescription: 'Print prescription',
  prescriptionDialog: 'Print preview',
  handoutTitle: 'Patient copy',
  handoutLanguage: 'Handout language',
  reasonGroup: 'Why are you printing?',
  reasonGive: 'Give to patient',
  reasonReferral: 'Referral',
  reasonPatientRequest: 'Patient request',
  reasonRequired: 'Choose a reason before printing',
  recordNotice: 'Print recorded.',
  printForPatient: 'Print for patient',
  requisitionDialog: 'Patient requisition',
  preparation: 'Preparation',
  preparationText: 'Fast for eight hours before collection.',
  orderReasonRequired: 'Choose why you are printing.',
  printChart: 'Print chart summary',
  previewRegion: 'Print preview',
  leftOut: 'Left out of this print',
  unsignedReason: 'Unsigned draft — only signed content is printed.',
  withheldReason: 'Withheld result — not released to the patient.',
  print: 'Print',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  signPrescription: 'توقيع الوصفة',
  placeOrder: 'تقديم الطلب',
  draftPrescription: 'لا يمكن طباعة وصفة مسودة.',
  draftOrder: 'لا يمكن طباعة الطلبات المسودة. ضع الطلب أولًا.',
  printPrescription: 'طباعة الوصفة',
  prescriptionDialog: 'معاينة الطباعة',
  handoutTitle: 'نسخة المريض',
  handoutLanguage: 'لغة المطبوع',
  reasonGroup: 'لماذا تطبع؟',
  reasonGive: 'تسليم إلى المريض',
  reasonReferral: 'إحالة',
  reasonPatientRequest: 'طلب المريض',
  reasonRequired: 'اختر سببًا قبل الطباعة',
  recordNotice: 'تم تسجيل الطباعة.',
  printForPatient: 'طباعة للمريض',
  requisitionDialog: 'طلب المريض',
  preparation: 'التحضير',
  preparationText: 'Fast for eight hours before collection.',
  orderReasonRequired: 'اختر سبب الطباعة.',
  printChart: 'طباعة ملخص الملف',
  previewRegion: 'معاينة الطباعة',
  leftOut: 'مستبعد من هذه الطباعة',
  unsignedReason: 'مسودة غير موقّعة؛ تُطبع المحتويات الموقّعة فقط.',
  withheldReason: 'نتيجة محجوبة؛ لم يتم الإفراج عنها للمريض.',
  print: 'طباعة',
};

/** Wait for the audit POST to reach the stub, then return its entry. */
async function auditFor(api: SyntheticPrintApi, kind: string): Promise<PrintAuditEntry> {
  await expect
    .poll(
      () => api.audit.find((item) => item.kind === kind),
      `an audit entry for ${kind} should have been recorded`,
    )
    .toBeDefined();
  return must(api.audit.find((item) => item.kind === kind));
}

/**
 * Drive the full print journey in one locale. The draft barriers are asserted
 * first, then each committed source is printed with an explicit reason and the
 * resulting audit entry is read back through the API stub.
 */
async function runJourney(page: Page, api: SyntheticPrintApi, words: JourneyWords): Promise<void> {
  await page.goto('/e2e/print/print-harness.html');
  await expect(page.getByTestId('print-harness')).toBeVisible();

  if (words.locale === 'ar') {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);

  await test.step('a draft prescription and an unplaced order cannot print', async () => {
    const prescription = page.getByRole('button', { name: words.printPrescription });
    const order = page.getByRole('button', { name: words.printForPatient });
    await expect(prescription).toBeDisabled();
    await expect(page.getByText(words.draftPrescription)).toBeVisible();
    await expect(order).toBeDisabled();
    await expect(page.getByText(words.draftOrder)).toBeVisible();
    expect(api.audit).toHaveLength(0);
    expect(api.calls).not.toContain('POST /api/v1/printing/audit');
  });

  await test.step('sign the prescription and print the patient copy', async () => {
    await page.getByRole('button', { name: words.signPrescription }).click();
    await expect.poll(() => api.calls).toContain('POST /api/v1/prescriptions/RX-SYN-1/sign');
    await expect(page.getByRole('button', { name: words.printPrescription })).toBeEnabled();

    await page.getByRole('button', { name: words.printPrescription }).click();
    const dialog = page.getByRole('dialog', { name: words.prescriptionDialog });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByTitle(words.handoutTitle)).toHaveAttribute(
      'data-print-handout',
      'true',
    );
    // The handout language is independent of the interface locale (RX-02).
    await expect(dialog.getByLabel(words.handoutLanguage)).toHaveValue(words.locale);

    // A reason is required: the first Print is refused in words and audits nothing.
    await dialog.getByRole('button', { name: words.print }).click();
    await expect(dialog.getByRole('alert')).toHaveText(words.reasonRequired);
    expect(api.audit).toHaveLength(0);

    await dialog.getByRole('radio', { name: words.reasonGive }).check();
    await dialog.getByRole('button', { name: words.print }).click();
    await expect(page.getByText(words.recordNotice)).toBeVisible();
    const entry = await auditFor(api, 'prescription');
    expect(entry.reason).toBe('give-to-patient');
    expect(entry.language).toBe(words.locale);
    expect(entry.action).toBe('print');
    expect(entry.copy).toBe('patient');
    expect(entry.reprint).toBe(false);
  });

  await test.step('place the lab order and print the requisition sheet', async () => {
    await page.getByRole('button', { name: words.placeOrder }).click();
    await expect.poll(() => api.calls).toContain('POST /api/v1/orders/ORD-SYN-1/place');
    await expect(page.getByRole('button', { name: words.printForPatient })).toBeEnabled();

    await page.getByRole('button', { name: words.printForPatient }).click();
    const dialog = page.getByRole('dialog', { name: words.requisitionDialog });
    await expect(dialog).toBeVisible();
    const sheet = dialog.locator('[data-order-requisition="true"]');
    await expect(sheet).toBeVisible();
    await expect(sheet).toHaveAttribute('dir', words.direction);
    await expect(sheet).toHaveAttribute('lang', words.locale);
    await expect(sheet.getByText(words.preparation)).toBeVisible();
    await expect(sheet.getByText(words.preparationText)).toBeVisible();
    await expect(sheet.getByText('LAB-GLU')).toBeVisible();

    await dialog.getByRole('button', { name: words.print }).click();
    await expect(dialog.getByRole('alert')).toHaveText(words.orderReasonRequired);
    expect(api.audit.filter((item) => item.kind === 'lab-order')).toHaveLength(0);

    await dialog.getByRole('radio', { name: words.reasonReferral }).check();
    await dialog.getByRole('button', { name: words.print }).click();
    const entry = await auditFor(api, 'lab-order');
    expect(entry.reason).toBe('referral');
    expect(entry.language).toBe(words.locale);
    expect(entry.sheetCount).toBe(1);
  });

  await test.step('print the chart summary with its omissions listed', async () => {
    await page.getByRole('button', { name: words.printChart }).click();
    const preview = page.getByRole('region', { name: words.previewRegion });
    await expect(preview).toBeVisible();
    const leftOut = preview.getByRole('list', { name: words.leftOut });
    await expect(leftOut).toContainText('Synthetic unsigned draft note');
    await expect(leftOut).toContainText(words.unsignedReason);
    await expect(leftOut).toContainText('Synthetic withheld result');
    await expect(leftOut).toContainText(words.withheldReason);

    await preview.getByRole('button', { name: words.print }).click();
    await expect(preview.getByRole('alert')).toHaveText(words.reasonRequired);
    expect(api.audit.filter((item) => item.kind === 'chart-summary')).toHaveLength(0);

    await preview.getByRole('radio', { name: words.reasonPatientRequest }).check();
    await preview.getByRole('button', { name: words.print }).click();
    const entry = await auditFor(api, 'chart-summary');
    expect(entry.reason).toBe('patient-request');
    expect(entry.language).toBe(words.locale);
    expect(entry.printable).toBe(true);
  });

  await test.step('the browser produces a PDF without a real printer', async () => {
    const pdf = await page.pdf();
    expect(pdf.subarray(0, 5).toString('latin1')).toBe('%PDF-');
    // No patient identifier is placed in the URL (WEB-02).
    expect(page.url()).not.toContain('patient-');
  });
}

test('a clinician prints a prescription, a lab order and a chart summary in English', async ({
  page,
  baseURL,
}) => {
  const api: SyntheticPrintApi = await installSyntheticPrintApi(page, baseURL);
  await runJourney(page, api, english);
  expect(api.audit).toHaveLength(3);
});

test('the same print journey runs in Arabic with right-to-left handouts', async ({
  page,
  baseURL,
}) => {
  const api: SyntheticPrintApi = await installSyntheticPrintApi(page, baseURL);
  await runJourney(page, api, arabic);
  expect(api.audit).toHaveLength(3);
});

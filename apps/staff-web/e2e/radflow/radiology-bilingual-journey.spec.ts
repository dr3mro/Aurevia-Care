import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { expectNoBlockingViolations } from '../accessibility';
import {
  accession,
  accessionIssuer,
  allowSyntheticWrites,
  mockWorklistApi,
  orderCode,
  orderId,
  patientId,
  scheduledAt,
  site,
  studyInstanceUid,
  workstationRef,
  wrongPatientId,
} from './synthetic-rad-api';

/**
 * Bilingual radiology worklist journey (WEB-10, RAD-01).
 *
 * The pre-login shell fixes the role to reception and denies `/radiology`, so
 * this journey mounts the real worklist screen in a dev-only harness with the
 * production providers, exactly as the laboratory journey does. Only the
 * `/api/v1/**` boundary is stubbed. Every identifier is synthetic and no
 * patient value is ever placed in a URL (WEB-02).
 */

/** Words asserted verbatim in one locale. */
interface JourneyWords {
  locale: 'en' | 'ar';
  direction: 'ltr' | 'rtl';
  switchToArabic: boolean;
  worklist: string;
  openOrder: string;
  scheduleForm: string;
  recordSchedule: string;
  linkStudy: string;
  handoff: string;
  commandSent: string;
  mismatchText: string;
  scheduled: string;
  performed: string;
  reported: string;
}

const english: JourneyWords = {
  locale: 'en',
  direction: 'ltr',
  switchToArabic: false,
  worklist: 'Imaging worklist',
  openOrder: 'Open order',
  scheduleForm: 'Schedule the study',
  recordSchedule: 'Record schedule',
  linkStudy: 'Link study',
  handoff: 'Hand off as reported',
  commandSent: 'Command sent to the imaging worklist API',
  mismatchText: 'Patient label does not match this order',
  scheduled: 'scheduled',
  performed: 'performed',
  reported: 'reported',
};

const arabic: JourneyWords = {
  locale: 'ar',
  direction: 'rtl',
  switchToArabic: true,
  worklist: 'قائمة أعمال الأشعة',
  openOrder: 'فتح الطلب',
  scheduleForm: 'جدولة الفحص',
  recordSchedule: 'تسجيل الجدول',
  linkStudy: 'ربط الدراسة',
  handoff: 'إرسال كتقرير',
  commandSent: 'تم إرسال الأمر إلى خدمة قائمة أعمال الأشعة',
  mismatchText: 'معرّف المريض لا يطابق هذا الطلب',
  scheduled: 'scheduled',
  performed: 'performed',
  reported: 'reported',
};

/** Open the harness and switch the document to the journey's locale. */
async function openHarness(page: Page, words: JourneyWords): Promise<void> {
  await page.goto('/e2e/radflow/radflow-harness.html');
  await expect(page.getByTestId('radworklist-harness')).toBeVisible();
  if (words.switchToArabic) {
    await page.getByRole('button', { name: 'العربية' }).click();
  }
  await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
}

/** Drive the worklist through every state and the refused link in one locale. */
async function runJourney(page: Page, words: JourneyWords): Promise<void> {
  const commands = await mockWorklistApi(page);
  await openHarness(page, words);
  await allowSyntheticWrites(page);

  const worklist = page.getByRole('region', { name: words.worklist });
  await expect(worklist).toBeVisible();
  const row = worklist.getByRole('row', { name: new RegExp(orderCode) });
  await expect(row).toBeVisible();
  await expect(row.getByTestId('worklist-status')).toHaveAttribute('data-status', 'received');
  await expect(worklist.getByRole('row', { name: /ORD-005/ })).toHaveCount(0);

  await test.step('the order is scheduled for the performing site', async () => {
    await row.getByRole('button', { name: words.openOrder }).click();
    await expect(page.getByRole('form', { name: words.scheduleForm })).toBeVisible();
    await page.locator(`#schedule-at-${orderId}`).fill(scheduledAt);
    await page.locator(`#schedule-modality-${orderId}`).selectOption('ct');
    await page.locator(`#schedule-accession-${orderId}`).fill(accession);
    await page.locator(`#schedule-issuer-${orderId}`).fill(accessionIssuer);
    await page.getByRole('button', { name: words.recordSchedule }).click();
    await expect(page.getByTestId('radworklist-announcement')).toHaveText(words.commandSent);
    await expect(row.getByTestId('worklist-status')).toHaveAttribute('data-status', words.scheduled);
    expect(commands).toContain('schedule');
  });

  await test.step('a wrong-patient study link is refused and leaves the order scheduled', async () => {
    await page.locator(`#link-patient-${orderId}`).fill(wrongPatientId);
    await page.locator(`#link-accession-${orderId}`).fill(accession);
    await page.locator(`#link-study-${orderId}`).fill(studyInstanceUid);
    await page.locator(`#link-workstation-${orderId}`).fill(workstationRef);
    const check = page.getByTestId('label-check');
    await expect(check).toHaveAttribute('data-verdict', 'patient');
    await expect(check).toContainText(words.mismatchText);
    await expect(page.getByRole('button', { name: words.linkStudy })).toBeDisabled();

    // The screen guard is not the only defence: a direct command with the
    // wrong patient label is refused at the real boundary contract, and the
    // order stays in the scheduled state. The mock records the attempted
    // command before answering, so compare counts rather than names here.
    const commandsBefore = commands.length;
    const bypass = await page.evaluate(
      async (draft) => {
        const response = await fetch(`/api/v1/radiology/worklist/orders/${draft.orderId}/studies`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Active-Organization': draft.organizationId,
            'X-Correlation-Id': 'synthetic-correlation-rad',
            'X-XSRF-TOKEN': 'synthetic-csrf-token',
            'If-Match': '2',
            'Idempotency-Key': 'synthetic-wrong-label-attempt',
          },
          body: JSON.stringify(draft.body),
        });
        const body: unknown = await response.json();
        return { status: response.status, body };
      },
      {
        orderId,
        organizationId: '11111111-1111-1111-1111-111111111111',
        body: {
          expectedVersion: 2,
          performingSiteId: site,
          studyInstanceUid,
          patientId: wrongPatientId,
          accession,
          workstationRef,
        },
      },
    );
    expect(bypass.status).toBe(422);
    expect(bypass.body).toEqual({ code: 'LABEL_MISMATCH' });
    expect(commands).toHaveLength(commandsBefore + 1);

    // The accession side of the same guard is checked at the boundary too: a
    // matched patient with another accession must not link a different study.
    const wrongAccession = await page.evaluate(
      async (draft) => {
        const response = await fetch(`/api/v1/radiology/worklist/orders/${draft.orderId}/studies`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Active-Organization': draft.organizationId,
            'X-Correlation-Id': 'synthetic-correlation-rad',
            'X-XSRF-TOKEN': 'synthetic-csrf-token',
            'If-Match': '2',
            'Idempotency-Key': 'synthetic-wrong-accession-attempt',
          },
          body: JSON.stringify(draft.body),
        });
        const body: unknown = await response.json();
        return { status: response.status, body };
      },
      {
        orderId,
        organizationId: '11111111-1111-1111-1111-111111111111',
        body: {
          expectedVersion: 2,
          performingSiteId: site,
          studyInstanceUid,
          patientId,
          accession: 'ACC-WRONG',
          workstationRef,
        },
      },
    );
    expect(wrongAccession.status).toBe(422);
    expect(wrongAccession.body).toEqual({ code: 'LABEL_MISMATCH' });
    expect(commands).toHaveLength(commandsBefore + 2);

    // The stub's unmatched-path fallback is a real branch: a request to an
    // unknown worklist command ends with the machine-readable empty body.
    const unknown = await page.evaluate(async () => {
      const response = await fetch('/api/v1/radiology/worklist/unknown-command');
      const body: unknown = await response.json();
      return { status: response.status, body };
    });
    expect(unknown.status).toBe(404);
    expect(unknown.body).toEqual({});
    await expect(row.getByTestId('worklist-status')).toHaveAttribute('data-status', words.scheduled);
    await expectNoBlockingViolations(page);
  });

  await test.step('the study links and is handed off once both labels match', async () => {
    await page.locator(`#link-patient-${orderId}`).fill(patientId);
    await expect(page.getByTestId('label-check')).toHaveAttribute('data-verdict', 'matched');
    await page.getByRole('button', { name: words.linkStudy }).click();
    await expect(row.getByTestId('worklist-status')).toHaveAttribute('data-status', words.performed);
    await page.getByRole('button', { name: words.handoff }).click();
    await expect(row.getByTestId('worklist-status')).toHaveAttribute('data-status', words.reported);
    await expect(page.getByRole('button', { name: words.handoff })).toHaveCount(0);
    expect(page.url()).not.toContain(patientId);
    await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
    await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
    await expectNoBlockingViolations(page);
  });
}

test('radiology schedules, refuses a wrong-patient link and reports in English', async ({ page }) => {
  await runJourney(page, english);
});

test('radiology schedules, refuses a wrong-patient link and reports in Arabic RTL', async ({
  page,
}) => {
  await runJourney(page, arabic);
});

import { expect, test } from '@playwright/test';
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
 * Browser journey for the radiology workflow (RAD-01, WEB-09).
 *
 * Everything is synthetic. The worklist leg drives the real `/radiology` route
 * with a mocked API so the journey does not need a backend; the referrer leg
 * mounts the exported report components through a small dev-only harness
 * because they are not routed in the portal yet. No credential, real patient
 * identifier or production endpoint appears here.
 *
 * The worklist helpers live in `synthetic-rad-api.ts` so the bilingual journey
 * uses the identical state machine and refusal contract.
 */

test('clinician and radiology journey: schedule, refuse a wrong-patient link, link, hand off as reported', async ({
  page,
}) => {
  const commands = await mockWorklistApi(page);
  await page.goto('/radiology');
  await allowSyntheticWrites(page);

  const worklist = page.getByRole('region', { name: 'Imaging worklist' });
  await expect(worklist).toBeVisible();
  const row = worklist.getByRole('row', { name: new RegExp(orderCode) });
  await expect(row).toBeVisible();
  await expect(row.getByTestId('worklist-status')).toHaveAttribute('data-status', 'received');
  await expect(worklist.getByRole('row', { name: /ORD-005/ })).toHaveCount(0);

  // The referring clinician's order has arrived at the performing site.
  await row.getByRole('button', { name: 'Open order' }).click();

  // Schedule: an approved modality and an issuer-scoped accession are recorded.
  await expect(page.getByRole('form', { name: 'Schedule the study' })).toBeVisible();
  await page.locator(`#schedule-at-${orderId}`).fill(scheduledAt);
  await page.locator(`#schedule-modality-${orderId}`).selectOption('ct');
  await page.locator(`#schedule-accession-${orderId}`).fill(accession);
  await page.locator(`#schedule-issuer-${orderId}`).fill(accessionIssuer);
  await page.getByRole('button', { name: 'Record schedule' }).click();
  await expect(page.getByTestId('radworklist-announcement')).toHaveText(
    'Command sent to the imaging worklist API',
  );
  await expect(row.getByTestId('worklist-status')).toHaveAttribute('data-status', 'scheduled');
  expect(commands).toContain('schedule');

  // Wrong-patient refusal: the workstation label is checked before any link is
  // sent. The verdict is named in words and the link action stays disabled.
  await page.locator(`#link-patient-${orderId}`).fill(wrongPatientId);
  await page.locator(`#link-accession-${orderId}`).fill(accession);
  await page.locator(`#link-study-${orderId}`).fill(studyInstanceUid);
  await page.locator(`#link-workstation-${orderId}`).fill(workstationRef);
  const check = page.getByTestId('label-check');
  await expect(check).toHaveAttribute('data-verdict', 'patient');
  await expect(check).toContainText('Patient label does not match this order');
  await expect(page.getByRole('button', { name: 'Link study' })).toBeDisabled();

  // Accession-only and dual mismatches are named separately and stay blocked.
  await page.locator(`#link-patient-${orderId}`).fill(patientId);
  await page.locator(`#link-accession-${orderId}`).fill('ACC-WRONG');
  await expect(check).toHaveAttribute('data-verdict', 'accession');
  await page.locator(`#link-patient-${orderId}`).fill(wrongPatientId);
  await expect(check).toHaveAttribute('data-verdict', 'both');
  await expect(page.getByRole('button', { name: 'Link study' })).toBeDisabled();
  expect(commands).not.toContain('studies');

  // Defence in depth: if the screen guard were bypassed, the API boundary
  // refuses the mismatched link with 422 LABEL_MISMATCH and leaves the order
  // unchanged. This direct POST checks that contract; it is not a UI action.
  const bypass = await page.evaluate(
    async (draft) => {
      const response = await fetch(`/api/v1/radiology/worklist/orders/${draft.orderId}/studies`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Active-Organization': draft.organizationId,
          'X-XSRF-TOKEN': draft.csrf,
          'If-Match': '2',
          'Idempotency-Key': 'synthetic-bypass-attempt',
        },
        body: JSON.stringify(draft.body),
      });
      return { status: response.status, body: (await response.json()) as { code: string } };
    },
    {
      orderId,
      organizationId: '11111111-1111-1111-1111-111111111111',
      csrf: 'synthetic-csrf-token',
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
  expect(bypass.body.code).toBe('LABEL_MISMATCH');
  await expect(row.getByTestId('worklist-status')).toHaveAttribute('data-status', 'scheduled');

  // Correct both labels. The study links only after they match and the UID and
  // workstation reference are present.
  await page.locator(`#link-patient-${orderId}`).fill(patientId);
  await page.locator(`#link-accession-${orderId}`).fill(accession);
  await expect(check).toHaveAttribute('data-verdict', 'matched');
  await expect(page.getByRole('button', { name: 'Link study' })).toBeEnabled();
  await page.getByRole('button', { name: 'Link study' }).click();
  await expect(page.getByTestId('radworklist-announcement')).toHaveText(
    'Command sent to the imaging worklist API',
  );
  await expect(row.getByTestId('worklist-status')).toHaveAttribute('data-status', 'performed');
  expect(commands).toContain('studies');

  // Handoff moves the performed study to the reporting stage once. Signing the
  // interpretation itself is an API action outside this browser slice.
  await page.getByRole('button', { name: 'Hand off as reported' }).click();
  await expect(row.getByTestId('worklist-status')).toHaveAttribute('data-status', 'reported');
  expect(commands).toContain('report');
  await expect(page.getByRole('button', { name: 'Hand off as reported' })).toHaveCount(0);

  // No patient label is ever placed in the URL (WEB-02).
  expect(page.url()).not.toContain(patientId);
});

test('referrer journey: signed report sections and addendum acknowledgment in English and Arabic', async ({
  page,
}) => {
  await page.goto('/e2e/radflow/referrer-harness.html');
  // The harness is a dev-server entry point (it is not part of the production
  // build). Fail clearly on a staged base URL that falls back to the real app.
  await expect(page.getByTestId('referrer-harness')).toBeVisible();

  const card = page.getByTestId('imaging-report-card');
  await expect(card).toBeVisible();
  const sections = card.locator('[data-section]');
  await expect(sections).toHaveCount(5);
  expect(
    await sections.evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-section'))),
  ).toEqual(['technique', 'comparison', 'findings', 'impression', 'followUp']);
  await expect(card).toContainText('No acute cardiopulmonary abnormality.');

  // Only the open receipt for this tenant and order is shown; the look-alike
  // receipts for another tenant and another order stay hidden.
  const banner = page.getByTestId('addendum-banner');
  await expect(banner).toBeVisible();
  await expect(banner.locator('section')).toHaveCount(1);
  await expect(banner.locator('section[data-version="3"]')).toBeVisible();
  const acknowledge = banner.getByRole('button', { name: 'Acknowledge version 3' });
  await expect(acknowledge).toBeVisible();

  // The referrer acknowledges that exact revision and the banner closes.
  await acknowledge.click();
  await expect(banner).toHaveCount(0);
  await expect(page.getByTestId('harness-acknowledgment')).toContainText(':rad-004:3');

  // The same signed content reads right-to-left in Arabic (WEB-09).
  await page.getByRole('button', { name: 'العربية' }).click();
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(card.getByRole('heading', { name: 'تقرير الأشعة' })).toBeVisible();
  await expect(page.getByTestId('addendum-banner')).toHaveCount(0);
});

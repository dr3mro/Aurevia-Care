import { expect, test } from '@playwright/test';
import type { Browser, BrowserContext, Page } from '@playwright/test';
import { installSyntheticLabApi } from './synthetic-lab-api';
import type { SyntheticLabApi } from './synthetic-lab-api';

/**
 * Synthetic end-to-end laboratory journey for LAB-01, LAB-02 and WEB-09.
 *
 * Three independent browser contexts stand in for the two laboratory users and
 * the clinician: the collector/enterer, the independent verifier and the
 * clinician who reads the released report. The patient's view is asserted
 * through the release-status panel, the in-scope surface that states per-audience
 * visibility. Every screen uses the real routes and the real transport; only the
 * `/api/v1/lab/**` boundary is served by a deterministic synthetic harness, so
 * no patient record, partner or credential is contacted.
 */

/** Open a synthetic context at the configured base URL. */
async function newRole(
  browser: Browser,
  baseURL: string | undefined,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext(baseURL === undefined ? {} : { baseURL });
  const page = await context.newPage();
  context.setDefaultTimeout(10_000);
  return { context, page };
}

/** Find a synthetic accession on the collection desk and open it. */
async function openOrder(page: Page, accessionNo: string): Promise<void> {
  await page.locator('#lab-find-query').fill(accessionNo);
  await page.getByRole('button', { name: new RegExp(accessionNo) }).click();
}

/** Complete the two-name and accession bedside check for one synthetic order. */
async function verifyIdentity(
  page: Page,
  patientId: string,
  accessionNo: string,
  issuer: string,
): Promise<void> {
  await page.locator('#lab-presented-patient').fill(patientId);
  await page.locator('#lab-presented-accession').fill(accessionNo);
  await page.locator('#lab-presented-issuer').fill(issuer);
  await page.getByLabel('I matched both patient names on the wristband and requisition').check();
  await page.getByLabel('I matched the accession number and issuer on the tube').check();
  await expect(page.getByTestId('match-status')).toContainText(
    'Patient and accession match. You may continue.',
  );
}

test('synthetic lab journey: collect, reject, recollect, enter, verify, release and correct', async ({
  browser,
  baseURL,
}) => {
  const collector = await newRole(browser, baseURL);
  const verifier = await newRole(browser, baseURL);
  const clinician = await newRole(browser, baseURL);
  const api: SyntheticLabApi = await installSyntheticLabApi(collector.page, baseURL);

  try {
    await test.step('the collector collects a matched specimen and prints its label', async () => {
      await collector.page.goto('/lab/accessions');
      await expect(
        collector.page.getByRole('heading', { name: 'Specimen collection desk' }),
      ).toBeVisible();
      await openOrder(collector.page, 'ACC-100');
      await verifyIdentity(collector.page, 'patient-100', 'ACC-100', 'SYN-ISSUER');

      await collector.page.locator('#lab-specimen-type').fill('SERUM');
      await collector.page.locator('#lab-site-code').fill('MAIN');
      await collector.page.locator('#lab-collector').fill('synthetic.collector');
      await collector.page.locator('#lab-collected-at').fill('2026-03-09T09:00:00Z');
      await collector.page.locator('#lab-quality').fill('ACCEPTABLE');
      await collector.page.getByRole('button', { name: 'Record collection' }).click();

      await expect(collector.page.getByTestId('collection-status')).toHaveText(
        'Collection recorded.',
      );
      expect(api.calls).toContain('POST /api/v1/lab/specimens/accession-100/collect');
      expect(api.accessions.find((item) => item.id === 'accession-100')?.state).toBe('collected');

      // A successful command reloads the list and clears local drafts; re-check
      // the identity before using the label action, as a collector would.
      await verifyIdentity(collector.page, 'patient-100', 'ACC-100', 'SYN-ISSUER');
      await collector.page.getByRole('button', { name: 'Print label sheet' }).click();
      await expect(
        collector.page.getByRole('heading', { name: 'Specimen label sheet' }),
      ).toBeVisible();
      await expect(collector.page.locator('code', { hasText: 'ACC-100' }).first()).toBeVisible();
    });

    await test.step('the collector rejects a second specimen with a reason and notification', async () => {
      await openOrder(collector.page, 'ACC-200');
      await verifyIdentity(collector.page, 'patient-200', 'ACC-200', 'SYN-BETA-ISSUER');
      await collector.page.getByLabel('Rejection reason').selectOption('haemolysed');
      await collector.page.getByLabel('Notify the patient (where policy allows)').check();
      await collector.page.getByRole('button', { name: 'Reject specimen' }).click();

      await expect(collector.page.getByTestId('collection-status')).toHaveText(
        'Specimen rejected.',
      );
      expect(api.calls).toContain('POST /api/v1/lab/specimens/accession-200/reject');
      const rejected = api.accessions.find((item) => item.id === 'accession-200');
      expect(rejected?.state).toBe('rejected');
      expect(rejected?.rejectionReason).toBe('haemolysed');
      expect(rejected?.rejectionNotification).toContain('clinician:ORDER-200');
    });

    await test.step('recollection mints a linked specimen under the same order', async () => {
      // The collection desk has no recollect button yet; the linked specimen is
      // minted through the same intercepted browser endpoint the API provides.
      await collector.page.evaluate(async () => {
        await fetch('/api/v1/lab/specimens/accession-200/recollect', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: '{}',
        });
      });
      expect(api.calls).toContain('POST /api/v1/lab/specimens/accession-200/recollect');
      const recollection = api.accessions.find((item) => item.id === 'accession-200-r1');
      expect(recollection?.state).toBe('recollected');
      expect(recollection?.replacesAccessionId).toBe('accession-200');
      expect(recollection?.sourceOrderRef).toBe('ORDER-200');

      // The desk fetched its list before the recollect command; refresh so the
      // linked accession is visible to the collector.
      await collector.page.reload();
      await openOrder(collector.page, 'ACC-200-R1');
      await expect(collector.page.getByText('Accession state')).toBeVisible();
      await expect(collector.page.getByText('Recollected')).toBeVisible();
    });

    await test.step('the enterer keys a value and the independent verifier verifies it', async () => {
      await collector.page.goto('/lab/results/entry');
      const hemoglobin = collector.page.getByRole('textbox', {
        name: 'Result value for Hemoglobin',
      });
      await hemoglobin.fill('7.6');
      await expect(
        collector.page.getByRole('heading', { name: 'Laboratory result entry and verification' }),
      ).toBeVisible();
      await expect(collector.page.getByText('Entered by synthetic.lab.technician')).toBeVisible();

      await verifier.page.goto('/lab/results/entry');
      await expect(verifier.page.getByText('Verifying as synthetic.lab.verifier')).toBeVisible();
      await expect(verifier.page.getByTestId('critical-notification-step')).toBeVisible();
      await expect(
        verifier.page.getByRole('button', { name: 'Verify and prepare release' }),
      ).toBeEnabled();
      await verifier.page.getByRole('button', { name: 'Verify and prepare release' }).click();
      await expect(verifier.page.getByTestId('verification-status')).toContainText(
        'Verified by synthetic.lab.verifier',
      );
    });

    await test.step('the clinician reads the released report and what the patient sees', async () => {
      await clinician.page.goto('/lab/release');
      await expect(
        clinician.page.getByRole('heading', { name: 'Laboratory report release and corrections' }),
      ).toBeVisible();
      await expect(clinician.page.getByTestId('release-state')).toContainText(
        'Report state: Final',
      );
      await expect(clinician.page.getByTestId('release-status-clinician')).toContainText(
        'Released',
      );
      await expect(clinician.page.getByTestId('release-status-patient')).toContainText('Released');
      await expect(clinician.page.getByTestId('diff-hemoglobin')).toHaveAttribute(
        'data-change',
        'changed',
      );
      await expect(clinician.page.getByTestId('diff-sodium')).toHaveAttribute(
        'data-change',
        'removed',
      );
      await expect(clinician.page.getByTestId('diff-vitamin-d')).toHaveAttribute(
        'data-change',
        'added',
      );
    });

    await test.step('a recorded correction keeps the report visible to both audiences', async () => {
      await expect(
        clinician.page.getByRole('button', { name: 'Record correction' }),
      ).toBeDisabled();
      await expect(clinician.page.getByTestId('correction-reason-required')).toBeVisible();
      await clinician.page
        .locator('#release-correction-reason')
        .fill('Transcription error on the hemoglobin value confirmed against the analyser.');
      await clinician.page.getByRole('button', { name: 'Record correction' }).click();

      await expect(clinician.page.getByTestId('release-state')).toContainText(
        'Report state: Corrected',
      );
      await expect(clinician.page.getByTestId('correction-status')).toContainText(
        'Corrected by synthetic.lab.supervisor',
      );
      await expect(clinician.page.getByTestId('release-status-clinician')).toContainText(
        'Released',
      );
      await expect(clinician.page.getByTestId('release-status-patient')).toContainText('Released');
    });

    await test.step('the clinician checks the turnaround board and the partner outage route', async () => {
      await clinician.page.goto('/lab/turnaround-board');
      await expect(
        clinician.page.getByRole('heading', { name: 'Laboratory turnaround board' }),
      ).toBeVisible();
      await expect(clinician.page.getByTestId('turnaround-column')).toHaveCount(5);
      await expect(clinician.page.getByTestId('outage-banner')).toBeVisible();
      await expect(
        clinician.page.getByRole('link', { name: 'Open the manual route' }),
      ).toBeVisible();
    });
  } finally {
    await collector.context.close();
    await verifier.context.close();
    await clinician.context.close();
  }
});

test('safety barriers block an unsafe collection, an empty rejection and an unreleased report', async ({
  browser,
  baseURL,
}) => {
  const collector = await newRole(browser, baseURL);
  const clinician = await newRole(browser, baseURL);
  const api: SyntheticLabApi = await installSyntheticLabApi(collector.page, baseURL);
  try {
    await test.step('a mismatched patient identifier blocks collection', async () => {
      await collector.page.goto('/lab/accessions');
      await openOrder(collector.page, 'ACC-100');
      await collector.page.locator('#lab-presented-patient').fill('patient-999');
      await collector.page.locator('#lab-presented-accession').fill('ACC-100');
      await collector.page.locator('#lab-presented-issuer').fill('SYN-ISSUER');
      await collector.page
        .getByLabel('I matched both patient names on the wristband and requisition')
        .check();
      await collector.page
        .getByLabel('I matched the accession number and issuer on the tube')
        .check();
      await expect(collector.page.getByTestId('match-status')).toContainText(
        'Identity mismatch — collection is blocked',
      );
      await expect(
        collector.page.getByRole('button', { name: 'Record collection' }),
      ).toBeDisabled();
      expect(api.calls).not.toContain('POST /api/v1/lab/specimens/accession-100/collect');
    });

    await test.step('an empty rejection reason blocks the reject command', async () => {
      await collector.page.reload();
      await openOrder(collector.page, 'ACC-200');
      await verifyIdentity(collector.page, 'patient-200', 'ACC-200', 'SYN-BETA-ISSUER');
      await expect(collector.page.getByRole('button', { name: 'Reject specimen' })).toBeDisabled();
      await collector.page.getByLabel('Rejection reason').selectOption('other');
      await expect(collector.page.getByRole('button', { name: 'Reject specimen' })).toBeDisabled();
      expect(api.calls).not.toContain('POST /api/v1/lab/specimens/accession-200/reject');
    });

    await test.step('withdrawal removes a report from clinician and patient view', async () => {
      await clinician.page.goto('/lab/release');
      await expect(clinician.page.getByRole('button', { name: 'Withdraw report' })).toBeDisabled();
      await clinician.page
        .locator('#release-withdraw-reason')
        .fill('Report was filed against the wrong specimen accession.');
      await clinician.page.getByRole('button', { name: 'Withdraw report' }).click();
      await expect(clinician.page.getByTestId('release-state')).toContainText(
        'Report state: Withdrawn',
      );
      await expect(clinician.page.getByTestId('withdrawn-notice')).toBeVisible();
      await expect(clinician.page.getByTestId('release-status-clinician')).toContainText(
        'Not visible',
      );
      await expect(clinician.page.getByTestId('release-status-patient')).toContainText(
        'Not visible',
      );
    });
  } finally {
    await collector.context.close();
    await clinician.context.close();
  }
});

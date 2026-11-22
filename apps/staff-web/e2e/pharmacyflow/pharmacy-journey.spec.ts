import { expect, test } from '@playwright/test';
import type { Browser, BrowserContext, Page } from '@playwright/test';
import {
  createSyntheticPharmacyApi,
  installSyntheticPharmacyApi,
} from './synthetic-pharmacy-api';
import type { SyntheticPharmacyApi } from './synthetic-pharmacy-api';

/**
 * Synthetic end-to-end pharmacy journey (RX-02, WEB-09).
 *
 * Two independent browser contexts stand in for the prescriber and the pharmacy
 * user. The prescriber leg mounts the exported review components through a
 * dev-only harness because they are not routed in the staff portal yet; the
 * pharmacy leg drives the real `/pharmacy` routes. Both legs share one
 * deterministic in-browser stand-in for `/api/v1/pharmacy/**` and
 * `/api/v1/prescriptions/**`, so the status each side sees is backed by the same
 * fact trail. No patient record, partner, credential or database is contacted.
 */

/** Open a labelled synthetic context at the configured base URL. */
async function newRole(
  browser: Browser,
  baseURL: string | undefined,
  role: string,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext(
    baseURL === undefined ? {} : { baseURL },
  );
  const page = await context.newPage();
  // The role label is only for trace context, never placed in clinical data.
  void role;
  context.setDefaultTimeout(10_000);
  return { context, page };
}

test('synthetic pharmacy journey: prescribe, clarify, substitute, accept and dispense', async ({
  browser,
  baseURL,
}) => {
  const prescriber = await newRole(browser, baseURL, 'prescriber');
  const pharmacy = await newRole(browser, baseURL, 'pharmacy');
  const api: SyntheticPharmacyApi = createSyntheticPharmacyApi();
  await installSyntheticPharmacyApi(prescriber.page, baseURL, api);
  await installSyntheticPharmacyApi(pharmacy.page, baseURL, api);

  try {
    await test.step('the prescriber attests and routes the prescription to the pharmacy', async () => {
      await prescriber.page.goto('/e2e/pharmacyflow/prescriber-harness.html');
      await expect(
        prescriber.page.getByTestId('prescriber-harness'),
      ).toBeVisible();
      await prescriber.page.getByTestId('prescribe-route').click();
      await expect(prescriber.page.getByTestId('route-status')).toContainText(
        'Delivery confirmed',
      );
      expect(api.calls).toContain(
        'POST /api/v1/prescriptions/rx-synthetic-001/transmit',
      );
      expect(api.calls).toContain(
        'POST /api/v1/prescriptions/rx-synthetic-001/confirm-delivery',
      );
    });

    await test.step('the pharmacy reads the routed prescription before responding', async () => {
      await pharmacy.page.goto('/pharmacy');
      const row = pharmacy.page.getByRole('row', { name: /SYN-PATIENT-001/ });
      await expect(row).toContainText('Delivery confirmed');
      await row.getByRole('button', { name: /Open prescription/ }).click();
      await expect(
        pharmacy.page.getByRole('heading', { name: 'Prescription detail', level: 2 }),
      ).toBeVisible();
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText(
        'Accepted: No',
      );
      await expect(pharmacy.page.getByTestId('dispensed-flag')).toContainText(
        'Dispensed: No',
      );
    });

    await test.step('the pharmacy asks a question and the prescriber answers it', async () => {
      // An empty question is refused before any command reaches the API.
      await pharmacy.page
        .getByRole('button', { name: 'Send clarification request' })
        .click();
      await expect(
        pharmacy.page.getByText('Enter a question before asking for clarification.'),
      ).toBeVisible();
      expect(api.calls).not.toContain(
        'POST /api/v1/pharmacy/status/rx-synthetic-001/clarification',
      );

      await pharmacy.page.locator('#clarify-question').fill(
        'Which strength should be dispensed?',
      );
      await pharmacy.page
        .getByRole('button', { name: 'Send clarification request' })
        .click();
      await expect(
        pharmacy.page.getByText('Command sent to the pharmacy API'),
      ).toBeVisible();
      await expect(
        pharmacy.page.locator('code', { hasText: 'clarification' }).first(),
      ).toBeVisible();
      expect(api.calls).toContain(
        'POST /api/v1/pharmacy/status/rx-synthetic-001/clarification',
      );

      await prescriber.page.getByTestId('refresh').click();
      const timeline = prescriber.page.getByRole('region', {
        name: 'Pharmacy status timeline',
      });
      await expect(
        timeline.getByTestId('timeline-row-event-synthetic-clarification'),
      ).toContainText('Clarification requested');
      const reply = prescriber.page.getByRole('region', {
        name: 'Clarification from the pharmacy',
      });
      await expect(reply).toContainText('Which strength should be dispensed?');
      await reply.locator('#clarification-answer').fill(
        'Dispense the 500 mg tablet.',
      );
      await reply.getByRole('button', { name: 'Send answer' }).click();
      await expect(reply.getByTestId('answer-body')).toContainText(
        'Dispense the 500 mg tablet.',
      );
      expect(api.calls).toContain(
        'POST /api/v1/pharmacy/clarifications/thread-synthetic-001/answer',
      );
    });

    await test.step('the pharmacy proposes a substitute the prescriber must approve', async () => {
      // The pharmacy inbox has no propose control yet; the command goes through
      // the same intercepted browser endpoint the pharmacy API provides.
      const proposal = await pharmacy.page.evaluate(async () => {
        const response = await fetch(
          '/api/v1/pharmacy/substitutions/rx-synthetic-001/proposals',
          {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'X-Active-Organization': 'org-alpha',
              'X-XSRF-TOKEN': 'synthetic-csrf-token',
              'If-Match': '3',
              'Idempotency-Key': 'synthetic-propose-001',
            },
            body: JSON.stringify({
              reason: 'stock',
              note: 'Synthetic stock note',
            }),
          },
        );
        return (await response.json()) as { proposal?: { id?: string } };
      });
      expect(proposal.proposal?.id).toBe('proposal-synthetic-001');
      expect(api.calls).toContain(
        'POST /api/v1/pharmacy/substitutions/rx-synthetic-001/proposals',
      );
    });

    await test.step('the prescriber compares, attests and approves the substitution', async () => {
      await prescriber.page.getByTestId('refresh').click();
      const review = prescriber.page.getByRole('region', {
        name: 'Pharmacy substitution proposal',
      });
      await expect(review.getByTestId('proposal-state')).toContainText(
        'Pending review',
      );
      await expect(review.getByTestId('original-entry')).toContainText(
        'Syntheticmox',
      );
      await expect(review.getByTestId('proposed-entry')).toContainText(
        'Syntheticmox Alternative',
      );
      await expect(review.getByTestId('original-entry')).toContainText(
        'Original',
      );
      await expect(review.getByTestId('proposed-entry')).toContainText(
        'Proposed',
      );

      // Approval without an attestation is refused and changes nothing.
      await review.getByRole('button', { name: 'Approve' }).click();
      await expect(review.getByText('Confirm the attestation before approving.')).toBeVisible();
      await expect(review.getByTestId('proposal-state')).toContainText(
        'Pending review',
      );
      expect(api.calls).not.toContain(
        'POST /api/v1/pharmacy/substitutions/proposal-synthetic-001/approve',
      );

      await review.locator('#substitution-attestation').fill(
        'I approve the synthetic substitute for this synthetic prescription.',
      );
      await review.getByRole('button', { name: 'Approve' }).click();
      await expect(review.getByTestId('proposal-state')).toContainText(
        'Approved',
      );
      await expect(review.getByTestId('revision-link')).toContainText(
        'revision-synthetic-001',
      );
      expect(api.calls).toContain(
        'POST /api/v1/pharmacy/substitutions/proposal-synthetic-001/approve',
      );
    });

    await test.step('the pharmacy accepts and records dispensing as separate facts', async () => {
      await pharmacy.page.reload();
      await expect(
        pharmacy.page.getByRole('heading', { name: 'Prescription detail', level: 2 }),
      ).toBeVisible();
      await pharmacy.page.getByRole('button', { name: 'Accept', exact: true }).click();
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText(
        'Accepted: Yes',
      );
      await expect(pharmacy.page.getByTestId('dispensed-flag')).toContainText(
        'Dispensed: No',
      );
      expect(api.calls).toContain(
        'POST /api/v1/pharmacy/status/rx-synthetic-001/accept',
      );

      await pharmacy.page
        .getByRole('button', { name: 'Record dispensing evidence' })
        .click();
      await pharmacy.page.locator('#dispensed-by').fill('synthetic.pharmacist');
      await pharmacy.page.locator('#dispensed-at').fill('2026-11-22T10:30:00Z');
      await pharmacy.page.locator('#handed-over').fill('30 synthetic tablets');
      await pharmacy.page.getByRole('button', { name: 'Record evidence' }).click();
      await expect(pharmacy.page.getByTestId('dispensed-flag')).toContainText(
        'Dispensed: Yes',
      );
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText(
        'Accepted: Yes',
      );
      expect(api.calls).toContain(
        'POST /api/v1/pharmacy/status/rx-synthetic-001/dispensing-evidence',
      );
    });

    await test.step('the prescriber sees each fact on its own timeline row', async () => {
      await prescriber.page.getByTestId('refresh').click();
      const timeline = prescriber.page.getByRole('region', {
        name: 'Pharmacy status timeline',
      });
      await expect(
        timeline.getByTestId('timeline-row-event-synthetic-submitted'),
      ).toContainText('Submitted');
      await expect(
        timeline.getByTestId('timeline-row-event-synthetic-delivery'),
      ).toContainText('Delivery confirmed');
      await expect(
        timeline.getByTestId('timeline-row-event-synthetic-clarification'),
      ).toContainText('Clarification requested');
      await expect(
        timeline.getByTestId('timeline-row-event-synthetic-accept'),
      ).toContainText('Accepted by pharmacy');
      await expect(
        timeline.getByTestId('timeline-row-event-synthetic-dispense'),
      ).toContainText('Dispensed');
      const dispensed = timeline.getByTestId(
        'timeline-row-event-synthetic-dispense',
      );
      await expect(dispensed).toContainText('synthetic.pharmacist');
      await expect(dispensed).toContainText('30 synthetic tablets');
      // The opaque dispensing reference is never placed in a URL (WEB-02).
      expect(pharmacy.page.url()).not.toContain('SYN-PATIENT-001');
      expect(prescriber.page.url()).not.toContain('SYN-PATIENT-001');
    });
  } finally {
    await prescriber.context.close();
    await pharmacy.context.close();
  }
});

import { expect, test } from '@playwright/test';
import type { Browser, BrowserContext, Page } from '@playwright/test';
import { createSyntheticPharmacyApi } from './synthetic-pharmacy-api';
import type { SyntheticPharmacyApi } from './synthetic-pharmacy-api';

/**
 * Synthetic end-to-end pharmacy journey for RX-02 and WEB-09.
 *
 * Two independent browser contexts stand in for the prescriber and the pharmacy
 * user. Both drive real staff-web surfaces and real typed API clients; only the
 * `/api/v1/pharmacy/**` boundary is stubbed, with one shared in-memory store so a
 * command from one role is visible to the other. The prescriber's three status
 * components are not routed in the portal yet, so that leg mounts them through a
 * dev-only harness. Every subject, identifier and medication is synthetic.
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
  // Annotate the browser trace with the human role, never with clinical data,
  // so a failed step can be attributed to the right user.
  test.info().annotations.push({ type: 'role', description: role });
  context.setDefaultTimeout(10_000);
  return { context, page };
}

/** Route the synthetic prescription, then load both roles at their entry points. */
async function startJourney(
  prescriber: Page,
  pharmacy: Page,
  api: SyntheticPharmacyApi,
): Promise<void> {
  await prescriber.goto('/e2e/pharmacyflow/prescriber-harness.html');
  await expect(prescriber.getByTestId('prescriber-harness')).toBeVisible();
  await prescriber.getByRole('button', { name: 'Prescribe and route' }).click();
  await expect.poll(() => api.store.calls).toContain('POST /api/v1/pharmacy/inbox/route');
  await pharmacy.goto('/pharmacy');
  await expect(pharmacy.getByRole('heading', { name: 'Pharmacy inbox' })).toBeVisible();
  await pharmacy.reload();
}

/** Open the one routed prescription from the pharmacy worklist. */
async function openRoutedPrescription(pharmacy: Page): Promise<void> {
  const row = pharmacy.getByRole('row', { name: /SYN-PATIENT-001/ });
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: /Open prescription/ }).click();
  await expect(
    pharmacy.getByRole('heading', { level: 2, name: 'Prescription detail' }),
  ).toBeVisible();
}

test('synthetic pharmacy journey: prescribe, clarify, substitute, accept and dispense', async ({
  browser,
  baseURL,
}) => {
  const prescriber = await newRole(browser, baseURL, 'prescriber');
  const pharmacy = await newRole(browser, baseURL, 'pharmacy');
  const api = createSyntheticPharmacyApi();
  await api.installOn(prescriber.page, baseURL);
  await api.installOn(pharmacy.page, baseURL);

  try {
    await test.step('before routing, neither side has a prescription to act on', async () => {
      await pharmacy.page.goto('/pharmacy');
      await expect(pharmacy.page.getByRole('heading', { name: 'Pharmacy inbox' })).toBeVisible();
      await expect(pharmacy.page.getByRole('row', { name: /SYN-PATIENT-001/ })).toHaveCount(0);
      await prescriber.page.goto('/e2e/pharmacyflow/prescriber-harness.html');
      await expect(prescriber.page.getByTestId('prescriber-harness')).toBeVisible();
      await expect(prescriber.page.getByTestId('no-status')).toBeVisible();
    });

    await test.step('the prescriber prescribes and routes; the pharmacy receives it', async () => {
      await startJourney(prescriber.page, pharmacy.page, api);
      await expect(prescriber.page.getByTestId('timeline-row-event-synthetic-1')).toContainText(
        'Submitted',
      );
      await expect(prescriber.page.getByTestId('timeline-row-event-synthetic-2')).toContainText(
        'Delivery confirmed',
      );

      const row = pharmacy.page.getByRole('row', { name: /SYN-PATIENT-001/ });
      await expect(row).toBeVisible();
      await expect(row).toContainText('Delivery confirmed');
      await openRoutedPrescription(pharmacy.page);
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: No');
      await expect(pharmacy.page.getByTestId('dispensed-flag')).toContainText('Dispensed: No');
      await expect(pharmacy.page.getByText('delivery_confirmed')).toBeVisible();
    });

    await test.step('the pharmacy asks a question and the prescriber answers it', async () => {
      await pharmacy.page.locator('#clarify-question').fill('Which strength should be dispensed?');
      await pharmacy.page.getByRole('button', { name: 'Send clarification request' }).click();
      await expect(pharmacy.page.getByText('Command sent to the pharmacy API')).toBeVisible();
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: No');
      expect(api.store.events.map((event) => event.type)).toContain('clarification');

      await prescriber.page.getByRole('button', { name: 'Refresh status' }).click();
      await expect(prescriber.page.getByTestId('timeline-row-event-synthetic-3')).toContainText(
        'Clarification requested',
      );
      await expect(
        prescriber.page.getByTestId('clarification-entry-clarification-entry-question'),
      ).toContainText('Which strength should be dispensed?');
      await prescriber.page.locator('#clarification-answer').fill('Dispense the 500 mg tablet.');
      await prescriber.page.getByRole('button', { name: 'Send answer' }).click();
      await expect(prescriber.page.getByTestId('answer-body')).toContainText(
        'Dispense the 500 mg tablet.',
      );
      await expect.poll(() => api.store.thread?.thread.state).toBe('answered');
    });

    await test.step('the pharmacy proposes a substitute and the prescriber approves it', async () => {
      // There is no pharmacy proposal screen in this slice, so the command is
      // sent through the same browser endpoint the API provides (as in the lab
      // recollection journey). The decision itself is driven through the real
      // prescriber component and API client.
      await pharmacy.page.evaluate(async () => {
        await fetch('/api/v1/pharmacy/substitutions', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': 'synthetic-csrf-token',
          },
          body: JSON.stringify({ prescriptionId: 'rx-synthetic-001', reason: 'stock' }),
        });
      });
      expect(api.store.proposal?.proposal.state).toBe('pending');

      await prescriber.page.getByRole('button', { name: 'Refresh status' }).click();
      await expect(prescriber.page.getByTestId('proposal-state')).toContainText('Pending review');
      await expect(prescriber.page.getByTestId('original-entry')).toContainText('Syntheticmox');
      await expect(prescriber.page.getByTestId('proposed-entry')).toContainText(
        'Syntheticmox Alternative',
      );
      await prescriber.page
        .locator('#substitution-attestation')
        .fill('Synthetic attestation: the alternative is equivalent.');
      await prescriber.page.getByRole('button', { name: 'Approve' }).click();
      await expect(prescriber.page.getByTestId('proposal-state')).toContainText('Approved');
      await expect(prescriber.page.getByTestId('revision-link')).toContainText(
        'revision-synthetic-001',
      );
      expect(api.store.proposal?.appliedRevision?.id).toBe('revision-synthetic-001');
    });

    await test.step('the pharmacy accepts, then records dispensing as separate facts', async () => {
      await pharmacy.page.reload();
      await pharmacy.page.getByRole('button', { name: 'Accept', exact: true }).click();
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: Yes');
      await expect(pharmacy.page.getByTestId('dispensed-flag')).toContainText('Dispensed: No');
      expect(api.store.events.map((event) => event.type)).toContain('accepted');

      await pharmacy.page.getByRole('button', { name: 'Record dispensing evidence' }).click();
      await pharmacy.page.locator('#dispensed-by').fill('synthetic.pharmacist');
      await pharmacy.page.locator('#dispensed-at').fill('2026-11-22T10:30:00Z');
      await pharmacy.page.locator('#handed-over').fill('30 synthetic tablets');
      await pharmacy.page.getByRole('button', { name: 'Record evidence' }).click();
      await expect(pharmacy.page.getByTestId('dispensed-flag')).toContainText('Dispensed: Yes');
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: Yes');
      expect(api.store.events.map((event) => event.type)).toContain('dispensed');
    });

    await test.step('the prescriber reads acceptance and dispensing as two distinct rows', async () => {
      await prescriber.page.getByRole('button', { name: 'Refresh status' }).click();
      await expect(prescriber.page.getByTestId('timeline-row-event-synthetic-4')).toContainText(
        'Accepted by pharmacy',
      );
      await expect(prescriber.page.getByTestId('timeline-row-event-synthetic-5')).toContainText(
        'Dispensed',
      );
      const dispensedRow = prescriber.page.getByTestId('timeline-row-event-synthetic-5');
      await expect(dispensedRow).toContainText('synthetic.pharmacist');
      await expect(dispensedRow).toContainText('30 synthetic tablets');
      await expect(dispensedRow).toContainText('2026-11-22T10:30:00Z');
      // Acceptance never implies dispensing: the two rows carry separate words.
      await expect(prescriber.page.getByTestId('timeline-row-event-synthetic-4')).not.toContainText(
        '30 synthetic tablets',
      );
    });

    await test.step('the prescriber reads the same status words in Arabic', async () => {
      await prescriber.page.getByRole('button', { name: 'العربية' }).click();
      await expect(prescriber.page.locator('html')).toHaveAttribute('dir', 'rtl');
      await expect(
        prescriber.page.getByRole('heading', { name: 'الجدول الزمني لحالة الصيدلية' }),
      ).toBeVisible();
      await expect(prescriber.page.getByTestId('timeline-row-event-synthetic-4')).toContainText(
        'مقبولة من الصيدلية',
      );
      await expect(prescriber.page.getByTestId('timeline-row-event-synthetic-5')).toContainText(
        'مُصروفة',
      );
    });

    // Opaque local identifiers only; no patient datum belongs in a URL (WEB-02).
    expect(pharmacy.page.url()).not.toContain('SYN-PATIENT-001');
  } finally {
    await prescriber.context.close();
    await pharmacy.context.close();
  }
});

test('an empty clarification question is refused without writing a fact', async ({
  browser,
  baseURL,
}) => {
  const prescriber = await newRole(browser, baseURL, 'prescriber');
  const pharmacy = await newRole(browser, baseURL, 'pharmacy');
  const api = createSyntheticPharmacyApi();
  await api.installOn(prescriber.page, baseURL);
  await api.installOn(pharmacy.page, baseURL);

  try {
    await startJourney(prescriber.page, pharmacy.page, api);
    await openRoutedPrescription(pharmacy.page);
    await pharmacy.page.getByRole('button', { name: 'Send clarification request' }).click();
    await expect(
      pharmacy.page.getByText('Enter a question before asking for clarification.'),
    ).toBeVisible();
    expect(api.store.events.map((event) => event.type)).not.toContain('clarification');
    await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: No');
  } finally {
    await prescriber.context.close();
    await pharmacy.context.close();
  }
});

test('a stale version precondition is refused without appending a fact', async ({
  browser,
  baseURL,
}) => {
  const prescriber = await newRole(browser, baseURL, 'prescriber');
  const pharmacy = await newRole(browser, baseURL, 'pharmacy');
  const api = createSyntheticPharmacyApi();
  await api.installOn(prescriber.page, baseURL);
  await api.installOn(pharmacy.page, baseURL);

  try {
    await startJourney(prescriber.page, pharmacy.page, api);
    await openRoutedPrescription(pharmacy.page);

    // Defence in depth at the boundary: the routed prescription is at version
    // 2, so a command carrying version 1 must be refused like the real API
    // refuses a stale `If-Match` (WEB-09). This direct POST is not a UI action.
    const stale = await pharmacy.page.evaluate(async () => {
      const response = await fetch(
        '/api/v1/pharmacy/status/rx-synthetic-001/accept',
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': 'synthetic-csrf-token',
            'If-Match': '1',
          },
          body: '{}',
        },
      );
      return { status: response.status, body: (await response.json()) as { code: string } };
    });
    expect(stale.status).toBe(412);
    expect(stale.body.code).toBe('VERSION_CONFLICT');
    expect(api.store.events.map((event) => event.type)).not.toContain('accepted');
    await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: No');
  } finally {
    await prescriber.context.close();
    await pharmacy.context.close();
  }
});

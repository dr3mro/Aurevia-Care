import { expect, test } from '@playwright/test';
import type { Browser, BrowserContext, Page } from '@playwright/test';
import { createSyntheticPharmacyApi, installSyntheticPharmacyApi } from './synthetic-pharmacy-api';
import type { SyntheticPharmacyApi } from './synthetic-pharmacy-api';

/**
 * Synthetic end-to-end pharmacy journey (PHARM-08, RX-02, WEB-09).
 *
 * Two independent browser contexts stand in for the prescriber and the pharmacy
 * user. Both drive real staff-web surfaces and real typed API clients; only the
 * `/api/v1/pharmacy/**` boundary and the logical prescription commands are
 * stubbed, with one shared in-memory fact trail so a command from one role is
 * visible to the other. The prescriber's three status components are not routed
 * in the portal yet, so that leg mounts them through a dev-only harness. The
 * stand-in records both a flat `METHOD /path` command log and the typed store
 * projections, so each leg can assert what it sent and what the other read. No
 * patient record, partner, credential or database is contacted. Every subject,
 * identifier and medicine is synthetic.
 */

/** Open a labelled synthetic context at the configured base URL. */
async function newRole(
  browser: Browser,
  baseURL: string | undefined,
  role: string,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext(baseURL === undefined ? {} : { baseURL });
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
  await installSyntheticPharmacyApi(prescriber.page, baseURL, api);
  await installSyntheticPharmacyApi(pharmacy.page, baseURL, api);

  try {
    await test.step('before routing, neither side has a prescription to act on', async () => {
      await pharmacy.page.goto('/pharmacy');
      await expect(pharmacy.page.getByRole('heading', { name: 'Pharmacy inbox' })).toBeVisible();
      await expect(pharmacy.page.getByRole('row', { name: /SYN-PATIENT-001/ })).toHaveCount(0);
      await prescriber.page.goto('/e2e/pharmacyflow/prescriber-harness.html');
      await expect(prescriber.page.getByTestId('prescriber-harness')).toBeVisible();
      await expect(prescriber.page.getByTestId('no-status')).toBeVisible();
    });

    await test.step('the prescriber attests and routes; the pharmacy receives it', async () => {
      await startJourney(prescriber.page, pharmacy.page, api);
      // The harness keeps an explicit route control, its confirmation output,
      // and both logical prescription commands behind the single route call.
      await expect(prescriber.page.getByTestId('prescribe-route')).toBeVisible();
      await expect(prescriber.page.getByTestId('route-status')).toContainText('Delivery confirmed');
      expect(api.calls).toContain('POST /api/v1/pharmacy/inbox/route');
      expect(api.calls).toContain('POST /api/v1/prescriptions/rx-synthetic-001/transmit');
      expect(api.calls).toContain('POST /api/v1/prescriptions/rx-synthetic-001/confirm-delivery');

      const timeline = prescriber.page.getByRole('region', {
        name: 'Pharmacy status timeline',
      });
      await expect(timeline.getByTestId('timeline-row-event-synthetic-submitted')).toContainText(
        'Submitted',
      );
      await expect(timeline.getByTestId('timeline-row-event-synthetic-delivery')).toContainText(
        'Delivery confirmed',
      );

      const row = pharmacy.page.getByRole('row', { name: /SYN-PATIENT-001/ });
      await expect(row).toBeVisible();
      await expect(row).toContainText('Delivery confirmed');
      await openRoutedPrescription(pharmacy.page);
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: No');
      await expect(pharmacy.page.getByTestId('dispensed-flag')).toContainText('Dispensed: No');
      await expect(pharmacy.page.getByText('delivery_confirmed')).toBeVisible();
      await expect(pharmacy.page.getByText('submission-synthetic-001')).toBeVisible();
    });

    await test.step('a wrong prescription id is refused without mutating shared state', async () => {
      // A wrong id must be refused at every read and command boundary before any
      // fact is appended (WEB-09 error state). These are raw browser requests:
      // the screens only ever address the one routed id.
      const eventCount = api.store.events.length;
      const missing = await pharmacy.page.evaluate(async () => {
        const inbox = await fetch('/api/v1/pharmacy/inbox/rx-unknown');
        const status = await fetch('/api/v1/pharmacy/status/rx-unknown');
        const command = await fetch('/api/v1/pharmacy/status/rx-unknown/accept', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': 'synthetic-csrf-token',
          },
          body: '{}',
        });
        return {
          inbox: { status: inbox.status, body: (await inbox.json()) as { code: string } },
          status: { status: status.status, body: (await status.json()) as { code: string } },
          command: { status: command.status, body: (await command.json()) as { code: string } },
        };
      });
      expect(missing.inbox.status).toBe(404);
      expect(missing.inbox.body.code).toBe('NOT_FOUND');
      expect(missing.status.status).toBe(404);
      expect(missing.status.body.code).toBe('NOT_FOUND');
      expect(missing.command.status).toBe(404);
      expect(missing.command.body.code).toBe('NOT_FOUND');
      expect(api.store.events.length).toBe(eventCount);
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: No');
    });

    await test.step('the pharmacy asks a question and the prescriber answers it', async () => {
      // An empty question is refused before any command reaches the API.
      await pharmacy.page.getByRole('button', { name: 'Send clarification request' }).click();
      await expect(
        pharmacy.page.getByText('Enter a question before asking for clarification.'),
      ).toBeVisible();
      expect(api.calls).not.toContain(
        'POST /api/v1/pharmacy/status/rx-synthetic-001/clarification',
      );
      expect(api.store.events.map((event) => event.type)).not.toContain('clarification');

      await pharmacy.page.locator('#clarify-question').fill('Which strength should be dispensed?');
      await pharmacy.page.getByRole('button', { name: 'Send clarification request' }).click();
      await expect(pharmacy.page.getByText('Command sent to the pharmacy API')).toBeVisible();
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: No');
      expect(api.calls).toContain('POST /api/v1/pharmacy/status/rx-synthetic-001/clarification');
      expect(api.store.events.map((event) => event.type)).toContain('clarification');

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
      await expect(
        prescriber.page.getByTestId('clarification-entry-clarification-entry-question'),
      ).toContainText('Which strength should be dispensed?');
      await reply.locator('#clarification-answer').fill('Dispense the 500 mg tablet.');
      await reply.getByRole('button', { name: 'Send answer' }).click();
      await expect(reply.getByTestId('answer-body')).toContainText('Dispense the 500 mg tablet.');
      expect(api.calls).toContain(
        'POST /api/v1/pharmacy/clarifications/thread-synthetic-001/answer',
      );
      await expect.poll(() => api.store.thread?.thread.state).toBe('answered');
    });

    await test.step('the pharmacy proposes a substitute the prescriber must approve', async () => {
      // The pharmacy inbox has no propose control yet, so the command goes
      // through the same intercepted browser endpoint the pharmacy API provides.
      const proposal = await pharmacy.page.evaluate(async () => {
        const response = await fetch('/api/v1/pharmacy/substitutions/rx-synthetic-001/proposals', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Active-Organization': 'org-alpha',
            'X-XSRF-TOKEN': 'synthetic-csrf-token',
            'If-Match': '3',
            'Idempotency-Key': 'synthetic-propose-001',
          },
          body: JSON.stringify({ reason: 'stock', note: 'Synthetic stock note' }),
        });
        return (await response.json()) as { proposal?: { id?: string } };
      });
      expect(proposal.proposal?.id).toBe('proposal-synthetic-001');
      expect(api.calls).toContain('POST /api/v1/pharmacy/substitutions/rx-synthetic-001/proposals');
      expect(api.store.proposal?.proposal.state).toBe('pending');
    });

    await test.step('the prescriber compares, attests and approves the substitution', async () => {
      await prescriber.page.getByTestId('refresh').click();
      const review = prescriber.page.getByRole('region', {
        name: 'Pharmacy substitution proposal',
      });
      await expect(review.getByTestId('proposal-state')).toContainText('Pending review');
      await expect(review.getByTestId('original-entry')).toContainText('Syntheticmox');
      await expect(review.getByTestId('proposed-entry')).toContainText('Syntheticmox Alternative');
      await expect(review.getByTestId('original-entry')).toContainText('Original');
      await expect(review.getByTestId('proposed-entry')).toContainText('Proposed');

      // Approval without an attestation is refused and changes nothing.
      await review.getByRole('button', { name: 'Approve' }).click();
      await expect(review.getByText('Confirm the attestation before approving.')).toBeVisible();
      await expect(review.getByTestId('proposal-state')).toContainText('Pending review');
      expect(api.calls).not.toContain(
        'POST /api/v1/pharmacy/substitutions/proposal-synthetic-001/approve',
      );

      await review
        .locator('#substitution-attestation')
        .fill('I approve the synthetic substitute for this synthetic prescription.');
      await review.getByRole('button', { name: 'Approve' }).click();
      await expect(review.getByTestId('proposal-state')).toContainText('Approved');
      await expect(review.getByTestId('revision-link')).toContainText('revision-synthetic-001');
      expect(api.calls).toContain(
        'POST /api/v1/pharmacy/substitutions/proposal-synthetic-001/approve',
      );
      expect(api.store.proposal?.appliedRevision?.id).toBe('revision-synthetic-001');
    });

    await test.step('the pharmacy accepts and records dispensing as separate facts', async () => {
      await pharmacy.page.reload();
      await expect(
        pharmacy.page.getByRole('heading', { name: 'Prescription detail', level: 2 }),
      ).toBeVisible();
      await pharmacy.page.getByRole('button', { name: 'Accept', exact: true }).click();
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: Yes');
      await expect(pharmacy.page.getByTestId('dispensed-flag')).toContainText('Dispensed: No');
      expect(api.calls).toContain('POST /api/v1/pharmacy/status/rx-synthetic-001/accept');
      expect(api.store.events.map((event) => event.type)).toContain('accepted');

      await pharmacy.page.getByRole('button', { name: 'Record dispensing evidence' }).click();
      await pharmacy.page.locator('#dispensed-by').fill('synthetic.pharmacist');
      await pharmacy.page.locator('#dispensed-at').fill('2026-11-22T10:30:00Z');
      await pharmacy.page.locator('#handed-over').fill('30 synthetic tablets');
      await pharmacy.page.getByRole('button', { name: 'Record evidence' }).click();
      await expect(pharmacy.page.getByTestId('dispensed-flag')).toContainText('Dispensed: Yes');
      await expect(pharmacy.page.getByTestId('accepted-flag')).toContainText('Accepted: Yes');
      expect(api.calls).toContain(
        'POST /api/v1/pharmacy/status/rx-synthetic-001/dispensing-evidence',
      );
      expect(api.store.events.map((event) => event.type)).toContain('dispensed');
    });

    await test.step('the prescriber sees each fact on its own timeline row', async () => {
      await prescriber.page.getByRole('button', { name: 'Refresh status' }).click();
      const timeline = prescriber.page.getByRole('region', {
        name: 'Pharmacy status timeline',
      });
      await expect(timeline.getByTestId('timeline-row-event-synthetic-submitted')).toContainText(
        'Submitted',
      );
      await expect(timeline.getByTestId('timeline-row-event-synthetic-delivery')).toContainText(
        'Delivery confirmed',
      );
      await expect(
        timeline.getByTestId('timeline-row-event-synthetic-clarification'),
      ).toContainText('Clarification requested');
      const accepted = timeline.getByTestId('timeline-row-event-synthetic-accept');
      await expect(accepted).toContainText('Accepted by pharmacy');
      const dispensed = timeline.getByTestId('timeline-row-event-synthetic-dispense');
      await expect(dispensed).toContainText('Dispensed');
      await expect(dispensed).toContainText('synthetic.pharmacist');
      await expect(dispensed).toContainText('30 synthetic tablets');
      await expect(dispensed).toContainText('2026-11-22T10:30:00Z');
      // Acceptance never implies dispensing: the two rows carry separate words.
      await expect(accepted).not.toContainText('30 synthetic tablets');
      // Opaque local identifiers only; no patient datum belongs in a URL (WEB-02).
      expect(pharmacy.page.url()).not.toContain('SYN-PATIENT-001');
      expect(prescriber.page.url()).not.toContain('SYN-PATIENT-001');
    });

    await test.step('the prescriber reads the same status words in Arabic', async () => {
      await prescriber.page.getByRole('button', { name: 'العربية' }).click();
      await expect(prescriber.page.locator('html')).toHaveAttribute('dir', 'rtl');
      await expect(
        prescriber.page.getByRole('heading', { name: 'الجدول الزمني لحالة الصيدلية' }),
      ).toBeVisible();
      await expect(
        prescriber.page.getByTestId('timeline-row-event-synthetic-accept'),
      ).toContainText('مقبولة من الصيدلية');
      await expect(
        prescriber.page.getByTestId('timeline-row-event-synthetic-dispense'),
      ).toContainText('مُصروفة');
    });
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
      const response = await fetch('/api/v1/pharmacy/status/rx-synthetic-001/accept', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'synthetic-csrf-token',
          'If-Match': '1',
        },
        body: '{}',
      });
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

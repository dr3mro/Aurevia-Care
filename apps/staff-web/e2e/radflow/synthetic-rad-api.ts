import type { Page } from '@playwright/test';

/**
 * Stateful synthetic imaging worklist boundary for the browser journeys
 * (WEB-10, RAD-01). The real worklist kernel refuses a study link whose
 * patient or accession label does not match the order, so this stand-in mirrors
 * that 422 `LABEL_MISMATCH` contract. Only synthetic identifiers are used.
 */

/** One synthetic worklist row in the wire shape used by the staff API client. */
export interface WorklistRow {
  orderId: string;
  performingSiteId: string;
  patientId: string;
  orderCode: string;
  urgency: string;
  status: string;
  version: number;
  scheduledAt: string | null;
  modality: string | null;
  accession: string | null;
  accessionIssuer: string | null;
  studyInstanceUid: string | null;
}

/** The performing site that owns the synthetic order. */
export const site = 'site-rad-main';

/** Opaque identifiers of the synthetic order. */
export const orderId = 'rad-001';
export const orderCode = 'ORD-001';
export const patientId = 'synthetic-patient-001';
export const wrongPatientId = 'synthetic-patient-999';
export const accession = 'ACC-001';
export const accessionIssuer = 'SYN-ORG';
export const scheduledAt = '2026-03-09T09:00:00Z';
export const studyInstanceUid = '1.2.826.0.1.3680043.2.1.3';
export const workstationRef = 'workstation-1';

/** Command names recorded by the API mock, in the order the browser sent them. */
export type CommandNames = string[];

/** Move the worklist row forward for one accepted command. */
function advanceRow(row: WorklistRow, action: string, body: Record<string, string>): WorklistRow {
  const version = row.version + 1;
  switch (action) {
    case 'schedule':
      return {
        ...row,
        status: 'SCHEDULED',
        version,
        scheduledAt: body.scheduledAt ?? null,
        modality: body.modality ?? null,
        accession: body.accession ?? null,
        accessionIssuer: body.accessionIssuer ?? null,
      };
    case 'studies':
      return {
        ...row,
        status: 'PERFORMED',
        version,
        studyInstanceUid: body.studyInstanceUid ?? null,
      };
    default:
      return { ...row, status: 'REPORTED', version };
  }
}

/** A study link naming another patient or accession is refused by the kernel. */
function isMismatchedStudyLink(
  row: WorklistRow,
  action: string,
  body: Record<string, string>,
): boolean {
  return (
    action === 'studies' && (body.patientId !== row.patientId || body.accession !== row.accession)
  );
}

/**
 * Install a stateful synthetic worklist API. Reads return the current row;
 * commands advance it forward exactly as the real worklist kernel does, and a
 * study link with a wrong patient or accession is refused with 422.
 *
 * @param page browser page whose `/api/v1/**` requests are intercepted
 * @returns the mutable command log, in arrival order
 */
export async function mockWorklistApi(page: Page): Promise<CommandNames> {
  const commands: CommandNames = [];
  let row: WorklistRow = {
    orderId,
    performingSiteId: site,
    patientId,
    orderCode,
    urgency: 'ROUTINE',
    status: 'RECEIVED',
    version: 1,
    scheduledAt: null,
    modality: null,
    accession: null,
    accessionIssuer: null,
    studyInstanceUid: null,
  };

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();

    if (method === 'GET' && url.pathname === '/api/v1/appointments') {
      // A second performing site's order is returned by the API but must be
      // filtered out before it is painted (defence in depth, RAD-01).
      const otherSiteRow: WorklistRow = {
        ...row,
        orderId: 'rad-005',
        orderCode: 'ORD-005',
        performingSiteId: 'site-rad-other',
      };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([row, otherSiteRow]),
      });
      return;
    }

    const segments =
      /^\/api\/v1\/radiology\/worklist\/orders\/([^/]+)\/(schedule|studies|report)$/.exec(
        url.pathname,
      );
    if (method === 'POST' && segments?.[1] === orderId && segments[2] !== undefined) {
      const action = segments[2];
      const body = JSON.parse(request.postData() ?? '{}') as Record<string, string>;
      commands.push(action);

      if (isMismatchedStudyLink(row, action, body)) {
        await route.fulfill({
          status: 422,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'LABEL_MISMATCH' }),
        });
        return;
      }

      row = advanceRow(row, action, body);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ orderId: row.orderId, status: row.status, version: row.version }),
      });
      return;
    }

    await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
  });

  return commands;
}

/**
 * Give the client a synthetic CSRF cookie so state-changing commands are sent.
 *
 * @param page browser page whose context receives the synthetic cookie
 */
export async function allowSyntheticWrites(page: Page): Promise<void> {
  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'synthetic-csrf-token',
      url: new URL(page.url()).origin,
    },
  ]);
}

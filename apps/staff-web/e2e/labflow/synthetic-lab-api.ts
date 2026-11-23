import type { Page, Route } from '@playwright/test';

/**
 * A deterministic, in-browser stand-in for the laboratory API (LAB-01, LAB-02).
 *
 * The staff-web lab screens are real routes that call the real transport, but a
 * browser journey must not need a live patient record, partner or database. This
 * harness intercepts only `/api/v1/lab/**` requests from one page, serves opaque
 * synthetic accessions and records every command so the journey can assert what
 * was actually sent. No real identifier, credential or patient data is present.
 */

/** The bounded accession states mirrored from the collection feature. */
export type SyntheticAccessionState =
  'accessioned' | 'collected' | 'received' | 'rejected' | 'recollected';

/** One synthetic accession in the shape the collection page consumes. */
export interface SyntheticAccession {
  id: string;
  version: number;
  accessionNo: string;
  accessionIssuer: string;
  patientId: string;
  sourceOrderRef: string;
  state: SyntheticAccessionState;
  specimenType: string | null;
  siteCode: string | null;
  collector: string | null;
  collectedAt: string | null;
  receivedAt: string | null;
  quality: string | null;
  rejectionReason: string | null;
  rejectionNotification: string | null;
  replacesAccessionId: string | null;
}

/** The first collector's synthetic accession, used for collect. */
export const alphaAccession: SyntheticAccession = {
  id: 'accession-100',
  version: 1,
  accessionNo: 'ACC-100',
  accessionIssuer: 'SYN-ISSUER',
  patientId: 'patient-100',
  sourceOrderRef: 'ORDER-100',
  state: 'accessioned',
  specimenType: null,
  siteCode: null,
  collector: null,
  collectedAt: null,
  receivedAt: null,
  quality: null,
  rejectionReason: null,
  rejectionNotification: null,
  replacesAccessionId: null,
};

/** The second collector's synthetic accession, used for reject then recollect. */
export const betaAccession: SyntheticAccession = {
  ...alphaAccession,
  id: 'accession-200',
  accessionNo: 'ACC-200',
  accessionIssuer: 'SYN-BETA-ISSUER',
  patientId: 'patient-200',
  sourceOrderRef: 'ORDER-200',
};

/** The linked recollection minted after the beta specimen is rejected. */
export const betaRecollection: SyntheticAccession = {
  ...betaAccession,
  id: 'accession-200-r1',
  accessionNo: 'ACC-200-R1',
  state: 'recollected',
  replacesAccessionId: 'accession-200',
};

/** Mutable state shared with the page's route handlers; safe to assert in the test. */
export interface SyntheticLabApi {
  /** Accessions currently returned by the list endpoint, in order. */
  accessions: SyntheticAccession[];
  /** `METHOD /path` for every intercepted lab request, in arrival order. */
  calls: string[];
}

/** Wrap one accession in the `AccessionViewResponse` envelope the page expects. */
function asView(accession: SyntheticAccession): { accession: SyntheticAccession; events: [] } {
  return { accession, events: [] };
}

/** Respond with a JSON body through the intercepted route. */
async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/**
 * Serve one specimen command or label request.
 *
 * @returns true when the request matched a specimen route and was answered
 */
async function handleSpecimenCommand(
  route: Route,
  api: SyntheticLabApi,
  method: string,
  path: string,
): Promise<boolean> {
  const match = /^\/api\/v1\/lab\/specimens\/([^/]+)\/(collect|reject|recollect|label)$/.exec(path);
  if (match === null) {
    return false;
  }
  const id = decodeURIComponent(match[1] ?? '');
  const accession = api.accessions.find((item) => item.id === id);
  if (accession === undefined) {
    await json(route, { code: 'NOT_FOUND' }, 404);
    return true;
  }
  switch (`${method} ${match[2] ?? ''}`) {
    case 'GET label':
      await json(route, {
        accessionNo: accession.accessionNo,
        accessionIssuer: accession.accessionIssuer,
        patientName: accession.patientId,
        patientMrn: accession.patientId,
        patientMrnIssuer: accession.accessionIssuer,
        specimenType: accession.specimenType ?? 'SERUM',
        collectedAt: accession.collectedAt ?? '2026-03-09T09:00:00Z',
      });
      return true;
    case 'POST collect':
      accession.state = 'collected';
      accession.version += 1;
      accession.specimenType = 'SERUM';
      accession.siteCode = 'MAIN';
      accession.collector = 'synthetic.collector';
      accession.collectedAt = '2026-03-09T09:00:00Z';
      accession.quality = 'ACCEPTABLE';
      await json(route, { id: accession.id, version: accession.version });
      return true;
    case 'POST reject':
      accession.state = 'rejected';
      accession.version += 1;
      accession.rejectionReason = 'haemolysed';
      accession.rejectionNotification = 'clinician:ORDER-200';
      await json(route, { id: accession.id, version: accession.version });
      return true;
    case 'POST recollect':
      if (!api.accessions.some((item) => item.id === betaRecollection.id)) {
        api.accessions.push({ ...betaRecollection });
      }
      await json(route, { id: betaRecollection.id, replacesAccessionId: id });
      return true;
    default:
      return false;
  }
}

/**
 * Install the synthetic lab API on one page and supply the write CSRF cookie
 * that the real transport requires. Call before navigating to a lab route.
 *
 * @param page page that owns the browser context to mock
 * @param baseURL configured browser base URL, used to scope the CSRF cookie
 * @returns mutable state the test inspects to prove commands reached the API
 */
export async function installSyntheticLabApi(
  page: Page,
  baseURL: string | undefined,
): Promise<SyntheticLabApi> {
  const api: SyntheticLabApi = {
    accessions: [{ ...alphaAccession }, { ...betaAccession }],
    calls: [],
  };

  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'synthetic-csrf-token',
      url: baseURL ?? 'http://127.0.0.1:5173',
    },
  ]);

  await page.route('**/api/v1/lab/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    api.calls.push(`${method} ${path}`);

    if (method === 'GET' && path === '/api/v1/lab/accessions') {
      await json(route, { items: api.accessions.map(asView), nextCursor: null });
      return;
    }

    if (await handleSpecimenCommand(route, api, method, path)) {
      return;
    }

    await json(route, { code: 'NOT_FOUND' }, 404);
  });

  return api;
}

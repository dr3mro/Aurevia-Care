/**
 * PAT-02 clause 2 acceptance proof: every released-report download is authorized
 * against the linked patient and the still-current release.
 *
 * The patient app never decides access locally. It requests a fresh server
 * authorization over the deployed GET route and then binds that authorization
 * to the authenticated patient, the exact revision tapped and a current release
 * before exposing a document reference. The negative cases vary exactly one field
 * of a valid authorization so the refusal can only come from the rule under test; if that check were
 * removed, the promise would resolve and the test would fail.
 *
 * Fixtures are synthetic identifiers only: no credential, network or clinical
 * data is involved, and no React render is needed for the client boundary.
 */
import { ReleasedReportClient } from '../../features/reports/reportClient';
import type {
  DownloadAuthorization,
  ReleasedReport,
  ReportTransport,
  TokenStore,
} from '../../features/reports/types';

/** Minimal call recorder: assert the transport boundary without a mock library. */
function recorder<A extends unknown[], R>(impl: (...args: A) => R) {
  const calls: A[] = [];
  const fn = (...args: A): R => {
    calls.push(args);
    return impl(...args);
  };
  return { fn, calls };
}

/** A released revision as it appears in the patient inbox. */
const report: ReleasedReport = {
  reportId: 'report-1',
  revision: 2,
  issuingOrganization: 'Clinic A',
  effectiveDate: '2026-06-10',
  language: 'en',
  correctionReason: 'corrected glucose value',
  supersedesRevision: 1,
};

/** A well-formed server authorization for the patient bound to the client below. */
const authorization: DownloadAuthorization = {
  reportId: 'report-1',
  revision: 2,
  documentId: 'document-2',
  checksum: 'checksum-2',
  language: 'en',
  patientId: 'patient-1',
  releaseState: 'current',
};

/** OS-backed credential seam with a synthetic stored token. */
function tokens(token: string | null): TokenStore {
  return {
    read: async () => token,
    save: async () => undefined,
    clear: async () => undefined,
  };
}

/** Protected transport that returns one canned authorization on GET and records every request. */
function transport(getResult: unknown) {
  const get = recorder(async (_path: string, _token: string): Promise<unknown> => getResult);
  const post = recorder(
    async (_path: string, _token: string, _body: unknown): Promise<unknown> => undefined,
  );
  const api: ReportTransport = { getJson: get.fn, postJson: post.fn };
  return { api, getCalls: get.calls, postCalls: post.calls };
}

/**
 * Assert a refused download that produced no document reference.
 *
 * Comparing the exact message avoids a substring match accepting a different
 * error code, and the resolved-value check fails if a tampered authorization is
 * ever handed back to the caller.
 */
async function expectRefusalWithoutDocument(client: ReleasedReportClient, code: string): Promise<void> {
  let granted: DownloadAuthorization | null = null;
  let refusal: unknown;
  try {
    granted = await client.authorizeDownload('report-1', 2);
  } catch (error) {
    refusal = error;
  }
  expect(refusal instanceof Error).toBe(true);
  expect((refusal as Error).message).toBe(code);
  expect(granted).toBeNull();
}

describe('authorize download against linked patient and current release (PAT-02 clause 2)', () => {
  it('authorizes the tapped revision only when the server authorization is linked to the patient and current', async () => {
    const fake = transport(authorization);
    const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');

    const granted = await client.authorizeDownload(report.reportId, report.revision);

    expect(granted).toEqual(authorization);
    expect(granted.patientId).toBe('patient-1');
    expect(granted.releaseState).toBe('current');
    expect(fake.getCalls[0]).toEqual([
      '/api/v1/patient/reports/report-1/download?revision=2',
      'token-1',
    ]);
    expect(fake.getCalls.length).toBe(1);
    expect(fake.postCalls.length).toBe(0);
  });

  it('refuses an authorization linked to another patient even when every other field matches', async () => {
    const fake = transport({ ...authorization, patientId: 'patient-2' });
    const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');

    await expectRefusalWithoutDocument(client, 'WRONG_PATIENT');

    expect(fake.getCalls.length).toBe(1);
    expect(fake.postCalls.length).toBe(0);
  });

  it('refuses a withdrawn release even when an older authorization exists', async () => {
    const fake = transport({ ...authorization, releaseState: 'withdrawn' });
    const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');

    await expectRefusalWithoutDocument(client, 'RELEASE_WITHDRAWN');

    expect(fake.getCalls.length).toBe(1);
    expect(fake.postCalls.length).toBe(0);
  });

  it('refuses an authorization for another report or revision', async () => {
    const tampered: unknown[] = [
      { ...authorization, reportId: 'report-2' },
      { ...authorization, revision: 3 },
    ];
    for (const payload of tampered) {
      const fake = transport(payload);
      const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');

      await expectRefusalWithoutDocument(client, 'AUTHORIZATION_MISMATCH');

      expect(fake.getCalls.length).toBe(1);
      expect(fake.postCalls.length).toBe(0);
    }
  });

  it('refuses a malformed authorization before releasing any document reference', async () => {
    const malformed: unknown[] = [
      'not an object',
      { ...authorization, documentId: '' },
      { ...authorization, releaseState: 'pending' },
    ];
    for (const payload of malformed) {
      const fake = transport(payload);
      const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');

      await expectRefusalWithoutDocument(client, 'MALFORMED_AUTHORIZATION');

      expect(fake.getCalls.length).toBe(1);
      expect(fake.postCalls.length).toBe(0);
    }
  });

  it('refuses to authorize without a stored credential', async () => {
    const fake = transport(authorization);
    const client = new ReleasedReportClient(fake.api, tokens(null), 'patient-1');

    await expectRefusalWithoutDocument(client, 'NOT_AUTHENTICATED');

    expect(fake.getCalls.length).toBe(0);
    expect(fake.postCalls.length).toBe(0);
  });
});

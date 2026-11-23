import { SecureCredentialStore } from './credentials';
import type { SecureStoreAdapter } from './credentials';
import { confirmAndAuthorizeDownload, downloadWithHandoff } from './downloadFlow';
import { correctionLabel, isRtl } from './presentation';
import { ReleasedReportClient, loadReleasedReports } from './reportClient';
import type { DownloadAuthorizer } from './reportClient';
import type { DownloadAuthorization, ReleasedReport, ReportTransport, TokenStore } from './types';

/** Minimal call recorder: the tests need no mock library to verify boundaries. */
function recorder<A extends unknown[], R>(impl: (...args: A) => R) {
  const calls: A[] = [];
  const fn = (...args: A): R => {
    calls.push(args);
    return impl(...args);
  };
  return { fn, calls };
}

const TOKEN_KEY = 'aurevia.patient.session';

const report: ReleasedReport = {
  reportId: 'report-1',
  revision: 2,
  issuingOrganization: 'Clinic A',
  effectiveDate: '2026-06-10',
  language: 'en',
  correctionReason: 'corrected glucose value',
  supersedesRevision: 1,
};

const authorization: DownloadAuthorization = {
  reportId: 'report-1',
  revision: 2,
  documentId: 'document-2',
  checksum: 'checksum-2',
  language: 'en',
  patientId: 'patient-1',
  releaseState: 'current',
};

function tokens(token: string | null): TokenStore {
  return {
    read: async () => token,
    save: async () => undefined,
    clear: async () => undefined,
  };
}

function secureAdapter(initial: string | null = null) {
  let stored = initial;
  const get = recorder(async (key: string): Promise<string | null> => {
    expect(key).toBe(TOKEN_KEY);
    return stored;
  });
  const set = recorder(async (key: string, value: string): Promise<void> => {
    expect(key).toBe(TOKEN_KEY);
    stored = value;
  });
  const del = recorder(async (key: string): Promise<void> => {
    expect(key).toBe(TOKEN_KEY);
    stored = null;
  });
  return {
    adapter: {
      getItemAsync: get.fn,
      setItemAsync: set.fn,
      deleteItemAsync: del.fn,
    } satisfies SecureStoreAdapter,
    getCalls: get.calls,
    setCalls: set.calls,
    deleteCalls: del.calls,
  };
}

/** Synthetic protected transport: GET returns the canned body; POST is recorded but never used. */
function transport(getResult: unknown) {
  const get = recorder(async (_path: string, _token: string): Promise<unknown> => getResult);
  const post = recorder(
    async (_path: string, _token: string, _body: unknown): Promise<unknown> => undefined,
  );
  return {
    api: { getJson: get.fn, postJson: post.fn } satisfies ReportTransport,
    getCalls: get.calls,
    postCalls: post.calls,
  };
}

describe('SecureCredentialStore (PAT-02)', () => {
  it('uses OS-backed storage for read, save and clear', async () => {
    const fake = secureAdapter('token-1');
    const store = new SecureCredentialStore(fake.adapter);

    await expect(store.read()).resolves.toBe('token-1');
    await store.save('token-2');
    await store.clear();

    expect(fake.getCalls.length).toBe(1);
    expect(fake.setCalls[0]).toEqual([TOKEN_KEY, 'token-2']);
    expect(fake.deleteCalls[0]).toEqual([TOKEN_KEY]);
  });

  it('defaults to the platform OS-backed adapter', () => {
    expect(new SecureCredentialStore()).toBeInstanceOf(SecureCredentialStore);
  });

  it('refuses empty and blank credentials', async () => {
    const fake = secureAdapter();
    const store = new SecureCredentialStore(fake.adapter);
    await expect(store.save('')).rejects.toThrow('INVALID_CREDENTIAL');
    await expect(store.save('   ')).rejects.toThrow('INVALID_CREDENTIAL');
    expect(fake.setCalls.length).toBe(0);
  });
});

describe('ReleasedReportClient (PAT-02)', () => {
  it('refuses an unverified patient identity at construction', () => {
    expect(() => new ReleasedReportClient(transport(authorization).api, tokens('token-1'), ''))
      .toThrow('INVALID_PATIENT');
    expect(() => new ReleasedReportClient(transport(authorization).api, tokens('token-1'), '   '))
      .toThrow('INVALID_PATIENT');
  });

  it('requires a protected credential for both commands', async () => {
    const fake = transport(authorization);
    const client = new ReleasedReportClient(fake.api, tokens(null), 'patient-1');

    await expect(client.listReleasedReports()).rejects.toThrow('NOT_AUTHENTICATED');
    await expect(client.authorizeDownload('report-1', 1)).rejects.toThrow('NOT_AUTHENTICATED');
    expect(fake.getCalls.length).toBe(0);
    expect(fake.postCalls.length).toBe(0);
  });

  it('lists reports with the stored token and no local clinical cache', async () => {
    const fake = transport([report]);
    const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');

    await expect(client.listReleasedReports()).resolves.toEqual([report]);
    expect(fake.getCalls[0]).toEqual(['/api/v1/patient/reports', 'token-1']);
    await expect(client.listReleasedReports()).resolves.toEqual([report]);
    expect(fake.getCalls.length).toBe(2);
  });

  it('rejects a non-array list response', async () => {
    const client = new ReleasedReportClient(transport({}).api, tokens('token-1'), 'patient-1');
    await expect(client.listReleasedReports()).rejects.toThrow('MALFORMED_RESPONSE');
  });

  it('rejects every malformed report shape', async () => {
    const invalid: unknown[] = [
      null,
      [],
      { ...report, reportId: '' },
      { ...report, reportId: 42 },
      { ...report, revision: 0 },
      { ...report, revision: 1.5 },
      { ...report, revision: '2' },
      { ...report, issuingOrganization: '' },
      { ...report, effectiveDate: '10-06-2026' },
      { ...report, language: 'de' },
      { ...report, correctionReason: 7 },
      { ...report, supersedesRevision: -1 },
      { ...report, correctionReason: null },
      { ...report, supersedesRevision: null },
      { ...report, correctionReason: '' },
    ];
    for (const payload of invalid) {
      const client = new ReleasedReportClient(transport([payload]).api, tokens('token-1'), 'patient-1');
      await expect(client.listReleasedReports()).rejects.toThrow('MALFORMED_REPORT');
    }
  });

  it('accepts an original report without correction context and an Arabic report', async () => {
    const original: ReleasedReport = { ...report, correctionReason: null, supersedesRevision: null };
    const arabic: ReleasedReport = { ...report, language: 'ar', reportId: 'report-2', revision: 1 };
    const client = new ReleasedReportClient(transport([original, arabic]).api, tokens('token-1'), 'patient-1');
    await expect(client.listReleasedReports()).resolves.toEqual([original, arabic]);
  });

  it('validates download inputs before any request', async () => {
    const fake = transport(authorization);
    const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');
    await expect(client.authorizeDownload('', 1)).rejects.toThrow('INVALID_REPORT_ID');
    await expect(client.authorizeDownload('report-1', 0)).rejects.toThrow('INVALID_REVISION');
    await expect(client.authorizeDownload('report-1', 1.2)).rejects.toThrow('INVALID_REVISION');
    expect(fake.getCalls.length).toBe(0);
    expect(fake.postCalls.length).toBe(0);
  });

  it('authorizes a download through the deployed GET route with the exact revision', async () => {
    const fake = transport(authorization);
    const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');
    await expect(client.authorizeDownload('report-1', 2)).resolves.toEqual(authorization);
    expect(fake.getCalls[0]).toEqual([
      '/api/v1/patient/reports/report-1/download?revision=2',
      'token-1',
    ]);
    expect(fake.postCalls.length).toBe(0);
  });

  it('encodes a report identifier used in the download path', async () => {
    const granted: DownloadAuthorization = { ...authorization, reportId: 'report/1 2' };
    const fake = transport(granted);
    const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');
    await expect(client.authorizeDownload('report/1 2', 2)).resolves.toEqual(granted);
    expect(fake.getCalls[0]).toEqual([
      '/api/v1/patient/reports/report%2F1%202/download?revision=2',
      'token-1',
    ]);
  });

  it('rejects every malformed authorization shape', async () => {
    const invalid: unknown[] = [
      'not an object',
      { ...authorization, reportId: '' },
      { ...authorization, revision: 0 },
      { ...authorization, documentId: '' },
      { ...authorization, checksum: '' },
      { ...authorization, language: 'de' },
      { ...authorization, patientId: '' },
      { ...authorization, patientId: 7 },
      { ...authorization, releaseState: 'pending' },
      { ...authorization, releaseState: null },
    ];
    for (const payload of invalid) {
      const client = new ReleasedReportClient(transport(payload).api, tokens('token-1'), 'patient-1');
      await expect(client.authorizeDownload('report-1', 2)).rejects.toThrow('MALFORMED_AUTHORIZATION');
    }
  });

  it('rejects a well-formed authorization for another report or revision', async () => {
    const otherReport = new ReleasedReportClient(
      transport({ ...authorization, reportId: 'report-2' }).api,
      tokens('token-1'),
      'patient-1',
    );
    await expect(otherReport.authorizeDownload('report-1', 2)).rejects.toThrow('AUTHORIZATION_MISMATCH');

    const otherRevision = new ReleasedReportClient(
      transport({ ...authorization, revision: 3 }).api,
      tokens('token-1'),
      'patient-1',
    );
    await expect(otherRevision.authorizeDownload('report-1', 2)).rejects.toThrow('AUTHORIZATION_MISMATCH');
  });

  it('stops download access when the current release has been withdrawn', async () => {
    const fake = transport({ ...authorization, releaseState: 'withdrawn' });
    const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');

    await expect(client.authorizeDownload('report-1', 2)).rejects.toThrow('RELEASE_WITHDRAWN');
    expect(fake.getCalls.length).toBe(1);
    expect(fake.postCalls.length).toBe(0);
  });

  it('denies a download authorized for a different linked patient', async () => {
    const fake = transport({ ...authorization, patientId: 'patient-2' });
    const client = new ReleasedReportClient(fake.api, tokens('token-1'), 'patient-1');

    await expect(client.authorizeDownload('report-1', 2)).rejects.toThrow('WRONG_PATIENT');
    expect(fake.getCalls.length).toBe(1);
    expect(fake.postCalls.length).toBe(0);
  });

  it('reports a load failure without keeping partial clinical data', async () => {
    const get = recorder(async () => {
      throw new Error('network');
    });
    const post = recorder(async () => undefined);
    const api: ReportTransport = { getJson: get.fn, postJson: post.fn };
    await expect(loadReleasedReports(new ReleasedReportClient(api, tokens('token-1'), 'patient-1'))).resolves.toEqual({
      reports: [],
      error: 'Unable to load released reports',
    });
  });

  it('returns loaded reports without an error', async () => {
    const client = new ReleasedReportClient(transport([report]).api, tokens('token-1'), 'patient-1');
    await expect(loadReleasedReports(client)).resolves.toEqual({ reports: [report], error: null });
  });
});

describe('report presentation (PAT-02)', () => {
  it('shows correction context only when both parts exist', () => {
    expect(correctionLabel(report)).toBe('Corrected revision 1 — corrected glucose value');
    expect(correctionLabel({ ...report, correctionReason: null })).toBeNull();
    expect(correctionLabel({ ...report, supersedesRevision: null })).toBeNull();
  });

  it('marks Arabic reports for RTL layout', () => {
    expect(isRtl({ ...report, language: 'ar' })).toBe(true);
    expect(isRtl(report)).toBe(false);
  });
});

describe('download confirmation (PAT-02)', () => {
  function authorizer() {
    const calls: [string, number][] = [];
    const client: DownloadAuthorizer = {
      authorizeDownload: async (id, revision) => {
        calls.push([id, revision]);
        return authorization;
      },
    };
    return { client, calls };
  }

  it('does not authorize when the patient cancels', async () => {
    const fake = authorizer();
    const messages: string[] = [];
    const confirm = async (message: string): Promise<boolean> => {
      messages.push(message);
      return false;
    };
    await expect(confirmAndAuthorizeDownload(fake.client, report, confirm)).resolves.toBeNull();
    expect(fake.calls.length).toBe(0);
    expect(messages[0]).toBe('Download report-1 revision 2?');
  });

  it('authorizes the exact revision after explicit confirmation', async () => {
    const fake = authorizer();
    await expect(confirmAndAuthorizeDownload(fake.client, report, async () => true)).resolves.toEqual(
      authorization,
    );
    expect(fake.calls[0]).toEqual(['report-1', 2]);
  });

  it('hands off the exact authorization once after confirmation', async () => {
    const fake = authorizer();
    const handoff = recorder((granted: DownloadAuthorization): void => {
      expect(granted).toEqual(authorization);
    });
    await expect(
      downloadWithHandoff(fake.client, report, async () => true, handoff.fn),
    ).resolves.toBeUndefined();
    expect(fake.calls).toEqual([['report-1', 2]]);
    expect(handoff.calls).toEqual([[authorization]]);
  });

  it('does not hand off a document when the patient cancels', async () => {
    const fake = authorizer();
    const handoff = recorder((_granted: DownloadAuthorization): void => undefined);
    await expect(
      downloadWithHandoff(fake.client, report, async () => false, handoff.fn),
    ).resolves.toBeUndefined();
    expect(fake.calls.length).toBe(0);
    expect(handoff.calls.length).toBe(0);
  });

  it.each([
    'MALFORMED_AUTHORIZATION',
    'AUTHORIZATION_MISMATCH',
    'WRONG_PATIENT',
    'RELEASE_WITHDRAWN',
    'NOT_AUTHENTICATED',
  ])('does not hand off a document when authorization fails with %s', async (code) => {
    const handoff = recorder((_granted: DownloadAuthorization): void => undefined);
    const client: DownloadAuthorizer = {
      authorizeDownload: async () => {
        throw new Error(code);
      },
    };
    await expect(
      downloadWithHandoff(client, report, async () => true, handoff.fn),
    ).rejects.toThrow(code);
    expect(handoff.calls.length).toBe(0);
  });
});

/**
 * PAT-02 clause-only proof: saving a local copy of a released report requires the
 * patient's explicit intent first. These tests drive the real prompt and the real
 * confirmation kernel together, so the button the patient presses is what decides
 * whether the exact revision is authorized. The prompt is not the server's
 * authorization decision: the report client still checks the verified patient and
 * the current release.
 *
 * Synthetic fixtures only; no clinical bytes, credentials or real identifiers.
 */
import { Alert } from 'react-native';
import type { AlertButton } from 'react-native';

import { confirmAndAuthorizeDownload, createAlertConfirmer } from '../../features/reports/downloadFlow';
import type { DownloadAuthorizer } from '../../features/reports/reportClient';
import type { DownloadAuthorization, ReleasedReport } from '../../features/reports/types';

/** Synthetic released report fixture: an opaque revision, never a real patient record. */
const report: ReleasedReport = {
  reportId: 'report-1',
  revision: 2,
  issuingOrganization: 'Clinic A',
  effectiveDate: '2026-06-10',
  language: 'en',
  correctionReason: 'corrected glucose value',
  supersedesRevision: 1,
};

/** Synthetic server authorization bound to the exact synthetic report revision. */
const authorization: DownloadAuthorization = {
  reportId: 'report-1',
  revision: 2,
  documentId: 'document-2',
  checksum: 'checksum-2',
  language: 'en',
  patientId: 'patient-1',
  releaseState: 'current',
};

/**
 * Minimal call recorder: records each boundary invocation without a mock library.
 *
 * @param impl the real implementation to delegate to
 * @returns the wrapped function and the ordered list of its argument tuples
 */
function recorder<A extends unknown[], R>(impl: (...args: A) => R) {
  const calls: A[] = [];
  const fn = (...args: A): R => {
    calls.push(args);
    return impl(...args);
  };
  return { fn, calls };
}

/**
 * Start the production intent flow against a synthetic authorizer.
 *
 * @param client recorder-backed client capability; its calls prove whether a
 * document reference could be produced
 * @returns the pending flow and the captured native alert button arrays
 */
function startIntentFlow(client: DownloadAuthorizer): {
  pending: Promise<DownloadAuthorization | null>;
  prompts: AlertButton[][];
} {
  const prompts: AlertButton[][] = [];
  Alert.alert = (_title, _message, buttons) => {
    if (buttons) {
      prompts.push(buttons);
    }
  };
  return {
    pending: confirmAndAuthorizeDownload(client, report, createAlertConfirmer()),
    prompts,
  };
}

describe('Confirm user intent for saving a local copy (PAT-02)', () => {
  const originalAlert = Alert.alert;

  afterEach(() => {
    Alert.alert = originalAlert;
  });

  it('pressing Download in the save-copy prompt authorizes only the exact report revision', async () => {
    const authorize = recorder(
      async (reportId: string, revision: number): Promise<DownloadAuthorization> => {
        expect(reportId).toBe(report.reportId);
        expect(revision).toBe(report.revision);
        return authorization;
      },
    );
    const { pending, prompts } = startIntentFlow({ authorizeDownload: authorize.fn });

    expect(prompts).toHaveLength(1);
    prompts[0]?.[1]?.onPress?.();

    await expect(pending).resolves.toEqual(authorization);
    expect(authorize.calls).toEqual([['report-1', 2]]);
  });

  it('pressing Cancel in the save-copy prompt produces no authorization and never calls the server', async () => {
    const authorize = recorder(
      async (_reportId: string, _revision: number): Promise<DownloadAuthorization> => authorization,
    );
    const { pending, prompts } = startIntentFlow({ authorizeDownload: authorize.fn });

    expect(prompts).toHaveLength(1);
    prompts[0]?.[0]?.onPress?.();

    await expect(pending).resolves.toBeNull();
    expect(authorize.calls).toEqual([]);
  });
});

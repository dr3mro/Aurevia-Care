import { createElement } from 'react';
import { render } from '@testing-library/react-native';

import { ReleasedReportClient } from '../../features/reports/reportClient';
import { ReleasedReportsScreen } from '../../features/reports/ReleasedReportsScreen';
import type { ReleasedReport, ReportTransport, TokenStore } from '../../features/reports/types';

/**
 * Synthetic released-report projection used across this clause's proof (PAT-02).
 *
 * It carries a complete correction pair so the display can be asserted for a
 * corrected revision, and uses only invented identifiers and organization names.
 */
const report: ReleasedReport = {
  reportId: 'report-1',
  revision: 2,
  issuingOrganization: 'Clinic A',
  effectiveDate: '2026-06-10',
  language: 'en',
  correctionReason: 'corrected glucose value',
  supersedesRevision: 1,
};

/**
 * Minimal OS-backed credential store fake for the authenticated patient.
 *
 * @param token synthetic session token, or null when no session exists
 * @returns a token store that always reports the supplied credential
 */
function tokens(token: string | null): TokenStore {
  return {
    read: async () => token,
    save: async () => undefined,
    clear: async () => undefined,
  };
}

/**
 * Minimal protected transport that returns a fixed decoded response body.
 *
 * The test owns the decoded value so a malformed projection can be injected at
 * the client trust boundary without a network or a mock library.
 *
 * @param getResult decoded body returned by the protected GET
 * @returns a transport whose POST is never used by a read-only clause
 */
function transport(getResult: unknown): ReportTransport {
  return {
    getJson: async () => getResult,
    postJson: async () => undefined,
  };
}

/**
 * Client bound to the verified synthetic patient for the read-only clause.
 *
 * @param body decoded list body the transport will return
 * @returns a client whose token and patient identity are valid, so any
 * rejection comes from projection parsing rather than authentication
 */
function clientFor(body: unknown): ReleasedReportClient {
  return new ReleasedReportClient(transport(body), tokens('token-1'), 'patient-1');
}

describe('PAT-02 clause 1 — released report metadata display', () => {
  it('showsRevisionIssuerEffectiveDateAndCorrectionContextOnReleasedReport', async () => {
    const view = await render(
      createElement(ReleasedReportsScreen, {
        reports: [report],
        error: null,
        onDownload: () => undefined,
      }),
    );

    expect(view.getByLabelText('revision')).toHaveTextContent('Revision 2');
    expect(view.getByLabelText('issuer')).toHaveTextContent('Clinic A');
    expect(view.getByLabelText('effective-date')).toHaveTextContent('2026-06-10');
    expect(view.getByLabelText('correction')).toHaveTextContent('Corrected revision 1 — corrected glucose value');
  });

  it('rejectsReleasedReportMissingIssuerOrEffectiveDate', async () => {
    const missingIssuer = clientFor([{ ...report, issuingOrganization: '' }]);
    await expect(missingIssuer.listReleasedReports()).rejects.toThrow('MALFORMED_REPORT');

    const invalidDate = clientFor([{ ...report, effectiveDate: '10-06-2026' }]);
    await expect(invalidDate.listReleasedReports()).rejects.toThrow('MALFORMED_REPORT');
  });

  it('rejectsCorrectionContextThatIsOnlyHalfPresent', async () => {
    const supersedesOnly = clientFor([{ ...report, correctionReason: null }]);
    await expect(supersedesOnly.listReleasedReports()).rejects.toThrow('MALFORMED_REPORT');

    const reasonOnly = clientFor([{ ...report, supersedesRevision: null }]);
    await expect(reasonOnly.listReleasedReports()).rejects.toThrow('MALFORMED_REPORT');
  });
});

import { Alert } from 'react-native';
import type { AlertButton } from 'react-native';
import { fireEvent, render } from '@testing-library/react-native';

import { confirmAndAuthorizeDownload, createAlertConfirmer } from './downloadFlow';
import { ReleasedReportsScreen } from './ReleasedReportsScreen';
import type { DownloadAuthorization, ReleasedReport } from './types';

const report: ReleasedReport = {
  reportId: 'report-1',
  revision: 2,
  issuingOrganization: 'Clinic A',
  effectiveDate: '2026-06-10',
  language: 'en',
  correctionReason: 'corrected glucose value',
  supersedesRevision: 1,
};

describe('ReleasedReportsScreen (PAT-02)', () => {
  it('shows a load error and no reports state', async () => {
    const view = await render(
      <ReleasedReportsScreen reports={[]} error="Unable to load released reports" onDownload={() => undefined} />,
    );
    expect(view.getByLabelText('report-error')).toHaveTextContent('Unable to load released reports');
    expect(view.queryByLabelText('no-reports')).toBeNull();
  });

  it('shows an empty state when the patient has no reports', async () => {
    const view = await render(<ReleasedReportsScreen reports={[]} error={null} onDownload={() => undefined} />);
    expect(view.getByLabelText('no-reports')).toHaveTextContent('No released reports');
    expect(view.queryByLabelText('report-error')).toBeNull();
  });

  it('shows revision, issuer, effective date, correction context and language', async () => {
    const calls: ReleasedReport[] = [];
    const view = await render(
      <ReleasedReportsScreen
        reports={[report]}
        error={null}
        onDownload={(selected) => {
          calls.push(selected);
        }}
      />,
    );
    expect(view.getByLabelText('revision')).toHaveTextContent('Revision 2');
    expect(view.getByLabelText('issuer')).toHaveTextContent('Clinic A');
    expect(view.getByLabelText('effective-date')).toHaveTextContent('2026-06-10');
    expect(view.getByLabelText('correction')).toHaveTextContent('Corrected revision 1 — corrected glucose value');
    expect(view.getByLabelText('language')).toHaveTextContent('LTR');
    expect(view.getByLabelText('report-report-1').props.style.direction).toBe('ltr');
    await fireEvent.press(view.getByLabelText('download-report-1'));
    expect(calls[0]).toEqual(report);
  });

  it('omits the correction banner for originals and marks Arabic reports RTL', async () => {
    const original: ReleasedReport = {
      ...report,
      correctionReason: null,
      supersedesRevision: null,
      language: 'ar',
    };
    const view = await render(
      <ReleasedReportsScreen reports={[original]} error={null} onDownload={() => undefined} />,
    );
    expect(view.queryByLabelText('correction')).toBeNull();
    expect(view.getByLabelText('language')).toHaveTextContent('RTL');
    expect(view.getByLabelText('report-report-1').props.style.direction).toBe('rtl');
  });
});

describe('screen to authorization integration (PAT-02)', () => {
  it('authorizes the exact revision tapped in the report list', async () => {
    const calls: [string, number][] = [];
    const client = {
      authorizeDownload: async (reportId: string, revision: number): Promise<DownloadAuthorization> => {
        calls.push([reportId, revision]);
        return {
          reportId,
          revision,
          documentId: 'document-2',
          checksum: 'checksum-2',
          language: 'en',
          patientId: 'patient-1',
          releaseState: 'current',
        };
      },
    };
    let pending: Promise<unknown> | undefined;
    const view = await render(
      <ReleasedReportsScreen
        reports={[report]}
        error={null}
        onDownload={(selected) => {
          pending = confirmAndAuthorizeDownload(client, selected, async () => true);
        }}
      />,
    );
    await fireEvent.press(view.getByLabelText('download-report-1'));
    await pending;
    expect(calls[0]).toEqual(['report-1', 2]);
  });
});

describe('createAlertConfirmer (PAT-02)', () => {
  const originalAlert = Alert.alert;

  afterEach(() => {
    Alert.alert = originalAlert;
  });

  it('resolves true only when the patient chooses download', async () => {
    let buttons: AlertButton[] | undefined;
    let title: unknown;
    let message: unknown;
    Alert.alert = (alertTitle, alertMessage, alertButtons) => {
      title = alertTitle;
      message = alertMessage;
      buttons = alertButtons;
    };
    const pending = createAlertConfirmer()('Download report-1 revision 2?');
    expect(title).toBe('Save a local copy');
    expect(message).toBe('Download report-1 revision 2?');
    expect(buttons?.length).toBe(2);
    buttons?.[1]?.onPress?.();
    await expect(pending).resolves.toBe(true);
  });

  it('resolves false when the patient cancels', async () => {
    let buttons: AlertButton[] | undefined;
    Alert.alert = (_title, _message, alertButtons) => {
      buttons = alertButtons;
    };
    const pending = createAlertConfirmer()('Download report-1 revision 2?');
    buttons?.[0]?.onPress?.();
    await expect(pending).resolves.toBe(false);
  });
});

/**
 * READY-48 acceptance tests: each of the three patient tabs reads its data
 * through the READY-47 transport and presents all five required states with a
 * fake transport (PAT-01, PAT-02, PAT-03, WEB-09).
 */
import { jest } from '@jest/globals';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { createElement } from 'react';
import type { ReactElement } from 'react';

import { clinicalCacheSize } from '../api/clinicalCache';
import { TransportError } from '../api/transport';
import type { Transport } from '../api/transport';
import type { DownloadAuthorization, TokenStore } from '../features/reports/types';

import {
  AppointmentListPage,
  ConversationInboxPage,
  ReleasedReportsPage,
} from './patientPages';
import type { PatientPageProps } from './patientPages';

/** Synthetic server payloads: no real patient data or clinical narrative. */
const appointmentPayload = [{
  id: 'apt-1',
  version: 1,
  patientId: 'synthetic-patient',
  facilityCode: 'synthetic-facility',
  facilityTimeZone: 'Asia/Riyadh',
  startsAt: '2026-11-21T10:00:00Z',
  status: 'booked',
  confirmationEnabled: true,
  confirmedAt: null,
}];

const reportPayload = [{
  reportId: 'report-1',
  revision: 1,
  issuingOrganization: 'Synthetic clinic',
  effectiveDate: '2026-11-20',
  language: 'en',
  correctionReason: null,
  supersedesRevision: null,
}];

const inboxPayload = {
  entries: [{
    conversation: {
      id: 'conv-1',
      version: 1,
      patientId: 'synthetic-patient',
      patientSubject: 'synthetic-subject',
      unitKey: 'synthetic-lab',
      connectionId: 'conn-1',
      topic: 'follow up',
      state: 'awaiting_staff',
      assigneeSubject: null,
      patientReadSequence: 0,
      staffReadSequence: 1,
      createdAt: '2026-11-21T10:00:00Z',
    },
    unreadCount: 2,
  }],
};

/** Build a transport whose GET is controlled by the test and whose writes are inert. */
function fakeTransport(get: () => Promise<unknown>): Transport {
  return {
    get: jest.fn(get) as Transport['get'],
    post: jest.fn(async () => undefined) as Transport['post'],
    put: jest.fn(async () => undefined) as Transport['put'],
  };
}

interface PageCase {
  readonly name: string;
  readonly page: (props: PatientPageProps) => ReactElement;
  readonly payload: unknown;
  readonly emptyPayload: unknown;
  readonly readyMarker: string;
}

const pageCases: readonly PageCase[] = [
  {
    name: 'appointments',
    page: AppointmentListPage,
    payload: appointmentPayload,
    emptyPayload: [],
    readyMarker: 'appointment-list',
  },
  {
    name: 'reports',
    page: ReleasedReportsPage,
    payload: reportPayload,
    emptyPayload: [],
    readyMarker: 'released-reports',
  },
  {
    name: 'conversations',
    page: ConversationInboxPage,
    payload: inboxPayload,
    emptyPayload: { entries: [] },
    readyMarker: 'conversation inbox',
  },
];

const baseProps = {
  locale: 'en' as const,
  organizationId: 'synthetic-organization',
  patientId: 'synthetic-patient',
  onSessionExpired: () => undefined,
};

function readyMarker(view: { getByTestId: (id: string) => unknown; getByLabelText: (label: string) => unknown }, marker: string): unknown {
  return marker === 'conversation inbox' || marker === 'released-reports'
    ? view.getByLabelText(marker)
    : view.getByTestId(marker);
}

describe.each(pageCases)('$name tab through the transport', (pageCase) => {
  it('renders the screen with data returned by the patient endpoint', async () => {
    const transport = fakeTransport(async () => pageCase.payload);
    const view = await render(
      <>{createElement(pageCase.page, { ...baseProps, transport, onSessionExpired: () => undefined })}</>,
    );
    await waitFor(() => expect(readyMarker(view, pageCase.readyMarker)).toBeTruthy());
  });

  it('shows the empty state for an empty patient projection', async () => {
    const view = await render(
      <>
        {createElement(pageCase.page, {
          ...baseProps,
          transport: fakeTransport(async () => pageCase.emptyPayload),
          onSessionExpired: () => undefined,
        })}
      </>,
    );
    await waitFor(() => expect(view.getByTestId('screen-empty')).toBeTruthy());
  });

  it('shows a generic error state on a server failure', async () => {
    const view = await render(
      <>
        {createElement(pageCase.page, {
          ...baseProps,
          transport: fakeTransport(async () => {
            throw new TransportError('server', {});
          }),
          onSessionExpired: () => undefined,
        })}
      </>,
    );
    await waitFor(() => expect(view.getByTestId('screen-error')).toBeTruthy());
  });

  it('shows the offline state when the transport cannot reach the API', async () => {
    const view = await render(
      <>
        {createElement(pageCase.page, {
          ...baseProps,
          transport: fakeTransport(async () => {
            throw new TransportError('network', {});
          }),
          onSessionExpired: () => undefined,
        })}
      </>,
    );
    await waitFor(() => expect(view.getByTestId('screen-offline')).toBeTruthy());
  });

  it('calls onSessionExpired on an unauthenticated response', async () => {
    const onSessionExpired = jest.fn();
    await render(
      <>
        {createElement(pageCase.page, {
          ...baseProps,
          transport: fakeTransport(async () => {
            throw new TransportError('unauthenticated', {});
          }),
          onSessionExpired,
        })}
      </>,
    );
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
  });

  it('shows the loading state while the request is in flight', async () => {
    const view = await render(
      <>
        {createElement(pageCase.page, {
          ...baseProps,
          transport: fakeTransport(() => new Promise<unknown>(() => undefined)),
          onSessionExpired: () => undefined,
        })}
      </>,
    );
    expect(view.getByTestId('screen-loading')).toBeTruthy();
  });
});

/** Synthetic authorization for the tapped revision; no clinical bytes are present. */
const downloadAuthorization: DownloadAuthorization = {
  reportId: 'report-1',
  revision: 2,
  documentId: 'document-2',
  checksum: 'checksum-2',
  language: 'en',
  patientId: 'synthetic-patient',
  releaseState: 'current',
};

/** The tapped row is revision 2; the shared payload above stays at revision 1. */
const downloadableReports = [{ ...reportPayload[0]!, revision: 2 }];
const downloadPath = '/api/v1/patient/reports/report-1/download?revision=2';

/** Transport that serves the list, one canned authorization, and nothing else. */
function downloadTransport(downloadResult: unknown, failure?: Error): Transport {
  const get = jest.fn(async (path: string): Promise<unknown> => {
    if (path === '/api/v1/patient/reports') {
      return downloadableReports;
    }
    if (path.includes('/download')) {
      if (failure !== undefined) {
        throw failure;
      }
      return downloadResult;
    }
    throw new Error(`unexpected request: ${path}`);
  }) as Transport['get'];
  return {
    get,
    post: jest.fn(async () => undefined) as Transport['post'],
    put: jest.fn(async () => undefined) as Transport['put'],
  };
}

/** Synthetic download seams: the confirmation and handoff are recorded, not native. */
function downloadSeam(downloadResult: unknown, failure?: Error) {
  const tokens: TokenStore = {
    read: jest.fn(async () => 'synthetic-token'),
    save: jest.fn(async () => undefined),
    clear: jest.fn(async () => undefined),
  };
  return {
    transport: downloadTransport(downloadResult, failure),
    tokens,
    confirm: jest.fn(async () => true),
    handoff: jest.fn(async () => undefined),
  };
}

describe('released report download from the signed-in page (PAT-02)', () => {
  it('authorizes the tapped revision through the deployed GET route before handing off', async () => {
    const fake = downloadSeam(downloadAuthorization);
    const view = await render(
      <ReleasedReportsPage
        {...baseProps}
        transport={fake.transport}
        reportDownload={fake}
        onSessionExpired={() => undefined}
      />,
    );
    await waitFor(() => expect(view.getByLabelText('released-reports')).toBeTruthy());
    await fireEvent.press(view.getByTestId('download-report-1'));
    await waitFor(() => expect(fake.handoff).toHaveBeenCalledTimes(1));

    expect(fake.confirm).toHaveBeenCalledTimes(1);
    expect(fake.transport.get).toHaveBeenCalledWith(downloadPath);
    expect(fake.transport.get).toHaveBeenCalledTimes(2);
    expect(fake.transport.post).not.toHaveBeenCalled();
    expect(fake.tokens.read).toHaveBeenCalled();
    expect(fake.handoff).toHaveBeenCalledWith(downloadAuthorization);
    expect(clinicalCacheSize()).toBe(0);
  }, 10_000);

  it('issues no request and no handoff when the patient declines', async () => {
    const fake = downloadSeam(downloadAuthorization);
    const confirm = jest.fn(async () => false);
    const view = await render(
      <ReleasedReportsPage
        {...baseProps}
        transport={fake.transport}
        reportDownload={{ tokens: fake.tokens, confirm, handoff: fake.handoff }}
        onSessionExpired={() => undefined}
      />,
    );
    await waitFor(() => expect(view.getByLabelText('released-reports')).toBeTruthy());
    await fireEvent.press(view.getByTestId('download-report-1'));
    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1));

    expect(fake.transport.get).toHaveBeenCalledTimes(1);
    expect(fake.transport.get).not.toHaveBeenCalledWith(downloadPath);
    expect(fake.handoff).not.toHaveBeenCalled();
    expect(clinicalCacheSize()).toBe(0);
  }, 10_000);

  it.each([
    ['a foreign patient', { ...downloadAuthorization, patientId: 'other-patient' }],
    ['a withdrawn release', { ...downloadAuthorization, releaseState: 'withdrawn' }],
    ['a mismatched revision', { ...downloadAuthorization, revision: 3 }],
    ['a malformed reference', { ...downloadAuthorization, checksum: '' }],
  ])('requests authorization but never hands off %s', async (_label, result) => {
    const fake = downloadSeam(result);
    const onSessionExpired = jest.fn();
    const view = await render(
      <ReleasedReportsPage
        {...baseProps}
        transport={fake.transport}
        reportDownload={fake}
        onSessionExpired={onSessionExpired}
      />,
    );
    await waitFor(() => expect(view.getByLabelText('released-reports')).toBeTruthy());
    await fireEvent.press(view.getByTestId('download-report-1'));
    await waitFor(() => expect(fake.transport.get).toHaveBeenCalledWith(downloadPath));

    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(fake.handoff).not.toHaveBeenCalled();
    expect(clinicalCacheSize()).toBe(0);
  }, 10_000);

  it('ends the session on an unauthenticated download response', async () => {
    const fake = downloadSeam(undefined, new TransportError('unauthenticated', {}));
    const onSessionExpired = jest.fn();
    const view = await render(
      <ReleasedReportsPage
        {...baseProps}
        transport={fake.transport}
        reportDownload={fake}
        onSessionExpired={onSessionExpired}
      />,
    );
    await waitFor(() => expect(view.getByLabelText('released-reports')).toBeTruthy());
    await fireEvent.press(view.getByTestId('download-report-1'));
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));

    expect(fake.handoff).not.toHaveBeenCalled();
    expect(clinicalCacheSize()).toBe(0);
  }, 10_000);

  it.each([
    ['a server failure', new TransportError('server', {})],
    ['expired access', new TransportError('forbidden', { code: 'RELEASE_NOT_EFFECTIVE' })],
  ])('keeps no document and ends no session on %s', async (_label, failure) => {
    const fake = downloadSeam(undefined, failure);
    const onSessionExpired = jest.fn();
    const view = await render(
      <ReleasedReportsPage
        {...baseProps}
        transport={fake.transport}
        reportDownload={fake}
        onSessionExpired={onSessionExpired}
      />,
    );
    await waitFor(() => expect(view.getByLabelText('released-reports')).toBeTruthy());
    await fireEvent.press(view.getByTestId('download-report-1'));
    await waitFor(() => expect(fake.transport.get).toHaveBeenCalledWith(downloadPath));

    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(fake.handoff).not.toHaveBeenCalled();
    expect(clinicalCacheSize()).toBe(0);
  }, 10_000);

  it('keeps no document when the handoff itself fails', async () => {
    const fake = downloadSeam(downloadAuthorization);
    const handoff = jest.fn(async () => {
      throw new Error('platform save unavailable');
    });
    const onSessionExpired = jest.fn();
    const view = await render(
      <ReleasedReportsPage
        {...baseProps}
        transport={fake.transport}
        reportDownload={{ tokens: fake.tokens, confirm: fake.confirm, handoff }}
        onSessionExpired={onSessionExpired}
      />,
    );
    await waitFor(() => expect(view.getByLabelText('released-reports')).toBeTruthy());
    await fireEvent.press(view.getByTestId('download-report-1'));
    await waitFor(() => expect(handoff).toHaveBeenCalledTimes(1));

    expect(handoff).toHaveBeenCalledWith(downloadAuthorization);
    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(clinicalCacheSize()).toBe(0);
  }, 10_000);

  it('uses the installed handoff seam when no test handoff is injected', async () => {
    const fake = downloadSeam(downloadAuthorization);
    const view = await render(
      <ReleasedReportsPage
        {...baseProps}
        transport={fake.transport}
        reportDownload={{ tokens: fake.tokens, confirm: fake.confirm }}
        onSessionExpired={() => undefined}
      />,
    );
    await waitFor(() => expect(view.getByLabelText('released-reports')).toBeTruthy());
    await fireEvent.press(view.getByTestId('download-report-1'));
    await waitFor(() => expect(fake.transport.get).toHaveBeenCalledWith(downloadPath));

    expect(fake.transport.post).not.toHaveBeenCalled();
    expect(clinicalCacheSize()).toBe(0);
  }, 10_000);
});

describe('patient endpoint selection', () => {
  it('uses the patient appointment endpoint with the linked patient identifier', async () => {
    const transport = fakeTransport(async () => appointmentPayload);
    await render(
      <AppointmentListPage {...baseProps} transport={transport} onSessionExpired={() => undefined} />,
    );
    expect(transport.get).toBeDefined();
    expect(transport.get).toHaveBeenCalledWith(
      '/api/v1/patient/appointments?patientId=synthetic-patient',
    );
  });

  it('renders an appointment whose status arrives in the server enum form', async () => {
    const transport = fakeTransport(async () => [{ ...appointmentPayload[0]!, status: 'BOOKED' }]);
    const view = await render(
      <AppointmentListPage {...baseProps} transport={transport} onSessionExpired={() => undefined} />,
    );
    await waitFor(() => expect(view.getByTestId('status-apt-1')).toHaveTextContent('booked'));
  });

  it('uses the patient released-report and conversation inbox endpoints', async () => {
    const reports = fakeTransport(async () => reportPayload);
    const conversations = fakeTransport(async () => inboxPayload);
    await render(<ReleasedReportsPage {...baseProps} transport={reports} onSessionExpired={() => undefined} />);
    await render(<ConversationInboxPage {...baseProps} transport={conversations} onSessionExpired={() => undefined} />);
    expect(reports.get).toHaveBeenCalledWith('/api/v1/patient/reports');
    expect(conversations.get).toHaveBeenCalledWith('/api/v1/conversations/inbox');
  });
});

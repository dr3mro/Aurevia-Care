import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { RadWorklistPage } from './RadWorklistPage';
import type { WorklistEntryResponse } from './api';
import { alphaOrganizationId, betaOrganizationId, syntheticPerformingSite } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

/** Build one synthetic wire row with overridable fields. */
function apiEntry(overrides: Partial<WorklistEntryResponse> = {}): WorklistEntryResponse {
  return {
    orderId: 'rad-001',
    performingSiteId: syntheticPerformingSite,
    patientId: 'synthetic-patient-001',
    orderCode: 'ORD-001',
    urgency: 'ROUTINE',
    status: 'RECEIVED',
    version: 1,
    scheduledAt: null,
    modality: null,
    accession: null,
    accessionIssuer: null,
    studyInstanceUid: null,
    ...overrides,
  };
}

/** The default list spans the three actionable statuses. */
const worklist: WorklistEntryResponse[] = [
  apiEntry(),
  apiEntry({
    orderId: 'rad-002',
    patientId: 'synthetic-patient-002',
    orderCode: 'ORD-002',
    urgency: 'URGENT',
    status: 'SCHEDULED',
    version: 2,
    scheduledAt: '2026-03-09T09:00:00Z',
    modality: 'CT',
    accession: 'ACC-002',
    accessionIssuer: 'SYN-ORG',
  }),
  apiEntry({
    orderId: 'rad-003',
    patientId: 'synthetic-patient-003',
    orderCode: 'ORD-003',
    urgency: 'STAT',
    status: 'PERFORMED',
    version: 3,
    scheduledAt: '2026-03-09T10:00:00Z',
    modality: 'MRI',
    accession: 'ACC-003',
    accessionIssuer: 'SYN-ORG',
    studyInstanceUid: '1.2.826',
  }),
];

/** Read the descriptor handed to the transport at a given call index. */
function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

/** Build a typed transport failure for a state test. */
function transportFailure(
  kind: ConstructorParameters<typeof TransportError>[0],
  correlationId = 'corr-1',
) {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** Deferred promise used to hold the loading state open. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** Mount the page under the same providers as the running shell. */
function element(organizationId = alphaOrganizationId, locale: Locale = 'en'): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>
          <RadWorklistPage />
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(organizationId = alphaOrganizationId, locale: Locale = 'en') {
  return render(element(organizationId, locale));
}

/** Select the row whose order code is supplied. */
function openRow(orderCode: string) {
  const row = required(screen.getByText(orderCode).closest('tr'));
  fireEvent.click(within(row).getByRole('button', { name: 'Open order' }));
}

/** Fill the received order's schedule form with a complete synthetic draft. */
function fillSchedule() {
  fireEvent.change(screen.getByLabelText('Scheduled instant (UTC ISO)'), {
    target: { value: '2026-03-09T09:00:00Z' },
  });
  fireEvent.change(screen.getByLabelText('Accession'), { target: { value: 'ACC-001' } });
  fireEvent.change(screen.getByLabelText('Accession issuer'), { target: { value: 'SYN-ORG' } });
  fireEvent.change(screen.getByLabelText('Modality'), { target: { value: 'ct' } });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('RadWorklistPage states', () => {
  it('shows the loading state until the first page resolves', async () => {
    const pending = deferred<WorklistEntryResponse[]>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(worklist);
    expect(await screen.findByText('ORD-002')).toBeTruthy();
  });

  it('shows the empty state while keeping the filters usable', async () => {
    sendMock.mockResolvedValueOnce([]);
    wrap();
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Received' })).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind, `corr-${kind}`));
      wrap();
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state for a forbidden list response', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('shows the conflict state for a precondition-required failure', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('precondition-required'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
  });

  it('retries a failed list request from the error state', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('server')).mockResolvedValue(worklist);
    wrap();
    fireEvent.click(await screen.findByRole('button', { name: stateViewText('en', 'retry') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).method).toBe('GET');
  });

  it('refetches when sign-in is chosen from the expired-session state', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired')).mockResolvedValue(worklist);
    wrap();
    fireEvent.click(await screen.findByRole('button', { name: stateViewText('en', 'signIn') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
  });
});

describe('RadWorklistPage commands', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue(worklist);
  });

  it('schedules a received order and reloads the list', async () => {
    wrap();
    await screen.findAllByText('ORD-001');
    fillSchedule();
    fireEvent.click(screen.getByRole('button', { name: 'Record schedule' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/radiology/worklist/orders/rad-001/schedule');
    expect(command.headers['If-Match']).toBe('1');
    expect(command.headers['Idempotency-Key']).toBeTruthy();
    expect(command.body).toMatchObject({
      modality: 'CT',
      accession: 'ACC-001',
      accessionIssuer: 'SYN-ORG',
      performingSiteId: syntheticPerformingSite,
    });
    expect(descriptorAt(2).method).toBe('GET');
    expect(screen.getByTestId('radworklist-announcement').textContent).toBe(
      'Command sent to the imaging worklist API',
    );
  });

  it('links a verified study through the API', async () => {
    wrap();
    await screen.findAllByText('ORD-001');
    openRow('ORD-002');
    fireEvent.change(screen.getByLabelText('Patient label from workstation'), {
      target: { value: 'synthetic-patient-002' },
    });
    fireEvent.change(screen.getByLabelText('Accession label from workstation'), {
      target: { value: 'ACC-002' },
    });
    fireEvent.change(screen.getByLabelText('Study Instance UID'), {
      target: { value: '1.2.826' },
    });
    fireEvent.change(screen.getByLabelText('Diagnostic workstation reference'), {
      target: { value: 'workstation-1' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Link study' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.url).toBe('/api/v1/radiology/worklist/orders/rad-002/studies');
    expect(command.headers['If-Match']).toBe('2');
    expect(command.body).toMatchObject({
      patientId: 'synthetic-patient-002',
      accession: 'ACC-002',
      studyInstanceUid: '1.2.826',
      workstationRef: 'workstation-1',
    });
  });

  it('blocks a wrong-patient link before any command is sent', async () => {
    wrap();
    await screen.findAllByText('ORD-001');
    openRow('ORD-002');
    fireEvent.change(screen.getByLabelText('Patient label from workstation'), {
      target: { value: 'wrong-patient' },
    });
    fireEvent.change(screen.getByLabelText('Accession label from workstation'), {
      target: { value: 'ACC-002' },
    });
    fireEvent.change(screen.getByLabelText('Study Instance UID'), { target: { value: '1.2.826' } });
    fireEvent.change(screen.getByLabelText('Diagnostic workstation reference'), {
      target: { value: 'workstation-1' },
    });

    expect(screen.getByTestId('label-check').getAttribute('data-verdict')).toBe('patient');
    const link = screen.getByRole<HTMLButtonElement>('button', { name: 'Link study' });
    expect(link.disabled).toBe(true);
    fireEvent.click(link);
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('hands off a performed study', async () => {
    wrap();
    await screen.findAllByText('ORD-001');
    openRow('ORD-003');
    fireEvent.click(screen.getByRole('button', { name: 'Hand off as reported' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.url).toBe('/api/v1/radiology/worklist/orders/rad-003/report');
    expect(command.headers['If-Match']).toBe('3');
    expect(command.headers['Idempotency-Key']).toBeTruthy();
  });

  it('shows the conflict state without discarding the typed draft', async () => {
    wrap();
    await screen.findAllByText('ORD-001');
    fillSchedule();
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(screen.getByRole('button', { name: 'Record schedule' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>('Accession').value).toBe('ACC-001');
    expect(sendMock).toHaveBeenCalledTimes(2);

    // Conflict recovery controls are live and never discard the typed draft.
    const panel = within(screen.getByTestId('radworklist-command-error'));
    fireEvent.click(panel.getByRole('button', { name: 'Compare' }));
    const compare = screen.getByTestId<HTMLTextAreaElement>('draft-disclosure');
    expect(compare.value).toContain('ACC-001');
    expect(compare.readOnly).toBe(true);
    fireEvent.click(panel.getByRole('button', { name: 'Copy my changes' }));
    expect(screen.getByTestId<HTMLTextAreaElement>('draft-disclosure').value).toContain('ACC-001');
    fireEvent.click(panel.getByRole('button', { name: 'Reload' }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
  });

  it('normalises an unexpected command rejection into a typed error', async () => {
    wrap();
    await screen.findAllByText('ORD-001');
    fillSchedule();
    sendMock.mockRejectedValueOnce(new Error('unexpected transport failure'));

    fireEvent.click(screen.getByRole('button', { name: 'Record schedule' }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });
});

describe('RadWorklistPage scoping and navigation', () => {
  it('clears the previous tenant rows before the new tenant responds', async () => {
    sendMock.mockResolvedValueOnce(worklist);
    sendMock.mockResolvedValue([]);

    const { rerender } = wrap(alphaOrganizationId);
    expect(await screen.findAllByText('ORD-001')).toBeTruthy();
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    rerender(element(betaOrganizationId));
    expect(screen.queryAllByText('ORD-001')).toEqual([]);
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });

  it('queries one status when a filter button is pressed', async () => {
    sendMock.mockResolvedValue(worklist);
    wrap();
    await screen.findAllByText('ORD-001');

    fireEvent.click(screen.getByRole('button', { name: 'Scheduled' }));

    await waitFor(() =>
      expect(sendMock.mock.calls.some((call) => call[0].url.includes('status=SCHEDULED'))).toBe(
        true,
      ),
    );
  });

  it('refetches when the performing site changes', async () => {
    sendMock.mockResolvedValue(worklist);
    wrap();
    await screen.findAllByText('ORD-001');

    fireEvent.change(screen.getByLabelText('Performing site'), {
      target: { value: 'site-rad-other' },
    });

    await waitFor(() =>
      expect(sendMock.mock.calls.some((call) => call[0].url.includes('site=site-rad-other'))).toBe(
        true,
      ),
    );
  });

  it('renders the registered radiology route in Arabic with right-to-left direction', async () => {
    sendMock.mockResolvedValue(worklist);
    const route = required(
      createRoutes(false).find((candidate) => candidate.pattern === '/radiology'),
    );
    const Page = route.component;

    render(
      <Suspense fallback={<p>Loading route</p>}>
        <ThemeProvider>
          <I18nProvider initialLocale="ar">
            <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
              <Page />
            </ActiveOrganizationProvider>
          </I18nProvider>
        </ThemeProvider>
      </Suspense>,
    );

    expect(await screen.findAllByText('ORD-001')).toBeTruthy();
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('table')).toBeTruthy();
  });

  it('shows the loading state while a re-filtered request is in flight', async () => {
    const pending = deferred<WorklistEntryResponse[]>();
    sendMock.mockResolvedValueOnce(worklist).mockReturnValueOnce(pending.promise);
    wrap();
    await screen.findAllByText('ORD-001');

    fireEvent.click(screen.getByRole('button', { name: 'Received' }));

    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    pending.resolve(worklist);
    expect(await screen.findByRole('table')).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}

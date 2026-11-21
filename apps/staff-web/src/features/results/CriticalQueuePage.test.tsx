import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { COUNTDOWN_INTERVAL_MS } from './domain';
import type { CriticalEvent, CriticalReport, CriticalResult, CriticalView } from './domain';
import { CriticalQueuePage } from './CriticalQueuePage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const ORGANIZATION = '11111111-1111-1111-1111-111111111111';

/** Synthetic critical result with a fixed due instant and no patient data. */
function critical(overrides: Partial<CriticalResult> = {}): CriticalResult {
  return {
    id: 'critical-1',
    version: 2,
    patientId: 'SYN-0001',
    sourceSystem: 'SYNTHETIC-LAB',
    sourceId: 'SYN-SOURCE-1',
    orderReference: null,
    accession: null,
    resultSummary: 'Synthetic critical finding',
    observedValue: '2.1',
    unit: 'mg/L',
    readBackRequired: false,
    state: 'awaiting_ack',
    verifiedBySubject: null,
    verifiedAt: null,
    ownerSubject: 'clinician.a',
    dueAt: '2026-03-08T12:30:00Z',
    primaryChannel: 'PRIMARY_CHANNEL',
    backupChannel: 'BACKUP_CHANNEL',
    backupRecipient: 'clinician.b',
    createdAt: '2026-03-08T11:00:00Z',
    ...overrides,
  };
}

/** Synthetic immutable event. */
function event(overrides: Partial<CriticalEvent> = {}): CriticalEvent {
  return {
    id: 'event-1',
    criticalResultId: 'critical-1',
    criticalResultVersion: 2,
    eventType: 'escalation_attempt',
    actorId: 'clinician.a',
    recipient: 'clinician.b',
    channel: 'BACKUP_CHANNEL',
    outcome: 'failed',
    detail: 'Synthetic notification attempt failed',
    occurredAt: '2026-03-08T11:30:00Z',
    ...overrides,
  };
}

/** Synthetic operational report. */
function report(overrides: Partial<CriticalReport> = {}): CriticalReport {
  return {
    overdue: [critical()],
    rosterGaps: [],
    failedNotifications: [],
    ...overrides,
  };
}

/** Synthetic single-result view. */
function view(overrides: Partial<CriticalView> = {}): CriticalView {
  return { result: critical(), events: [event()], ...overrides };
}

/** Wrap the page with the synthetic theme, locale and active organization. */
function element(ui: ReactElement, locale: Locale = 'en') {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: ORGANIZATION }}>
          <Suspense fallback={<div>Loading screen</div>}>{ui}</Suspense>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(element(ui, locale));
}

/** Typed transport failure for a state assertion. */
function failure(kind: ConstructorParameters<typeof TransportError>[0], code?: string) {
  return new TransportError(kind, {
    correlationId: `corr-${kind}`,
    code,
    retryAfterSeconds: undefined,
  });
}

function descriptorAt(index: number): RequestDescriptor {
  return sendMock.mock.calls[index]![0] as RequestDescriptor;
}

/** Control whether the clipboard API exists for the copy action test. */
function setClipboard(available: boolean) {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: available ? { writeText: vi.fn().mockResolvedValue(undefined) } : undefined,
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** Route a mocked call by endpoint so report, detail and commands stay distinct. */
function mockCriticalCalls(options: {
  post?: (descriptor: RequestDescriptor) => Promise<unknown>;
  loaded?: () => CriticalView;
  reported?: () => CriticalReport;
}) {
  sendMock.mockImplementation((descriptor: RequestDescriptor) => {
    if (descriptor.url === '/api/v1/critical-results/report') {
      return Promise.resolve(options.reported?.() ?? report());
    }
    if (descriptor.method === 'POST') {
      return options.post?.(descriptor) ?? Promise.resolve({});
    }
    return Promise.resolve(options.loaded?.() ?? view());
  });
}

async function openFirstResult() {
  fireEvent.click(await screen.findByRole('button', { name: 'Open result SYN-0001' }));
  await screen.findByRole('heading', { name: 'Critical result detail' });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('CriticalQueuePage report states', () => {
  it('shows the loading state until the report resolves', async () => {
    const pending = deferred<CriticalReport>();
    sendMock.mockReturnValueOnce(pending.promise);
    wrap(<CriticalQueuePage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    pending.resolve(report());
    expect(await screen.findByText('Synthetic critical finding')).toBeTruthy();
  });

  it('shows the empty state when the report has no findings or gaps', async () => {
    sendMock.mockResolvedValueOnce(report({ overdue: [], rosterGaps: [], failedNotifications: [] }));
    wrap(<CriticalQueuePage />);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(failure(kind));
      wrap(<CriticalQueuePage />);
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden and session-expired states', async () => {
    sendMock.mockRejectedValueOnce(failure('forbidden'));
    const forbidden = wrap(<CriticalQueuePage />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    forbidden.unmount();
    sendMock.mockRejectedValueOnce(failure('session-expired'));
    wrap(<CriticalQueuePage />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('renders the queue table under RTL', async () => {
    sendMock.mockResolvedValueOnce(report());
    wrap(<CriticalQueuePage />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByRole('table', { name: 'قائمة النتائج الحرجة' })).toBeTruthy();
  });

  it('orders rows by due time and marks overdue rows with text', async () => {
    sendMock.mockResolvedValueOnce(
      report({
        overdue: [
          critical({ id: 'later', patientId: 'SYN-0002', dueAt: '2026-03-08T15:00:00Z' }),
          critical({ id: 'sooner', patientId: 'SYN-0003', dueAt: '2026-03-08T09:00:00Z' }),
        ],
      }),
    );
    wrap(<CriticalQueuePage />);
    const table = await screen.findByRole('table');
    const headers = within(table).getAllByRole('rowheader').map((cell) => cell.textContent);
    expect(headers).toEqual(['SYN-0003', 'SYN-0002']);
    const badges = within(table).getAllByText('OVERDUE');
    expect(badges.length).toBeGreaterThan(0);
    expect(badges[0]!.style.background).not.toBe('');
    expect(table.querySelectorAll('tr[data-overdue="true"]').length).toBe(2);
  });

  it('shows an explicit no-due-time cell for an undated result', async () => {
    sendMock.mockResolvedValueOnce(
      report({ overdue: [critical({ dueAt: null })] }),
    );
    wrap(<CriticalQueuePage />);
    expect((await screen.findAllByText('No due time')).length).toBe(2);
  });

  it('shows roster gaps and failed notifications, and their explicit empty states', async () => {
    sendMock.mockResolvedValueOnce(
      report({
        overdue: [],
        rosterGaps: [event({ id: 'gap-1', detail: 'Synthetic roster gap' })],
        failedNotifications: [event({ id: 'failed-1', detail: 'Synthetic failed notification' })],
      }),
    );
    wrap(<CriticalQueuePage />);
    expect(await screen.findByText('Synthetic roster gap')).toBeTruthy();
    expect(screen.getByText('Synthetic failed notification')).toBeTruthy();
  });

  it('shows the explicit empty labels for both operational lists', async () => {
    sendMock.mockResolvedValueOnce(report());
    wrap(<CriticalQueuePage />);
    expect(await screen.findByText('No roster gaps reported.')).toBeTruthy();
    expect(screen.getByText('No failed notifications reported.')).toBeTruthy();
  });

  it('shows a failed-notification-only report', async () => {
    sendMock.mockResolvedValueOnce(
      report({ overdue: [], rosterGaps: [], failedNotifications: [event()] }),
    );
    wrap(<CriticalQueuePage />);
    expect(await screen.findByText('Synthetic notification attempt failed')).toBeTruthy();
  });

  it('refreshes the countdown every thirty seconds with a fake clock', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-03-08T12:00:00Z'));
    try {
      sendMock.mockResolvedValue(report({ overdue: [critical({ dueAt: '2026-03-08T12:00:45Z' })] }));
      wrap(<CriticalQueuePage />);
      expect(await screen.findByText('in 45 seconds')).toBeTruthy();
      await act(async () => {
        vi.advanceTimersByTime(COUNTDOWN_INTERVAL_MS);
      });
      expect(screen.getByText('in 15 seconds')).toBeTruthy();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('CriticalQueuePage route registration', () => {
  it('lazily loads the registered critical queue page', async () => {
    sendMock.mockResolvedValue(report());
    const route = createRoutes(false).find(
      (candidate) => candidate.pattern === '/results/critical',
    )!;
    render(
      element(
        <Suspense fallback={<p>Loading</p>}>
          <route.component />
        </Suspense>,
      ),
    );
    expect(await screen.findByRole('heading', { level: 1, name: 'Critical result queue' })).toBeTruthy();
  });
});

describe('CriticalQueuePage detail and commands', () => {
  beforeEach(() => {
    mockCriticalCalls({});
  });

  it('loads one result with its event trail', async () => {
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    expect(screen.getByText('Synthetic notification attempt failed')).toBeTruthy();
    expect(descriptorAt(1).url).toBe('/api/v1/critical-results/critical-1');
  });

  it('gives an empty event trail its own message', async () => {
    mockCriticalCalls({ loaded: () => view({ events: [] }) });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    expect(screen.getByText('No events have been recorded for this result.')).toBeTruthy();
  });

  it('shows the detail error state when the single result cannot load', async () => {
    sendMock.mockImplementation((descriptor: RequestDescriptor) => {
      if (descriptor.url === '/api/v1/critical-results/report') {
        return Promise.resolve(report());
      }
      return Promise.reject(failure('server'));
    });
    wrap(<CriticalQueuePage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Open result SYN-0001' }));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
  });

  it('offers only assign while the finding is unverified', async () => {
    mockCriticalCalls({ loaded: () => view({ result: critical({ state: 'unverified' }) }) });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    expect(screen.getByRole('button', { name: 'Assign owner and deadline' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Acknowledge result' })).toBeNull();
  });

  it('offers read-back only when the site policy requires it', async () => {
    mockCriticalCalls({ loaded: () => view({ result: critical({ readBackRequired: true }) }) });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    expect(screen.getByRole('button', { name: 'Record read-back' })).toBeTruthy();
  });

  it.each(['acknowledged', 'closed'] as const)('shows a status word for %s', async (state) => {
    mockCriticalCalls({ loaded: () => view({ result: critical({ state }) }) });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    expect(screen.queryByRole('button', { name: 'Acknowledge result' })).toBeNull();
    expect(screen.getByText(state === 'acknowledged' ? 'Acknowledged' : 'Closed')).toBeTruthy();
  });

  it('requires a reason before recording an escalation', async () => {
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.click(screen.getByRole('button', { name: 'Record escalation attempt' }));
    expect(screen.getByText('Enter a reason before recording this escalation.')).toBeTruthy();
    expect(sendMock.mock.calls.filter((call) => (call[0] as RequestDescriptor).method === 'POST').length).toBe(0);
  });

  it('sends the escalation reason as detail with the approved backup channel', async () => {
    mockCriticalCalls({
      post: () => Promise.resolve(critical()),
    });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    expect((screen.getByLabelText('Approved channel') as HTMLInputElement).value).toBe('BACKUP_CHANNEL');
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'Synthetic backup reason' } });
    fireEvent.click(screen.getByRole('button', { name: 'Record escalation attempt' }));
    await waitFor(() => expect(screen.getByText('Command sent to the result API')).toBeTruthy());
    const command = sendMock.mock.calls.find(
      (call) => (call[0] as RequestDescriptor).method === 'POST',
    )![0] as RequestDescriptor;
    expect(command.url).toBe('/api/v1/critical-results/critical-1/escalate');
    expect(command.headers['If-Match']).toBe('2');
    expect(command.body).toMatchObject({
      detail: 'Synthetic backup reason',
      channel: 'BACKUP_CHANNEL',
      recipient: 'clinician.b',
      outcome: 'delivered',
    });
  });

  it('records a failed attempt with the system-down flag', async () => {
    mockCriticalCalls({ post: () => Promise.resolve(critical()) });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'Synthetic outage' } });
    fireEvent.change(screen.getByLabelText('Attempt outcome'), { target: { value: 'failed' } });
    fireEvent.click(screen.getByLabelText('Primary channel unavailable (system down)'));
    fireEvent.click(screen.getByRole('button', { name: 'Record escalation attempt' }));
    await waitFor(() => expect(screen.getByText('Command sent to the result API')).toBeTruthy());
    const command = sendMock.mock.calls.find(
      (call) => (call[0] as RequestDescriptor).method === 'POST',
    )![0] as RequestDescriptor;
    expect(command.body).toMatchObject({
      outcome: 'failed',
      systemDown: true,
      detail: 'Synthetic outage',
    });
  });

  it('acknowledges the exact critical revision', async () => {
    mockCriticalCalls({ post: () => Promise.resolve(critical()) });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.change(screen.getByLabelText('Note (optional)'), { target: { value: 'Synthetic note' } });
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge result' }));
    await waitFor(() => expect(screen.getByText('Command sent to the result API')).toBeTruthy());
    const command = sendMock.mock.calls.find(
      (call) => (call[0] as RequestDescriptor).method === 'POST',
    )![0] as RequestDescriptor;
    expect(command.url).toBe('/api/v1/critical-results/critical-1/acknowledge');
    expect(command.body).toEqual({ note: 'Synthetic note' });
  });

  it('assigns an owner and a due instant', async () => {
    mockCriticalCalls({
      loaded: () => view({ result: critical({ state: 'unverified', ownerSubject: null }) }),
      post: () => Promise.resolve(critical()),
    });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    expect((screen.getByLabelText('Owner subject') as HTMLInputElement).value).toBe('');
    fireEvent.change(screen.getByLabelText('Owner subject'), { target: { value: 'clinician.a' } });
    fireEvent.change(screen.getByLabelText('Due instant (ISO-8601)'), {
      target: { value: '2026-03-08T15:00:00Z' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Assign owner and deadline' }));
    await waitFor(() => expect(screen.getByText('Command sent to the result API')).toBeTruthy());
    const command = sendMock.mock.calls.find(
      (call) => (call[0] as RequestDescriptor).method === 'POST',
    )![0] as RequestDescriptor;
    expect(command.body).toEqual({
      ownerSubject: 'clinician.a',
      dueAt: '2026-03-08T15:00:00Z',
    });
  });

  it('records a verbatim read-back', async () => {
    mockCriticalCalls({
      loaded: () => view({ result: critical({ readBackRequired: true }) }),
      post: () => Promise.resolve(critical()),
    });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.change(screen.getByLabelText('Value read back'), { target: { value: '2.1 mg/L' } });
    fireEvent.click(screen.getByRole('button', { name: 'Record read-back' }));
    await waitFor(() => expect(screen.getByText('Command sent to the result API')).toBeTruthy());
    const command = sendMock.mock.calls.find(
      (call) => (call[0] as RequestDescriptor).method === 'POST',
    )![0] as RequestDescriptor;
    expect(command.body).toEqual({ repeatedValue: '2.1 mg/L' });
  });

  it('shows a server error when assignment fails', async () => {
    mockCriticalCalls({
      loaded: () => view({ result: critical({ state: 'unverified' }) }),
      post: () => Promise.reject(failure('server')),
    });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.click(screen.getByRole('button', { name: 'Assign owner and deadline' }));
    expect(await screen.findByText('corr-server')).toBeTruthy();
  });

  it('shows a server error when escalation fails', async () => {
    mockCriticalCalls({ post: () => Promise.reject(failure('server')) });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'Synthetic reason' } });
    fireEvent.click(screen.getByRole('button', { name: 'Record escalation attempt' }));
    expect(await screen.findByText('corr-server')).toBeTruthy();
  });

  it('shows a server error when read-back fails', async () => {
    mockCriticalCalls({
      loaded: () => view({ result: critical({ readBackRequired: true }) }),
      post: () => Promise.reject(failure('server')),
    });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.click(screen.getByRole('button', { name: 'Record read-back' }));
    expect(await screen.findByText('corr-server')).toBeTruthy();
  });

  it('shows the conflict state on a 412 and keeps the command available', async () => {
    mockCriticalCalls({ post: () => Promise.reject(failure('conflict', 'STALE_VERSION')) });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge result' }));
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Acknowledge result' })).toBeTruthy();
  });

  it('offers working compare, reload and copy actions on a critical conflict', async () => {
    mockCriticalCalls({ post: () => Promise.reject(failure('conflict', 'STALE_VERSION')) });
    setClipboard(true);
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge result' }));
    await screen.findByText(stateViewText('en', 'conflict'));
    fireEvent.click(screen.getByRole('button', { name: 'Compare' }));
    expect(screen.getByText('Compare the current revision')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Copy my changes' }));
    expect(screen.getByText('Identifier copied to the clipboard')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    await waitFor(() => expect(screen.queryByText(stateViewText('en', 'conflict'))).toBeNull());
    expect(screen.queryByText('Compare the current revision')).toBeNull();
  });

  it('copies a critical conflict without a clipboard API', async () => {
    mockCriticalCalls({ post: () => Promise.reject(failure('conflict', 'STALE_VERSION')) });
    setClipboard(false);
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge result' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Copy my changes' }));
    expect(screen.getByText('Identifier copied to the clipboard')).toBeTruthy();
  });

  it('shows a non-conflict command failure with its correlation id', async () => {
    mockCriticalCalls({ post: () => Promise.reject(failure('server')) });
    wrap(<CriticalQueuePage />);
    await openFirstResult();
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge result' }));
    expect(await screen.findByText('corr-server')).toBeTruthy();
  });
});

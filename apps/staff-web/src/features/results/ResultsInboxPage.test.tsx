import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type {
  ReconciliationEntry,
  ResultDetail,
  ResultReport,
} from './domain';
import { ResultsInboxPage } from './ResultsInboxPage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const ALPHA = '11111111-1111-1111-1111-111111111111';
const BETA = '22222222-2222-2222-2222-222222222222';

/** Build a synthetic reconciliation entry with no patient data. */
function reconciliation(overrides: Partial<ReconciliationEntry> = {}): ReconciliationEntry {
  return {
    id: 'recon-1',
    identifiers: {
      patientId: 'SYN-0001',
      sourceId: 'SYN-LAB',
      accessionId: 'ACC-001',
      destinationId: 'SYN-CLINIC',
      orderId: 'ORD-001',
    },
    reason: 'unmatched',
    mismatches: ['accessionId'],
    detail: 'No matching order was registered',
    status: 'open',
    createdAt: '2026-03-08T06:30:00Z',
    ...overrides,
  };
}

/** Build one synthetic immutable result revision. */
function report(overrides: Partial<ResultReport> = {}): ResultReport {
  return {
    id: 'report-1',
    version: 2,
    orderId: 'ORD-001',
    identifiers: {
      patientId: 'SYN-0001',
      sourceId: 'SYN-LAB',
      accessionId: 'ACC-001',
      destinationId: 'SYN-CLINIC',
      orderId: 'ORD-001',
    },
    kind: 'final',
    supersedesVersion: null,
    previousId: null,
    owner: 'clinician.a',
    coverageQueue: 'SYNTHETIC-QUEUE',
    effectiveAt: '2026-03-08T06:00:00Z',
    receivedAt: '2026-03-08T06:05:00Z',
    checksum: 'synthetic-checksum',
    ...overrides,
  };
}

/** Build a review detail with optional lineage and notices. */
function detail(overrides: Partial<ResultDetail> = {}): ResultDetail {
  const current = overrides.current ?? report();
  return {
    current,
    review: { reportId: current.id, version: current.version, state: 'unreviewed', acknowledgedAt: null },
    lineage: [],
    notifications: [],
    ...overrides,
  };
}

/** Wrap a page with the synthetic theme, locale and active organization. */
function element(ui: ReactElement, organizationId: string, locale: Locale = 'en') {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>
          <Suspense fallback={<div>Loading screen</div>}>{ui}</Suspense>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, organizationId = ALPHA, locale: Locale = 'en') {
  return render(element(ui, organizationId, locale));
}

/** Build a typed transport failure for a state assertion. */
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

/**
 * Route a mocked call by endpoint: reconciliation always returns a list, the
 * result detail endpoint returns one revision, and commands use the supplied
 * post handler.
 */
function mockResultCalls(
  post: (() => Promise<unknown>) | undefined,
  loaded: () => ResultDetail = detail,
) {
  sendMock.mockImplementation((descriptor: RequestDescriptor) => {
    if (descriptor.url.includes('/reconciliations')) {
      return Promise.resolve([]);
    }
    if (descriptor.method === 'POST') {
      return post?.() ?? Promise.resolve({});
    }
    return Promise.resolve(loaded());
  });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('ResultsInboxPage reconciliation states', () => {
  it('shows the loading state until the first page resolves', async () => {
    const pending = deferred<ReconciliationEntry[]>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap(<ResultsInboxPage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve([reconciliation()]);
    expect(await screen.findByText('SYN-0001')).toBeTruthy();
  });

  it('shows the empty state when no reports are waiting', async () => {
    sendMock.mockResolvedValueOnce([]);
    wrap(<ResultsInboxPage />);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state and correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(failure(kind));
      wrap(<ResultsInboxPage />);
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state for a 403', async () => {
    sendMock.mockRejectedValueOnce(failure('forbidden'));
    wrap(<ResultsInboxPage />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(failure('session-expired'));
    wrap(<ResultsInboxPage />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('renders reconciliation rows with bilingual state words', async () => {
    sendMock.mockResolvedValueOnce([
      reconciliation(),
      reconciliation({ id: 'recon-2', reason: 'conflict', status: 'open' }),
    ]);
    wrap(<ResultsInboxPage />);
    expect(await screen.findByRole('table', { name: 'Unmatched and conflicting reports' })).toBeTruthy();
    expect(screen.getByText('Unmatched')).toBeTruthy();
    expect(screen.getByText('Conflicting identifiers')).toBeTruthy();
    expect(screen.getAllByText('accessionId').length).toBe(2);
  });

  it('renders the table and Arabic labels under RTL', async () => {
    sendMock.mockResolvedValueOnce([reconciliation()]);
    wrap(<ResultsInboxPage />, ALPHA, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByRole('table', { name: 'التقارير غير المطابقة والمتعارضة' })).toBeTruthy();
  });

  it('requests the resolved queue when the status filter changes', async () => {
    sendMock.mockResolvedValue([reconciliation()]);
    wrap(<ResultsInboxPage />);
    await screen.findByText('SYN-0001');
    fireEvent.click(screen.getByRole('button', { name: 'Resolved' }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).url).toBe('/api/v1/xch04/reconciliations?status=resolved');
    expect(screen.getByRole('button', { name: 'Resolved' }).getAttribute('aria-pressed')).toBe('true');
  });

  it('can switch back to the open status filter', async () => {
    sendMock.mockResolvedValue([reconciliation()]);
    wrap(<ResultsInboxPage />);
    await screen.findByText('SYN-0001');
    fireEvent.click(screen.getByRole('button', { name: 'Resolved' }));
    await waitFor(() => expect(descriptorAt(1).url).toContain('status=resolved'));
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    await waitFor(() => expect(descriptorAt(2).url).toContain('status=open'));
    expect(screen.getByRole('button', { name: 'Open' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'Resolved' }).getAttribute('aria-pressed')).toBe('false');
  });

  it('refetches rows for the newly active organization', async () => {
    sendMock.mockResolvedValue([reconciliation()]);
    const view = render(element(<ResultsInboxPage />, ALPHA));
    await screen.findByText('SYN-0001');
    view.rerender(element(<ResultsInboxPage />, BETA));
    await waitFor(() =>
      expect(descriptorAt(1).headers['X-Active-Organization']).toBe(BETA),
    );
  });
});

describe('ResultsInboxPage route registration', () => {
  it('lazily loads the registered inbox page', async () => {
    sendMock.mockResolvedValue([]);
    const route = createRoutes(false).find((candidate) => candidate.pattern === '/results')!;
    render(element(<Suspense fallback={<p>Loading</p>}><route.component /></Suspense>, ALPHA));
    expect(await screen.findByRole('heading', { level: 1, name: 'Results' })).toBeTruthy();
  });
});

describe('ResultsInboxPage result review', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue([]);
  });

  it('keeps the hint until a result is opened', async () => {
    wrap(<ResultsInboxPage />);
    await screen.findByText(stateViewText('en', 'empty'));
    expect(screen.getByText('No report is open. Enter an identifier above.')).toBeTruthy();
  });

  it('refuses a blank result identifier without a command', async () => {
    wrap(<ResultsInboxPage />);
    await screen.findByText(stateViewText('en', 'empty'));
    fireEvent.click(screen.getByRole('button', { name: 'Open result' }));
    expect(screen.getByText('Enter a result identifier before opening a report.')).toBeTruthy();
    expect(screen.getByLabelText('Result identifier').getAttribute('aria-invalid')).toBe('true');
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('opens a result and shows its immutable revision', async () => {
    sendMock.mockImplementation((descriptor: RequestDescriptor) =>
      Promise.resolve(
        descriptor.url.includes('/xch04/results/') ? detail() : [],
      ),
    );
    wrap(<ResultsInboxPage />);
    fireEvent.change(screen.getByLabelText('Result identifier'), { target: { value: 'report-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open result' }));
    expect(await screen.findByText('Final')).toBeTruthy();
    expect(screen.getByText('clinician.a')).toBeTruthy();
    expect(screen.getByText('Unreviewed')).toBeTruthy();
    expect(sendMock.mock.calls.some((call) => (call[0] as RequestDescriptor).url === '/api/v1/xch04/results/report-1')).toBe(true);
  });

  it('renders lineage and correction notices when the revision has them', async () => {
    sendMock.mockImplementation((descriptor: RequestDescriptor) =>
      Promise.resolve(
        descriptor.url.includes('/xch04/results/')
          ? detail({
              lineage: [report({ id: 'report-0', version: 1 })],
              notifications: [
                {
                  id: 'notice-1',
                  reportId: 'report-0',
                  recipient: 'clinician.z',
                  supersededVersion: 1,
                  createdAt: '2026-03-08T07:00:00Z',
                },
              ],
            })
          : [],
      ),
    );
    wrap(<ResultsInboxPage />);
    fireEvent.change(screen.getByLabelText('Result identifier'), { target: { value: 'report-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open result' }));
    expect(await screen.findByText('Revision lineage')).toBeTruthy();
    expect(screen.getByText(/clinician\.z · v1/)).toBeTruthy();
  });

  it('acknowledges the exact revision with its observed version', async () => {
    let acknowledged = false;
    mockResultCalls(
      () => {
        acknowledged = true;
        return Promise.resolve({
          reportId: 'report-1',
          version: 3,
          state: 'acknowledged',
          acknowledgedAt: '2026-03-08T09:00:00Z',
        });
      },
      () =>
        detail({
          review: {
            reportId: 'report-1',
            version: acknowledged ? 3 : 2,
            state: acknowledged ? 'acknowledged' : 'unreviewed',
            acknowledgedAt: null,
          },
        }),
    );
    wrap(<ResultsInboxPage />);
    fireEvent.change(screen.getByLabelText('Result identifier'), { target: { value: 'report-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open result' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Acknowledge result' }));
    await waitFor(() => expect(screen.getByText('Acknowledgment recorded for this revision')).toBeTruthy());
    const command = sendMock.mock.calls.find(
      (call) => (call[0] as RequestDescriptor).method === 'POST',
    )![0] as RequestDescriptor;
    expect(command.url).toBe('/api/v1/xch04/results/report-1/acknowledge');
    expect(command.headers['If-Match']).toBe('2');
  });

  it('shows the conflict state and keeps the typed identifier', async () => {
    mockResultCalls(() => Promise.reject(failure('conflict', 'STALE_VERSION')));
    wrap(<ResultsInboxPage />);
    fireEvent.change(screen.getByLabelText('Result identifier'), { target: { value: 'report-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open result' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Acknowledge result' }));
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect((screen.getByLabelText('Result identifier') as HTMLInputElement).value).toBe('report-1');
  });

  it('offers working compare, reload and copy actions on a conflict', async () => {
    mockResultCalls(() => Promise.reject(failure('conflict', 'STALE_VERSION')));
    setClipboard(true);
    wrap(<ResultsInboxPage />);
    fireEvent.change(screen.getByLabelText('Result identifier'), { target: { value: 'report-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open result' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Acknowledge result' }));
    await screen.findByText(stateViewText('en', 'conflict'));

    fireEvent.click(screen.getByRole('button', { name: 'Compare' }));
    expect(screen.getByText('Compare the current revision')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Copy my changes' }));
    expect(screen.getByText('Identifier copied to the clipboard')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    await waitFor(() => expect(screen.queryByText(stateViewText('en', 'conflict'))).toBeNull());
    expect(screen.queryByText('Compare the current revision')).toBeNull();
  });

  it('copies without a clipboard API without failing', async () => {
    mockResultCalls(() => Promise.reject(failure('conflict', 'STALE_VERSION')));
    setClipboard(false);
    wrap(<ResultsInboxPage />);
    fireEvent.change(screen.getByLabelText('Result identifier'), { target: { value: 'report-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open result' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Acknowledge result' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Copy my changes' }));
    expect(screen.getByText('Identifier copied to the clipboard')).toBeTruthy();
  });

  it('shows a transport error when opening a result fails', async () => {
    sendMock.mockImplementation((descriptor: RequestDescriptor) =>
      descriptor.url.includes('/xch04/results/')
        ? Promise.reject(failure('server'))
        : Promise.resolve([]),
    );
    wrap(<ResultsInboxPage />);
    fireEvent.change(screen.getByLabelText('Result identifier'), { target: { value: 'report-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open result' }));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
  });

  it('shows a non-conflict command failure with its correlation id', async () => {
    mockResultCalls(() => Promise.reject(failure('server')));
    wrap(<ResultsInboxPage />);
    fireEvent.change(screen.getByLabelText('Result identifier'), { target: { value: 'report-1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open result' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Acknowledge result' }));
    expect(await screen.findByText('corr-server')).toBeTruthy();
  });
});

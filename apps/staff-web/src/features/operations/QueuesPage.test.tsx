import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createRoutes } from '../../app/routes';
import { defined } from '../../components/invariant';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { alphaQueuesView, betaQueuesView } from './fixtures';
import { operationsText } from './labels';
import { QueuesPage } from './QueuesPage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function element(ui: ReactElement, organizationId: string): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement = <QueuesPage />, organizationId = 'org-alpha') {
  return render(element(ui, organizationId));
}

function failure(kind: ConstructorParameters<typeof TransportError>[0], correlationId = 'corr-1') {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

function descriptorAt(call: number): RequestDescriptor {
  const invocation = sendMock.mock.calls[call];
  if (invocation === undefined) {
    throw new Error('missing send call');
  }
  return invocation[0];
}

/** Open the dialog, choose resolve and type the mandatory reason. */
function startResolve() {
  fireEvent.click(screen.getByRole('button', { name: /Unsigned encounters/ }));
  const dialog = within(screen.getByRole('dialog'));
  fireEvent.change(dialog.getByLabelText(operationsText('en', 'actionLabel')), {
    target: { value: 'resolve' },
  });
  fireEvent.change(dialog.getByLabelText(operationsText('en', 'reasonLabel')), {
    target: { value: 'owner accepted the handover' },
  });
}

function confirmAction() {
  fireEvent.click(
    within(screen.getByRole('dialog')).getByRole('button', {
      name: operationsText('en', 'recordAction'),
    }),
  );
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('QueuesPage WEB-09 states', () => {
  it('shows loading until the queue view resolves', async () => {
    let resolve!: (value: typeof alphaQueuesView) => void;
    const pending = new Promise<typeof alphaQueuesView>((done) => {
      resolve = done;
    });
    sendMock.mockReturnValueOnce(pending);
    wrap();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    resolve(alphaQueuesView);
    expect(await screen.findByText(operationsText('en', 'unsigned-encounters'))).toBeTruthy();
  });

  it('shows the empty state when no queue rows are returned', async () => {
    sendMock.mockResolvedValueOnce({ rows: [], missingCoverage: alphaQueuesView.missingCoverage });
    wrap();
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline'] as const)(
    'shows the %s error with a correlation id',
    async (kind) => {
      sendMock.mockRejectedValueOnce(failure(kind, `corr-${kind}`));
      wrap();
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden and session-expired states', async () => {
    sendMock.mockRejectedValueOnce(failure('forbidden'));
    const first = wrap();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    first.unmount();

    sendMock.mockRejectedValueOnce(failure('session-expired'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('shows the conflict state with recovery actions when loading conflicts', async () => {
    sendMock.mockRejectedValueOnce(failure('conflict'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect(screen.getByText(/Working copy kept/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') }));
    expect(screen.getByText(/Draft copied/)).toBeTruthy();
    sendMock.mockResolvedValueOnce(alphaQueuesView);
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
  });
});

describe('QueuesPage recovery and route loading', () => {
  it('retries a failed load through the shared Retry action', async () => {
    sendMock.mockRejectedValueOnce(failure('server'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    sendMock.mockResolvedValueOnce(alphaQueuesView);
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'retry') }));
    expect(await screen.findByText(operationsText('en', 'unsigned-encounters'))).toBeTruthy();
  });

  it('lazily loads the registered queue page with live data', async () => {
    sendMock.mockResolvedValue(alphaQueuesView);
    const route = defined(
      createRoutes(false).find((candidate) => candidate.pattern === '/operations/queues'),
      'operations route',
    );
    render(
      <Suspense fallback={<p>Loading route</p>}>
        {element(<route.component />, 'org-alpha')}
      </Suspense>,
    );
    expect(await screen.findByText(operationsText('en', 'unsigned-encounters'))).toBeTruthy();
  });
});

describe('QueuesPage commands', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue(alphaQueuesView);
  });

  it('records a reason-bearing command and reloads the queue view', async () => {
    wrap();
    await screen.findByText(operationsText('en', 'unsigned-encounters'));
    startResolve();
    confirmAction();
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(1).method).toBe('POST');
    expect(descriptorAt(1).url).toBe('/api/v1/operations/queues/unsigned-encounters/resolve');
    expect(descriptorAt(1).body).toEqual({ reason: 'owner accepted the handover' });
    expect(descriptorAt(2).method).toBe('GET');
  });

  it('rejects a missing reason before any command is sent', async () => {
    wrap();
    await screen.findByText(operationsText('en', 'unsigned-encounters'));
    fireEvent.click(screen.getByRole('button', { name: /Unsigned encounters/ }));
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: operationsText('en', 'recordAction'),
      }),
    );
    expect(
      within(screen.getByRole('dialog')).getByText(operationsText('en', 'reasonRequired')),
    ).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('normalises an unexpected command rejection into the error state', async () => {
    wrap();
    await screen.findByText(operationsText('en', 'unsigned-encounters'));
    startResolve();
    sendMock.mockRejectedValueOnce(new Error('unexpected transport failure'));
    confirmAction();
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
    sendMock.mockResolvedValueOnce(alphaQueuesView);
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'retry') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
  });

  it('keeps the typed reason across a conflict and retries it', async () => {
    wrap();
    await screen.findByText(operationsText('en', 'unsigned-encounters'));
    startResolve();
    sendMock.mockRejectedValueOnce(failure('conflict'));
    confirmAction();
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(
      within(screen.getByRole('dialog')).getByLabelText(operationsText('en', 'reasonLabel')),
    ).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect(screen.getByText('Working copy kept: owner accepted the handover')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: operationsText('en', 'retryCommand') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(2).url).toBe('/api/v1/operations/queues/unsigned-encounters/resolve');
  });

  it('clears the previous tenant rows when the active organization changes', async () => {
    sendMock.mockResolvedValueOnce(alphaQueuesView).mockResolvedValue(betaQueuesView);
    const { rerender } = wrap();
    expect(
      await screen.findByRole('heading', { name: /Missing coverage: Imaging desk/ }),
    ).toBeTruthy();
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe('org-alpha');

    rerender(element(<QueuesPage />, 'org-beta'));
    expect(screen.queryByRole('heading', { name: /Missing coverage: Imaging desk/ })).toBeNull();
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe('org-beta');
  });

  it('discards an unsubmitted reason and a failed command when the organization changes', async () => {
    sendMock
      .mockResolvedValueOnce(alphaQueuesView)
      .mockRejectedValueOnce(failure('server'))
      .mockResolvedValueOnce(betaQueuesView);
    const { rerender } = wrap();
    await screen.findByText(operationsText('en', 'unsigned-encounters'));
    startResolve();
    confirmAction();
    expect(await screen.findByTestId('operations-command-error')).toBeTruthy();
    expect(descriptorAt(1).body).toEqual({ reason: 'owner accepted the handover' });

    rerender(element(<QueuesPage />, 'org-beta'));
    await waitFor(() =>
      expect(
        screen.queryByRole('heading', { name: /Missing coverage: Imaging desk/ }),
      ).toBeNull(),
    );
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByTestId('operations-command-error')).toBeNull();
    expect(screen.queryByText('owner accepted the handover')).toBeNull();
    expect(descriptorAt(2).headers['X-Active-Organization']).toBe('org-beta');
  });
});

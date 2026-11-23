import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createRoutes } from '../../app/routes';
import { defined } from '../../components/invariant';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { alphaInboxView, betaInboxView } from './fixtures';
import { inboxText } from './labels';
import { InboxPage } from './InboxPage';

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

function wrap(ui: ReactElement = <InboxPage />, organizationId = 'org-alpha') {
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

function composer(): HTMLElement {
  return screen.getByRole('textbox', { name: /Reply to .* from / });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('InboxPage WEB-09 states', () => {
  it('shows loading until the inbox resolves', async () => {
    let resolve!: (value: typeof alphaInboxView) => void;
    const pending = new Promise<typeof alphaInboxView>((done) => {
      resolve = done;
    });
    sendMock.mockReturnValueOnce(pending);
    wrap();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();
    resolve(alphaInboxView);
    expect(await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ })).toBeTruthy();
  });

  it('shows the empty state when there are no threads', async () => {
    sendMock.mockResolvedValueOnce({ ...alphaInboxView, threads: [] });
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

  it('shows conflict with working recovery actions', async () => {
    sendMock.mockRejectedValueOnce(failure('conflict'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect(screen.getByText(/Working copy kept/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'copyMyChanges') }));
    expect(screen.getByText(/Draft copied/)).toBeTruthy();
    sendMock.mockResolvedValueOnce(alphaInboxView);
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'reload') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
  });
});

describe('InboxPage recovery and route loading', () => {
  it('retries a failed load through the shared Retry action', async () => {
    sendMock.mockRejectedValueOnce(failure('server'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    sendMock.mockResolvedValueOnce(alphaInboxView);
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'retry') }));
    expect(
      await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ }),
    ).toBeTruthy();
  });

  it('lazily loads the registered inbox page with live data', async () => {
    sendMock.mockResolvedValue(alphaInboxView);
    const route = defined(
      createRoutes(false).find((candidate) => candidate.pattern === '/messages'),
      'messages route',
    );
    render(
      <Suspense fallback={<p>Loading route</p>}>{element(<route.component />, 'org-alpha')}</Suspense>,
    );
    expect(
      await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ }),
    ).toBeTruthy();
  });
});

describe('InboxPage commands', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue(alphaInboxView);
  });

  it('sends a reply to the named recipient and reloads the inbox', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.change(composer(), { target: { value: 'Thank you' } });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(1).method).toBe('POST');
    expect(descriptorAt(1).url).toBe('/api/v1/conversations/thread-001/reply');
    expect(descriptorAt(1).body).toMatchObject({
      recipient: 'Synthetic Grace Okafor',
      pendingFiles: ['prep-instructions.pdf'],
    });
    expect(descriptorAt(2).method).toBe('GET');
  });

  it('does not send a blank reply', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('rejects a missing reason for Assign and Resolve', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    for (const action of ['assign', 'resolve'] as const) {
      fireEvent.click(screen.getByRole('button', { name: inboxText('en', action) }));
      fireEvent.click(
        within(screen.getByRole('dialog')).getByRole('button', {
          name: inboxText('en', 'recordAction'),
        }),
      );
      expect(
        within(screen.getByRole('dialog')).getByText(inboxText('en', 'reasonRequired')),
      ).toBeTruthy();
      fireEvent.click(
        within(screen.getByRole('dialog')).getByRole('button', {
          name: inboxText('en', 'cancel'),
        }),
      );
    }
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('records a reason-bearing Assign command and reloads the inbox', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'assign') }));
    const dialog = within(screen.getByRole('dialog'));
    fireEvent.change(dialog.getByLabelText(inboxText('en', 'reasonLabel')), {
      target: { value: 'handed to backup' },
    });
    fireEvent.click(dialog.getByRole('button', { name: inboxText('en', 'recordAction') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(1).url).toBe('/api/v1/conversations/thread-001/assign');
    expect(descriptorAt(1).body).toMatchObject({ reason: 'handed to backup' });
    expect(descriptorAt(2).method).toBe('GET');
  });

  it('normalises an unexpected command rejection into the error state', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    sendMock.mockRejectedValueOnce(new Error('unexpected transport failure'));
    fireEvent.change(composer(), { target: { value: 'unexpected' } });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('records a failed work command through the shared Retry action', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'resolve') }));
    const dialog = within(screen.getByRole('dialog'));
    fireEvent.change(dialog.getByLabelText(inboxText('en', 'reasonLabel')), {
      target: { value: 'answered' },
    });
    sendMock.mockRejectedValueOnce(failure('server'));
    fireEvent.click(dialog.getByRole('button', { name: inboxText('en', 'recordAction') }));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    sendMock.mockResolvedValueOnce(alphaInboxView);
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'retry') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
  });

  it('discards the typed reply when leaving a thread', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.change(composer(), { target: { value: 'do not keep' } });
    fireEvent.click(screen.getByText('Refill question'));
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: inboxText('en', 'leave') }),
    );
    expect((composer() as HTMLTextAreaElement).value).toBe('');
  });

  it('keeps the typed reply through a conflict and retries it', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.change(composer(), { target: { value: 'kept across conflict' } });
    sendMock.mockRejectedValueOnce(failure('conflict'));
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(composer()).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'compare') }));
    expect(screen.getByText('Working copy kept: kept across conflict')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'retryCommand') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(2).url).toBe('/api/v1/conversations/thread-001/reply');
  });

  it('re-runs a failed reply through the shared Retry action', async () => {
    wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.change(composer(), { target: { value: 'retry me' } });
    sendMock.mockRejectedValueOnce(failure('server', 'corr-retry'));
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));
    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: stateViewText('en', 'retry') }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(2).url).toBe('/api/v1/conversations/thread-001/reply');
  });

});

describe('InboxPage tenant isolation', () => {
  it('clears the previous tenant rows when the active organization changes', async () => {
    sendMock.mockResolvedValueOnce(alphaInboxView).mockResolvedValue(betaInboxView);
    const { rerender } = wrap();
    expect(await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ })).toBeTruthy();
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe('org-alpha');
    rerender(element(<InboxPage />, 'org-beta'));
    expect(
      screen.queryByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ }),
    ).toBeNull();
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe('org-beta');
  });

  it('discards a typed reply and a failed command when the organization changes', async () => {
    sendMock
      .mockResolvedValueOnce(alphaInboxView)
      .mockRejectedValueOnce(failure('server'))
      .mockResolvedValueOnce(betaInboxView);
    const { rerender } = wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.change(composer(), { target: { value: 'alpha private reply' } });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));
    expect(await screen.findByTestId('inbox-command-error')).toBeTruthy();
    expect(descriptorAt(1).body).toMatchObject({ body: 'alpha private reply' });

    rerender(element(<InboxPage />, 'org-beta'));
    expect(
      await screen.findByRole('button', { name: /Synthetic Hassan Ibrahim beta: Refill question/ }),
    ).toBeTruthy();
    expect((composer() as HTMLTextAreaElement).value).toBe('');
    expect(screen.queryByTestId('inbox-command-error')).toBeNull();
    expect(screen.queryByText('alpha private reply')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));
    expect(sendMock).toHaveBeenCalledTimes(3);
    expect(descriptorAt(2).headers['X-Active-Organization']).toBe('org-beta');
  });

});

describe('InboxPage late command settlement', () => {
  it('ignores a reply that fails at the moment the tenant changes', async () => {
    let rejectReply!: (reason: unknown) => void;
    const pending = new Promise<typeof alphaInboxView>((_, reject) => {
      rejectReply = reject;
    });
    sendMock
      .mockResolvedValueOnce(alphaInboxView)
      .mockReturnValueOnce(pending)
      .mockResolvedValue(betaInboxView);
    const { rerender } = wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.change(composer(), { target: { value: 'alpha in-flight reply' } });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));

    rerender(element(<InboxPage />, 'org-beta'));
    await act(async () => {
      rejectReply(failure('server'));
      await Promise.resolve();
    });

    expect(
      await screen.findByRole('button', { name: /Synthetic Hassan Ibrahim beta: Refill question/ }),
    ).toBeTruthy();
    expect(screen.queryByTestId('inbox-command-error')).toBeNull();
    expect((composer() as HTMLTextAreaElement).value).toBe('');
  });

  it('ignores a reply that fails after the tenant changes', async () => {
    let rejectReply!: (reason: unknown) => void;
    const pending = new Promise<typeof alphaInboxView>((_, reject) => {
      rejectReply = reject;
    });
    sendMock
      .mockResolvedValueOnce(alphaInboxView)
      .mockReturnValueOnce(pending)
      .mockResolvedValueOnce(betaInboxView);
    const { rerender } = wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.change(composer(), { target: { value: 'alpha late reply' } });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));

    rerender(element(<InboxPage />, 'org-beta'));
    expect(
      await screen.findByRole('button', { name: /Synthetic Hassan Ibrahim beta: Refill question/ }),
    ).toBeTruthy();
    await act(async () => {
      rejectReply(failure('server'));
      await Promise.resolve();
    });

    expect(screen.queryByTestId('inbox-command-error')).toBeNull();
    expect(screen.queryByText('alpha late reply')).toBeNull();
    expect((composer() as HTMLTextAreaElement).value).toBe('');
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));
    expect(sendMock).toHaveBeenCalledTimes(3);
  });

  it('does not clear a beta draft when an alpha reply settles after the switch', async () => {
    let resolveReply!: (value: typeof alphaInboxView) => void;
    const pending = new Promise<typeof alphaInboxView>((done) => {
      resolveReply = done;
    });
    sendMock
      .mockResolvedValueOnce(alphaInboxView)
      .mockReturnValueOnce(pending)
      .mockResolvedValueOnce(betaInboxView);
    const { rerender } = wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.change(composer(), { target: { value: 'alpha late reply' } });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));

    rerender(element(<InboxPage />, 'org-beta'));
    expect(
      await screen.findByRole('button', { name: /Synthetic Hassan Ibrahim beta: Refill question/ }),
    ).toBeTruthy();
    fireEvent.change(composer(), { target: { value: 'beta draft' } });
    await act(async () => {
      resolveReply(alphaInboxView);
      await Promise.resolve();
    });

    expect((composer() as HTMLTextAreaElement).value).toBe('beta draft');
    expect(screen.queryByText('alpha late reply')).toBeNull();
    expect(screen.queryByTestId('inbox-command-error')).toBeNull();
  });

  it('ignores a work action that fails after the tenant changes', async () => {
    let rejectAction!: (reason: unknown) => void;
    const pending = new Promise<typeof alphaInboxView>((_, reject) => {
      rejectAction = reject;
    });
    sendMock
      .mockResolvedValueOnce(alphaInboxView)
      .mockReturnValueOnce(pending)
      .mockResolvedValueOnce(betaInboxView);
    const { rerender } = wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'resolve') }));
    const dialog = within(screen.getByRole('dialog'));
    fireEvent.change(dialog.getByLabelText(inboxText('en', 'reasonLabel')), {
      target: { value: 'alpha late action' },
    });
    fireEvent.click(dialog.getByRole('button', { name: inboxText('en', 'recordAction') }));

    rerender(element(<InboxPage />, 'org-beta'));
    expect(
      await screen.findByRole('button', { name: /Synthetic Hassan Ibrahim beta: Refill question/ }),
    ).toBeTruthy();
    await act(async () => {
      rejectAction(failure('server'));
      await Promise.resolve();
    });

    expect(screen.queryByTestId('inbox-command-error')).toBeNull();
    expect(screen.queryByText('alpha late action')).toBeNull();
    expect(sendMock).toHaveBeenCalledTimes(3);
  });

  it('does not clear a beta reason when an alpha action settles after the switch', async () => {
    let resolveAction!: (value: typeof alphaInboxView) => void;
    const pending = new Promise<typeof alphaInboxView>((done) => {
      resolveAction = done;
    });
    sendMock
      .mockResolvedValueOnce(alphaInboxView)
      .mockReturnValueOnce(pending)
      .mockResolvedValueOnce(betaInboxView);
    const { rerender } = wrap();
    await screen.findByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'resolve') }));
    const alphaDialog = within(screen.getByRole('dialog'));
    fireEvent.change(alphaDialog.getByLabelText(inboxText('en', 'reasonLabel')), {
      target: { value: 'alpha late action' },
    });
    fireEvent.click(alphaDialog.getByRole('button', { name: inboxText('en', 'recordAction') }));

    rerender(element(<InboxPage />, 'org-beta'));
    expect(
      await screen.findByRole('button', { name: /Synthetic Hassan Ibrahim beta: Refill question/ }),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'assign') }));
    const betaDialog = within(screen.getByRole('dialog'));
    fireEvent.change(betaDialog.getByLabelText(inboxText('en', 'reasonLabel')), {
      target: { value: 'beta work reason' },
    });
    await act(async () => {
      resolveAction(alphaInboxView);
      await Promise.resolve();
    });

    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(
      betaDialog.getByLabelText<HTMLInputElement>(inboxText('en', 'reasonLabel')).value,
    ).toBe('beta work reason');
    expect(screen.queryByTestId('inbox-command-error')).toBeNull();
  });
});

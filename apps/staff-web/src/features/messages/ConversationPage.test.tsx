import { StrictMode, Suspense } from 'react';
import type { ReactElement } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { RouterProvider } from '../../app/router';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import {
  alphaOrganizationId,
  betaOrganizationId,
  conversationId,
  patientId,
  syntheticConversation,
  syntheticView,
  syntheticWorkItemEvent,
} from './fixtures';
import { ConversationPage } from './ConversationPage';
import { resetReadReceipts, useUnreadCount } from './unreadStore';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

function failure(kind: ConstructorParameters<typeof TransportError>[0]) {
  return new TransportError(kind, {
    correlationId: `corr-${kind}`,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

function element(
  ui: ReactElement,
  id = conversationId,
  organizationId = alphaOrganizationId,
  locale: Locale = 'en',
) {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>
          <RouterProvider patterns={['/messages/:id']} initialPath={`/messages/${id}`}>
            {ui}
          </RouterProvider>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(id = conversationId, organizationId = alphaOrganizationId, locale: Locale = 'en') {
  return render(element(<ConversationPage />, id, organizationId, locale));
}

/** Queue the GET view and the single read POST that follows a successful load. */
function queueReady(overrides: Parameters<typeof syntheticView>[0] = {}) {
  const view = syntheticView(overrides);
  sendMock.mockResolvedValueOnce(view);
  sendMock.mockResolvedValueOnce({
    ...view.conversation,
    version: view.conversation.version + 1,
  });
  return view;
}

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

/** Probe proving the read command clears the unread count shown in the inbox. */
function UnreadProbe() {
  const count = useUnreadCount(alphaOrganizationId, conversationId, 9);
  return <span data-testid="probe-unread">{count}</span>;
}

beforeEach(() => {
  sendMock.mockReset();
  sendMock.mockResolvedValue(syntheticConversation());
  resetReadReceipts();
});

describe('ConversationPage states', () => {
  it('shows the loading state until the view resolves', async () => {
    const pending = deferred<ReturnType<typeof syntheticView>>();
    sendMock.mockReturnValueOnce(pending.promise);
    wrap();
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(syntheticView());
    expect(await screen.findByText('Synthetic patient question')).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(failure(kind));
      wrap();
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state when the unit does not allow this conversation', async () => {
    sendMock.mockRejectedValueOnce(failure('forbidden'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state', async () => {
    sendMock.mockRejectedValueOnce(failure('session-expired'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });
});

describe('ConversationPage reading', () => {
  it('renders messages in server order with sender role and time', async () => {
    queueReady();
    wrap();
    expect(await screen.findByText('Synthetic patient question')).toBeTruthy();
    const items = screen.getByRole('list', { name: 'Messages' }).querySelectorAll('li');
    expect(items).toHaveLength(2);
    expect(items[0]!.textContent).toContain('Patient');
    expect(items[0]!.textContent).toContain('Synthetic patient question');
    expect(items[1]!.textContent).toContain('Unit staff');
    expect(items[1]!.textContent).toContain('Synthetic staff reply');
    expect(screen.getAllByRole('list', { name: 'Work-item events' })).toHaveLength(1);
  });

  it('shows explicit empty states when both trails are empty', async () => {
    queueReady({ messages: [], events: [] });
    wrap();
    expect(await screen.findByText('No messages in this conversation yet.')).toBeTruthy();
    expect(screen.getByText('No work-item events yet.')).toBeTruthy();
  });

  it('renders a work-item event reason when the API supplies one', async () => {
    queueReady({
      events: [
        syntheticWorkItemEvent({
          eventType: 'assigned',
          reason: 'Synthetic assignment reason',
        }),
      ],
    });
    wrap();
    expect(await screen.findByText('Synthetic assignment reason')).toBeTruthy();
  });

  it('shows the read-only notice and hides commands for a closed thread', async () => {
    queueReady({ conversation: syntheticConversation({ state: 'closed' }) });
    wrap();
    expect(await screen.findByText('This conversation is closed and read-only.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Reply' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Assign' })).toBeNull();
  });

  it('keeps the generic title and browser tab free of patient data', async () => {
    document.title = 'Staff Portal';
    queueReady();
    wrap();
    await screen.findByText('Synthetic patient question');
    expect(screen.getByRole('heading', { name: 'Conversation' })).toBeTruthy();
    expect(document.title).toBe('Staff Portal');
    expect(document.title).not.toContain(patientId);
    expect(document.title).not.toContain('Synthetic follow-up');
  });

  it('renders in Arabic with right-to-left direction', async () => {
    queueReady();
    wrap(conversationId, alphaOrganizationId, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByRole('list', { name: 'الرسائل' })).toBeTruthy();
  });

  it('calls read exactly once with the loaded version and clears the unread count', async () => {
    queueReady();
    render(
      <>
        <UnreadProbe />
        {element(<ConversationPage />)}
      </>,
    );
    await screen.findByText('Synthetic patient question');
    await waitFor(() => expect(screen.getByTestId('probe-unread').textContent).toBe('0'));

    const reads = sendMock.mock.calls.filter(
      (call) => (call[0] as RequestDescriptor).url.endsWith('/read'),
    );
    expect(reads).toHaveLength(1);
    expect((reads[0]![0] as RequestDescriptor).headers['If-Match']).toBe('3');
  });

  it('calls read once even when React replays effects in StrictMode', async () => {
    const view = syntheticView();
    sendMock.mockImplementation(((descriptor: RequestDescriptor) =>
      Promise.resolve(
        descriptor.url.endsWith('/read')
          ? { ...view.conversation, version: view.conversation.version + 1 }
          : view,
      )) as typeof send);
    render(<StrictMode>{element(<ConversationPage />)}</StrictMode>);
    await screen.findByText('Synthetic patient question');
    const reads = sendMock.mock.calls.filter(
      (call) => (call[0] as RequestDescriptor).url.endsWith('/read'),
    );
    expect(reads).toHaveLength(1);
  });

  it('falls back to an empty id when rendered outside a router', async () => {
    const view = syntheticView({ conversation: syntheticConversation({ id: '' }) });
    sendMock.mockResolvedValueOnce(view);
    sendMock.mockResolvedValueOnce({ ...view.conversation, version: 4 });
    render(
      <ThemeProvider>
        <I18nProvider>
          <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
            <ConversationPage />
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(await screen.findByText('Synthetic patient question')).toBeTruthy();
    expect(descriptorAt(0).url).toBe('/api/v1/conversations/');
  });

  it('reports a failed read without hiding the loaded thread', async () => {
    sendMock.mockResolvedValueOnce(syntheticView());
    sendMock.mockRejectedValueOnce(failure('conflict'));
    wrap();
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByText('Synthetic patient question')).toBeTruthy();
  });

  it('loads a different conversation when the route id changes', async () => {
    queueReady();
    const first = wrap();
    await screen.findByText('Synthetic patient question');
    first.unmount();

    const otherId = '44444444-4444-4444-8444-444444444444';
    queueReady();
    wrap(otherId, betaOrganizationId);
    await screen.findByText('Synthetic patient question');
    expect(descriptorAt(2).url).toBe(`/api/v1/conversations/${otherId}`);
    expect(descriptorAt(2).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });
});

describe('ConversationPage reply', () => {
  it('posts a reply, clears the draft and announces success', async () => {
    queueReady();
    wrap();
    await screen.findByText('Synthetic patient question');
    sendMock.mockResolvedValueOnce(syntheticView({ conversation: syntheticConversation({ version: 5 }) }));

    fireEvent.change(screen.getByLabelText('Reply text'), {
      target: { value: 'Synthetic reply text' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Reply' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(2);
    expect(command.url).toBe(`/api/v1/conversations/${conversationId}/reply`);
    expect(command.body).toEqual({ body: 'Synthetic reply text' });
    expect(command.headers['If-Match']).toBe('4');
    expect(await screen.findByText('Reply sent.')).toBeTruthy();
    expect((screen.getByLabelText('Reply text') as HTMLTextAreaElement).value).toBe('');
  });

  it('requires reply text before sending', async () => {
    queueReady();
    wrap();
    await screen.findByText('Synthetic patient question');
    fireEvent.click(screen.getByRole('button', { name: 'Reply' }));
    expect(await screen.findByText('Enter reply text before sending.')).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('keeps the typed draft and shows conflict on a stale version', async () => {
    queueReady();
    wrap();
    await screen.findByText('Synthetic patient question');
    sendMock.mockRejectedValueOnce(failure('conflict'));

    fireEvent.change(screen.getByLabelText('Reply text'), {
      target: { value: 'Synthetic draft that must survive' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Reply' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect((screen.getByLabelText('Reply text') as HTMLTextAreaElement).value).toBe(
      'Synthetic draft that must survive',
    );
  });

  it('never writes the draft to browser storage and submits as a native form', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    queueReady();
    wrap();
    await screen.findByText('Synthetic patient question');
    fireEvent.change(screen.getByLabelText('Reply text'), {
      target: { value: 'Synthetic in-memory only' },
    });
    expect(setItem).not.toHaveBeenCalled();

    sendMock.mockResolvedValueOnce(syntheticView());
    const form = screen.getByRole('form', { name: 'Reply' });
    fireEvent.submit(form);
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    setItem.mockRestore();
  });
});

describe('ConversationPage work-item commands', () => {
  beforeEach(() => {
    queueReady();
  });

  async function openAndFill(action: string, subject?: string) {
    await screen.findByText('Synthetic patient question');
    fireEvent.click(screen.getByRole('button', { name: action }));
    if (subject !== undefined) {
      fireEvent.change(screen.getByLabelText(subject), { target: { value: 'staff-target' } });
    }
    fireEvent.change(screen.getByLabelText('Reason'), {
      target: { value: 'Synthetic reason' },
    });
  }

  it('cancels a confirmation without sending a command', async () => {
    wrap();
    await screen.findByText('Synthetic patient question');
    fireEvent.click(screen.getByRole('button', { name: 'Assign' }));
    expect(
      await screen.findByRole('group', { name: 'Assign this work item' }),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('group', { name: 'Assign this work item' })).toBeNull();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('starts confirmation on the safe Cancel choice with focusable fields', async () => {
    wrap();
    await screen.findByText('Synthetic patient question');
    fireEvent.click(screen.getByRole('button', { name: 'Assign' }));

    const cancel = await screen.findByRole('button', { name: 'Cancel' });
    expect(document.activeElement).toBe(cancel);
    expect(screen.getByLabelText('Assignee subject').tagName).toBe('INPUT');
    expect(screen.getByLabelText('Reason').tagName).toBe('INPUT');
    expect(screen.getByRole('button', { name: 'Confirm assignment' }).tagName).toBe('BUTTON');
  });

  it('requires an assignee subject before assignment', async () => {
    wrap();
    await openAndFill('Assign');
    fireEvent.click(screen.getByRole('button', { name: 'Confirm assignment' }));
    expect(await screen.findByText('Enter a staff subject before continuing.')).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('requires a reason before assignment', async () => {
    wrap();
    await screen.findByText('Synthetic patient question');
    fireEvent.click(screen.getByRole('button', { name: 'Assign' }));
    fireEvent.change(screen.getByLabelText('Assignee subject'), {
      target: { value: 'staff-target' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm assignment' }));
    expect(await screen.findByText('Enter a reason before continuing.')).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('assigns with subject, reason and version after confirmation', async () => {
    wrap();
    await openAndFill('Assign', 'Assignee subject');
    sendMock.mockResolvedValueOnce(syntheticConversation({ version: 6 }));

    fireEvent.click(screen.getByRole('button', { name: 'Confirm assignment' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(2);
    expect(command.url).toBe(`/api/v1/conversations/${conversationId}/assign`);
    expect(command.body).toEqual({ assigneeSubject: 'staff-target', reason: 'Synthetic reason' });
    expect(command.headers['If-Match']).toBe('4');
    expect(await screen.findByText('Work item assigned.')).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('transfers with a target subject after confirmation', async () => {
    wrap();
    await openAndFill('Transfer', 'Target subject');
    sendMock.mockResolvedValueOnce(syntheticConversation({ version: 6 }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm transfer' }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(2).url).toBe(`/api/v1/conversations/${conversationId}/transfer`);
    expect(descriptorAt(2).body).toEqual({ toSubject: 'staff-target', reason: 'Synthetic reason' });
    expect(await screen.findByText('Work item transferred.')).toBeTruthy();
  });

  it('resolves with a reason after confirmation', async () => {
    wrap();
    await openAndFill('Resolve');
    sendMock.mockResolvedValueOnce(syntheticConversation({ version: 6, state: 'resolved' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm resolve' }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(2).url).toBe(`/api/v1/conversations/${conversationId}/resolve`);
    expect(descriptorAt(2).body).toEqual({ reason: 'Synthetic reason' });
    expect(await screen.findByText('Work item resolved.')).toBeTruthy();
  });

  it('closes with a reason after confirmation', async () => {
    wrap();
    await openAndFill('Close');
    sendMock.mockResolvedValueOnce(syntheticConversation({ version: 6, state: 'closed' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm close' }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    expect(descriptorAt(2).url).toBe(`/api/v1/conversations/${conversationId}/close`);
    expect(descriptorAt(2).body).toEqual({ reason: 'Synthetic reason' });
    expect(await screen.findByText('Conversation closed. It is now read-only.')).toBeTruthy();
  });

  it('keeps the dialog and typed values on a command conflict', async () => {
    wrap();
    await openAndFill('Assign', 'Assignee subject');
    sendMock.mockRejectedValueOnce(failure('conflict'));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm assignment' }));
    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByRole('group', { name: 'Assign this work item' })).toBeTruthy();
    expect((screen.getByLabelText('Assignee subject') as HTMLInputElement).value).toBe('staff-target');
    expect((screen.getByLabelText('Reason') as HTMLInputElement).value).toBe('Synthetic reason');
  });
});

describe('conversation routes', () => {
  it('registers the detail path before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/messages/:id');
    expect(patterns.indexOf('/messages/:id')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the registered conversation page with live data', async () => {
    queueReady();
    const route = createRoutes(false).find((candidate) => candidate.pattern === '/messages/:id')!;
    const Page = route.component;
    render(<Suspense fallback={<p>Loading route</p>}>{element(<Page />)}</Suspense>);
    expect(await screen.findByText('Synthetic patient question')).toBeTruthy();
  });
});

import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
import {
  alphaOrganizationId,
  betaOrganizationId,
  conversationId,
  patientId,
  syntheticConversation,
  syntheticInbox,
  syntheticInboxEntry,
  syntheticWorkQueue,
} from './fixtures';
import { MessagesPage } from './MessagesPage';
import { markConversationRead, resetReadReceipts } from './unreadStore';

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
  organizationId = alphaOrganizationId,
  locale: Locale = 'en',
) {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, organizationId = alphaOrganizationId, locale: Locale = 'en') {
  return render(element(ui, organizationId, locale));
}

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

beforeEach(() => {
  sendMock.mockReset();
  sendMock.mockResolvedValue(syntheticInbox());
  resetReadReceipts();
});

describe('MessagesPage states', () => {
  it('shows the loading state until both projections resolve', async () => {
    const pending = deferred<ReturnType<typeof syntheticInbox>>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap(<MessagesPage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(syntheticInbox([syntheticInboxEntry()]));
    await screen.findByText('Synthetic follow-up');
  });

  it('shows the empty state when the inbox has no rows', async () => {
    sendMock.mockResolvedValueOnce(syntheticInbox([])).mockResolvedValueOnce(syntheticWorkQueue([]));
    wrap(<MessagesPage />);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(failure(kind));
      wrap(<MessagesPage />);
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state for a role without messaging access', async () => {
    sendMock.mockRejectedValueOnce(failure('forbidden'));
    wrap(<MessagesPage />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'forbiddenDetail'))).toBeTruthy();
  });

  it('shows the session-expired state', async () => {
    sendMock.mockRejectedValueOnce(failure('session-expired'));
    wrap(<MessagesPage />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });
});

describe('MessagesPage inbox', () => {
  beforeEach(() => {
    sendMock
      .mockResolvedValueOnce(syntheticInbox([syntheticInboxEntry(2)]))
      .mockResolvedValueOnce(syntheticWorkQueue([]));
  });

  it('shows each conversation with its unread count, unit, state and breach marker', async () => {
    wrap(<MessagesPage />);
    const openLink = await screen.findByRole('link', { name: new RegExp(patientId) });
    expect(openLink.textContent).toContain('Jan 1, 2026');
    expect(screen.getByText('Synthetic follow-up')).toBeTruthy();
    expect(screen.getByTestId(`unread-${conversationId}`).textContent).toBe('2 unread');
    expect(screen.getByText('Clinic')).toBeTruthy();
    expect(screen.getByText('Awaiting staff')).toBeTruthy();
    const breach = screen.getByText('SLA breached');
    expect(breach.getAttribute('data-state')).toBe('breached');
    expect(breach.getAttribute('style')).toContain('color');
    expect(screen.getByRole('tab', { name: 'Inbox' }).getAttribute('aria-selected')).toBe('true');
    expect(document.title).not.toContain(patientId);
    expect(screen.getByRole('heading', { name: 'Messages' })).toBeTruthy();
  });

  it('marks a fresh conversation as within the response target', async () => {
    const fresh = syntheticConversation({
      id: '22222222-2222-4222-8222-222222222222',
      createdAt: '2999-01-01T00:00:00.000Z',
      topic: null,
    });
    sendMock.mockReset();
    sendMock
      .mockResolvedValueOnce(syntheticInbox([syntheticInboxEntry(0, fresh)]))
      .mockResolvedValueOnce(syntheticWorkQueue([]));

    wrap(<MessagesPage />);
    const within = await screen.findByText('Within response target');
    expect(within.getAttribute('data-state')).toBe('within');
    expect(screen.getByText('Conversation')).toBeTruthy();
  });

  it('updates the unread count after a read on the conversation screen', async () => {
    wrap(<MessagesPage />);
    await screen.findByText('2 unread');
    act(() => markConversationRead(alphaOrganizationId, conversationId, 0));
    expect(screen.getByTestId(`unread-${conversationId}`).textContent).toBe('0 unread');
  });

  it('renders the table in Arabic with right-to-left direction', async () => {
    wrap(<MessagesPage />, alphaOrganizationId, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByRole('table', { name: 'الوارد' })).toBeTruthy();
  });

  it('switches tabs without a second request and shows work-queue unread counts', async () => {
    const matching = syntheticConversation();
    const extra = syntheticConversation({
      id: '33333333-3333-4333-8333-333333333333',
      patientId: 'SYN-PAT-0002',
      state: 'awaiting_patient',
    });
    sendMock.mockReset();
    sendMock
      .mockResolvedValueOnce(syntheticInbox([syntheticInboxEntry(3, matching)]))
      .mockResolvedValueOnce(syntheticWorkQueue([matching, extra]));

    wrap(<MessagesPage />);
    await screen.findByText('3 unread');

    const workQueueTab = screen.getByRole('tab', { name: 'Work queue' });
    fireEvent.click(workQueueTab);

    expect(await screen.findByRole('table', { name: 'Work queue' })).toBeTruthy();
    expect(workQueueTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.getByTestId(`unread-${extra.id}`).textContent).toBe('0 unread');
    expect(screen.getByTestId(`unread-${matching.id}`).textContent).toBe('3 unread');
    expect(sendMock).toHaveBeenCalledTimes(2);

    const inboxTab = screen.getByRole('tab', { name: 'Inbox' });
    fireEvent.click(inboxTab);
    expect(inboxTab.getAttribute('aria-selected')).toBe('true');
    expect(await screen.findByRole('table', { name: 'Inbox' })).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('clears the previous tenant rows before the new tenant responds', async () => {
    const first = wrap(<MessagesPage />);
    await screen.findByText(patientId);
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    first.unmount();
    sendMock.mockResolvedValue(
      syntheticInbox([syntheticInboxEntry(1, syntheticConversation({ patientId: 'SYN-PAT-BETA' }))]),
    );
    render(element(<MessagesPage />, betaOrganizationId));
    expect(screen.queryByText(patientId)).toBeNull();
    await waitFor(() => expect(screen.getAllByText('SYN-PAT-BETA').length).toBeGreaterThan(0));
    expect(descriptorAt(2).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });
});

describe('messages routes', () => {
  it('registers both paths before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/messages');
    expect(patterns).toContain('/messages/:id');
    expect(patterns.indexOf('/messages')).toBeLessThan(patterns.indexOf('*'));
    expect(patterns.indexOf('/messages/:id')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the registered messages page with live data', async () => {
    sendMock.mockResolvedValue(syntheticInbox());
    const route = createRoutes(false).find((candidate) => candidate.pattern === '/messages')!;
    const Page = route.component;

    render(<Suspense fallback={<p>Loading route</p>}>{element(<Page />)}</Suspense>);
    expect(await screen.findByRole('link', { name: /Open conversation/ })).toBeTruthy();
    expect(screen.getByText('Synthetic follow-up')).toBeTruthy();
  });
});

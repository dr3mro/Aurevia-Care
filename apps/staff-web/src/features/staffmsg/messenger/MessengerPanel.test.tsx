import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { TransportError } from '../../../api/transport';
import type { TransportErrorKind } from '../../../api/transport';
import { stateViewText } from '../../../components/stateViewMessages';
import type { StateViewMessageKey } from '../../../components/stateViewMessages';
import { defined } from '../../../components/invariant';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import {
  alphaOrganizationId,
  arabicMessage,
  contentDraft,
  emptyDraft,
  failedSend,
  facilityId,
  messengerView,
  secondAttachment,
  selfSubject,
  stagedAttachment,
} from './fixtures';
import { messengerText } from './labels';
import { MessengerPanel } from './MessengerPanel';
import type { MessengerPanelProps } from './MessengerPanel';
import type { StreamHandlers } from './realtime';
import type { RealtimePage } from './types';

/** A controllable stream so connection states are driven, never timed. */
function fakeRealtime() {
  let handlers: StreamHandlers | undefined;
  const cleanup = { cancel: vi.fn() };
  const openStream = vi.fn((next: StreamHandlers) => {
    handlers = next;
    return cleanup;
  });
  const poll = vi.fn((): Promise<RealtimePage> => Promise.resolve({ events: [], nextCursor: undefined }));
  const schedule = vi.fn(() => ({ cancel: vi.fn() }));
  return {
    openStream,
    poll,
    schedule,
    cleanup,
    fireOpen: () => handlers?.onOpen(),
    fireError: () => handlers?.onError(),
  };
}

/** Render the panel under the shared providers at a chosen locale. */
function wrap(overrides: Partial<MessengerPanelProps> = {}, locale: 'en' | 'ar' = 'en') {
  const realtime = fakeRealtime();
  const props: MessengerPanelProps = {
    view: messengerView,
    organizationId: alphaOrganizationId,
    facilityId,
    selfSubject,
    draft: emptyDraft,
    stagedFiles: [stagedAttachment, secondAttachment],
    loadState: 'ready',
    error: undefined,
    sendFailure: undefined,
    openStream: realtime.openStream,
    poll: realtime.poll,
    schedule: realtime.schedule,
    onRefresh: vi.fn(),
    onSelectConversation: vi.fn(),
    onDraftChange: vi.fn(),
    onStageFile: vi.fn(),
    onRemoveFile: vi.fn(),
    onSend: vi.fn(),
    onRetry: vi.fn(),
    onReload: vi.fn(),
    onSignIn: vi.fn(),
    onCompare: vi.fn(),
    onCopy: vi.fn(),
    ...overrides,
  };
  const view = render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <MessengerPanel {...props} />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...view, props, realtime };
}

describe('MessengerPanel structure', () => {
  it('shows the emergency banner and synthetic notice', () => {
    wrap();
    expect(screen.getByTestId('messenger-emergency-banner').textContent).toContain('not for emergencies');
    expect(screen.getByText(messengerText('en', 'syntheticNotice'))).toBeTruthy();
  });

  it('shows unread counts only for conversations that have unread messages', () => {
    wrap();
    expect(screen.getByText(/3 unread/)).toBeTruthy();
    expect(screen.queryByText(/0 unread/)).toBeNull();
    const first = screen.getByRole('button', { name: /Synthetic triage peer/ });
    expect(first.getAttribute('aria-current')).toBe('true');
  });

  it('selects another conversation from the list', () => {
    const { props } = wrap();
    fireEvent.click(screen.getByRole('button', { name: /Synthetic triage peer/ }));
    expect(props.onSelectConversation).toHaveBeenCalledWith(messengerView.conversations[0]?.id);
  });

  it('renders the empty conversation list state', () => {
    wrap({ view: { ...messengerView, conversations: [] } });
    expect(screen.getByText(stateViewText('en', 'empty'))).toBeTruthy();
  });

  it('renders an empty thread when no messages are present', () => {
    wrap({ view: { ...messengerView, messages: [] } });
    expect(screen.getByText(messengerText('en', 'emptyThread'))).toBeTruthy();
  });

  it('renders date separators and delivery states for thread bubbles', () => {
    wrap();
    expect(screen.getByText('March 9, 2026')).toBeTruthy();
    expect(screen.getByText('March 10, 2026')).toBeTruthy();
    expect(screen.getByText('Synthetic incoming body')).toBeTruthy();
    expect(screen.getByText(messengerText('en', 'incomingLabel'))).toBeTruthy();
    expect(screen.getByText('Synthetic outgoing body')).toBeTruthy();
    expect(screen.getByText(messengerText('en', 'deliveryDelivered'))).toBeTruthy();
  });
});

describe('MessengerPanel composer and send', () => {
  it('lists pending files and sends only on an explicit Send action', () => {
    const { props } = wrap({ draft: contentDraft });
    expect(screen.getByTestId('messenger-pending-files').textContent).toContain(stagedAttachment.name);
    fireEvent.change(screen.getByLabelText(messengerText('en', 'stageFileLabel')), {
      target: { value: secondAttachment.id },
    });
    fireEvent.click(screen.getByRole('button', { name: messengerText('en', 'attachLabel') }));
    expect(props.onStageFile).toHaveBeenCalledWith(secondAttachment);
    expect(props.onSend).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: messengerText('en', 'send') }));
    expect(props.onSend).toHaveBeenCalledTimes(1);
    expect(props.onSend).toHaveBeenCalledWith(contentDraft.text, contentDraft.attachments);
  });

  it('removes a pending file without sending it', () => {
    const { props } = wrap({ draft: contentDraft });
    fireEvent.click(
      screen.getByRole('button', { name: `${messengerText('en', 'removeAttachment')}: ${stagedAttachment.name}` }),
    );
    expect(props.onRemoveFile).toHaveBeenCalledWith(stagedAttachment.id);
    expect(props.onSend).not.toHaveBeenCalled();
  });

  it('shows an empty tray when no file is staged', () => {
    wrap();
    expect(screen.getByText(messengerText('en', 'emptyTray'))).toBeTruthy();
  });

  it('does not send blank text even when a file is pending', () => {
    const { props } = wrap({ draft: { text: '   ', attachments: [stagedAttachment] } });
    const send = screen.getByRole('button', { name: messengerText('en', 'send') });
    expect(send.hasAttribute('disabled')).toBe(true);
    fireEvent.click(send);
    expect(props.onSend).not.toHaveBeenCalled();
  });

  it('keeps the typed text on a failed send and retries with the same key', () => {
    const failedMessage = {
      ...defined(messengerView.messages[0], 'first thread message'),
      id: 'synthetic-message-failed',
      outgoing: true,
      delivery: 'failed' as const,
    };
    const { props } = wrap({
      sendFailure: failedSend,
      view: { ...messengerView, messages: [...messengerView.messages, failedMessage] },
    });
    const textarea = screen.getByLabelText<HTMLTextAreaElement>(messengerText('en', 'bodyLabel'));
    expect(textarea.value).toBe(failedSend.text);
    expect(screen.getByTestId('messenger-send-failure').textContent).toContain('not sent');
    expect(screen.getByText(messengerText('en', 'deliveryFailed'))).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: messengerText('en', 'retry') }));
    expect(props.onRetry).toHaveBeenCalledWith(failedSend.idempotencyKey);
    expect(props.onSend).not.toHaveBeenCalled();
  });

  it('lets the author correct the retained text and files after a failed send', () => {
    const { props } = wrap({ draft: contentDraft, sendFailure: failedSend });
    const textarea = screen.getByLabelText<HTMLTextAreaElement>(messengerText('en', 'bodyLabel'));
    expect(textarea.value).toBe(failedSend.text);
    fireEvent.change(textarea, { target: { value: 'Synthetic corrected reply' } });
    expect(props.onDraftChange).toHaveBeenCalledWith({ text: 'Synthetic corrected reply' });
    expect(textarea.value).toBe(contentDraft.text);
    fireEvent.change(screen.getByLabelText(messengerText('en', 'stageFileLabel')), {
      target: { value: secondAttachment.id },
    });
    fireEvent.click(screen.getByRole('button', { name: messengerText('en', 'attachLabel') }));
    expect(props.onStageFile).toHaveBeenCalledWith(secondAttachment);
    fireEvent.click(
      screen.getByRole('button', { name: `${messengerText('en', 'removeAttachment')}: ${stagedAttachment.name}` }),
    );
    expect(props.onRemoveFile).toHaveBeenCalledWith(stagedAttachment.id);
    fireEvent.click(screen.getByRole('button', { name: messengerText('en', 'send') }));
    expect(props.onSend).toHaveBeenCalledWith(contentDraft.text, contentDraft.attachments);
  });

  it('shows a later failure again after the author has edited an earlier one', () => {
    const laterFailure = {
      ...failedSend,
      text: 'Synthetic retained from the second failure',
      idempotencyKey: 'synthetic-idempotency-002',
    };
    const { props, rerender } = wrap({ draft: contentDraft, sendFailure: failedSend });
    const textarea = screen.getByLabelText<HTMLTextAreaElement>(messengerText('en', 'bodyLabel'));
    expect(textarea.value).toBe(failedSend.text);
    fireEvent.change(textarea, { target: { value: 'Synthetic corrected reply' } });
    expect(textarea.value).toBe(contentDraft.text);
    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <MessengerPanel {...props} sendFailure={laterFailure} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(textarea.value).toBe(laterFailure.text);
  });

  it('reports composer edits through the change callback', () => {
    const { props } = wrap({ draft: contentDraft });
    fireEvent.change(screen.getByLabelText<HTMLTextAreaElement>(messengerText('en', 'bodyLabel')), {
      target: { value: 'Synthetic edited text' },
    });
    expect(props.onDraftChange).toHaveBeenCalledWith({ text: 'Synthetic edited text' });
  });

  it('does not stage a file when none has been chosen', () => {
    const { props } = wrap();
    fireEvent.click(screen.getByRole('button', { name: messengerText('en', 'attachLabel') }));
    expect(props.onStageFile).not.toHaveBeenCalled();
  });

  it('keeps the primary Send action in the tab order and activates it', () => {
    const { props } = wrap({ draft: contentDraft });
    const send = screen.getByRole('button', { name: messengerText('en', 'send') });
    send.focus();
    expect(document.activeElement).toBe(send);
    fireEvent.click(send);
    expect(props.onSend).toHaveBeenCalledTimes(1);
  });
});

describe('MessengerPanel connection and resilient states', () => {
  it('shows a quiet connected state when the stream opens', async () => {
    const { realtime } = wrap();
    realtime.fireOpen();
    await waitFor(() => expect(document.querySelector('[data-connection="streaming"]')).not.toBeNull());
    expect(document.querySelector('[data-connection="streaming"]')?.getAttribute('role')).toBe('status');
  });

  it('shows a quiet reconnecting state after the stream is lost', async () => {
    const { realtime } = wrap();
    realtime.fireOpen();
    await waitFor(() => expect(document.querySelector('[data-connection="streaming"]')).not.toBeNull());
    // Hold the poll open so the quiet reconnecting state is observable.
    realtime.poll.mockImplementationOnce(() => new Promise(() => undefined));
    realtime.fireError();
    await waitFor(() => expect(document.querySelector('[data-connection="reconnecting"]')).not.toBeNull());
  });

  it('refetches thread data and schedules the next poll when an identifier event arrives', async () => {
    const { props, realtime } = wrap();
    realtime.poll.mockImplementationOnce(() =>
      Promise.resolve({
        events: [{ id: 'synthetic-event', sequence: 2, type: 'message' as const, conversationId: 'c', subject: 's' }],
        nextCursor: 2,
      }),
    );
    realtime.fireError();
    await waitFor(() => expect(props.onRefresh).toHaveBeenCalledTimes(1));
    expect(realtime.schedule).toHaveBeenCalled();
  });

  it('stops the old stream and clears prior rows when the tenant changes', async () => {
    const { realtime, rerender } = wrap();
    realtime.fireOpen();
    await waitFor(() => expect(document.querySelector('[data-connection="streaming"]')).not.toBeNull());
    const nextView = {
      ...messengerView,
      conversations: [
        { ...defined(messengerView.conversations[0], 'first conversation'), id: 'beta-conversation', topic: 'Synthetic beta peer' },
      ],
    };
    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <MessengerPanel
            {...({
              view: nextView,
              organizationId: 'org-synthetic-beta',
              facilityId,
              selfSubject,
              draft: emptyDraft,
              stagedFiles: [],
              loadState: 'ready',
              error: undefined,
              sendFailure: undefined,
              openStream: realtime.openStream,
              poll: realtime.poll,
              schedule: realtime.schedule,
              onRefresh: vi.fn(),
              onSelectConversation: vi.fn(),
              onDraftChange: vi.fn(),
              onStageFile: vi.fn(),
              onRemoveFile: vi.fn(),
              onSend: vi.fn(),
              onRetry: vi.fn(),
              onReload: vi.fn(),
              onSignIn: vi.fn(),
              onCompare: vi.fn(),
              onCopy: vi.fn(),
            } satisfies MessengerPanelProps)}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(realtime.cleanup.cancel).toHaveBeenCalled();
    expect(screen.queryByText(/Synthetic triage peer/)).toBeNull();
    expect(screen.getByText(/Synthetic beta peer/)).toBeTruthy();
    expect(screen.getByText(messengerText('en', 'noConversation'))).toBeTruthy();
    expect(document.querySelector('[data-connection="reconnecting"]')).not.toBeNull();
  });

  it('renders the loading state with the emergency notice still visible', () => {
    wrap({ loadState: 'loading' });
    expect(screen.getByText(stateViewText('en', 'loading'))).toBeTruthy();
    expect(screen.getByTestId('messenger-emergency-banner')).toBeTruthy();
  });

  it('renders a generic error state when no typed error is supplied', () => {
    wrap({ loadState: 'error' });
    expect(screen.getByText(stateViewText('en', 'error'))).toBeTruthy();
  });

  it('maps every transport failure kind to its named state with a correlation id', () => {
    const cases: readonly (readonly [TransportErrorKind, StateViewMessageKey])[] = [
      ['forbidden', 'forbidden'],
      ['conflict', 'conflict'],
      ['session-expired', 'sessionExpired'],
      ['offline', 'error'],
      ['timeout', 'error'],
      ['server', 'error'],
      ['csrf-missing', 'error'],
      ['not-found', 'error'],
      ['invalid', 'error'],
      ['rate-limited', 'error'],
      ['precondition-required', 'conflict'],
    ];
    for (const [kind, key] of cases) {
      const { unmount } = wrap({
        loadState: 'error',
        error: new TransportError(kind, {
          correlationId: 'synthetic-correlation',
          code: undefined,
          retryAfterSeconds: undefined,
        }),
      });
      expect(screen.getByText(stateViewText('en', key))).toBeTruthy();
      if (key === 'error') {
        expect(screen.getByText('synthetic-correlation')).toBeTruthy();
      }
      unmount();
    }
  });
});

describe('MessengerPanel localization', () => {
  it('renders Arabic labels, document RTL and isolated right-to-left bubbles', () => {
    wrap(
      { view: { ...messengerView, messages: [arabicMessage] } },
      'ar',
    );
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByTestId('messenger-emergency-banner').textContent).toContain('الطوارئ');
    const bubble = document.querySelector('[data-bubble]');
    expect(bubble?.getAttribute('dir')).toBe('auto');
    expect(bubble?.getAttribute('lang')).toBe('ar');
    expect(within(screen.getByRole('region', { name: messengerText('ar', 'conversationsLabel') })).getByText(/غير مقروءة/)).toBeTruthy();
  });
});

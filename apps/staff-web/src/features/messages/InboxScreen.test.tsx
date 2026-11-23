import { useState } from 'react';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { applyInboundMessage } from './domain';
import { alphaInboxView } from './fixtures';
import { inboxText } from './labels';
import { InboxScreen } from './InboxScreen';
import type { InboundMessage, InboxView, SendDraft, WorkDraft } from './types';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Own the drafts exactly as the page does, so every callback is observable. */
function Harness({
  view = alphaInboxView,
  onSend = vi.fn(),
  onWorkSubmit = vi.fn(),
  onDiscardDraft = vi.fn(),
}: {
  view?: InboxView;
  onSend?: (draft: SendDraft) => void;
  onWorkSubmit?: (draft: WorkDraft) => void;
  onDiscardDraft?: () => void;
}) {
  const [replyText, setReplyText] = useState('');
  const [workDraft, setWorkDraft] = useState<WorkDraft | undefined>(undefined);
  return (
    <InboxScreen
      view={view}
      replyText={replyText}
      workDraft={workDraft}
      reasonError={undefined}
      onReplyTextChange={setReplyText}
      onSend={onSend}
      onWorkDraftChange={setWorkDraft}
      onWorkSubmit={onWorkSubmit}
      onDiscardDraft={onDiscardDraft}
    />
  );
}

function composer(): HTMLElement {
  return screen.getByRole('textbox', { name: /Reply to .* from Front desk/ });
}

describe('InboxScreen layout and conversation', () => {
  it('shows the unit strip, four thread states and the selected conversation', () => {
    wrap(<Harness />);
    expect(screen.getByRole('heading', { name: /Unit inbox: Front desk/ })).toBeTruthy();
    expect(screen.getByText('Mon to Sat 8:00 to 17:00')).toBeTruthy();
    expect(screen.getByText('1 business day')).toBeTruthy();
    expect(screen.getByText('Lina Haddad')).toBeTruthy();
    expect(screen.getByText('Maya Chen')).toBeTruthy();
    expect(screen.getByText(inboxText('en', 'stateNew'))).toBeTruthy();
    expect(screen.getByText(inboxText('en', 'stateReplied'))).toBeTruthy();
    expect(screen.getByText(inboxText('en', 'stateWaiting'))).toBeTruthy();
    expect(screen.getByText(inboxText('en', 'stateResolved'))).toBeTruthy();
    expect(screen.getByText(inboxText('en', 'connectionVerified'))).toBeTruthy();
    expect(composer()).toBeTruthy();
    expect(screen.getByText(/Synthetic Grace Okafor · patient/)).toBeTruthy();
  });

  it('keeps an unscanned attachment closed and names its scan state', () => {
    wrap(<Harness />);
    const unscanned = screen.getByText('photo.jpeg').closest('[data-scan-state]');
    expect(unscanned?.getAttribute('data-scan-state')).toBe('unscanned');
    expect(within(unscanned as HTMLElement).getByText(inboxText('en', 'scanUnscanned'))).toBeTruthy();
    expect(within(unscanned as HTMLElement).queryByRole('button')).toBeNull();

    const clean = screen.getByText('visit-summary.pdf').closest('[data-scan-state]');
    expect(clean?.getAttribute('data-scan-state')).toBe('clean');
    expect(clean?.tagName).toBe('BUTTON');
    expect(within(clean as HTMLElement).getByText(inboxText('en', 'scanClean'))).toBeTruthy();
  });

});

describe('InboxScreen composer and work commands', () => {
  it('keeps a failed-scan attachment closed and names its scan state', () => {
    const inbound: InboundMessage = {
      threadId: 'thread-011',
      patientName: { latinName: 'Synthetic Failed Scan' },
      patientId: 'synthetic-patient-011',
      topic: 'Replacement file',
      message: {
        id: 'message-011',
        senderRole: 'patient',
        senderName: 'Synthetic Failed Scan',
        body: 'I tried to attach a replacement.',
        sentAt: '2026-03-09T17:10:00Z',
        timeZone: 'America/New_York',
        attachments: [
          { id: 'attachment-011', fileName: 'scan-failed.png', sizeLabel: '64 KB', scanState: 'failed' },
        ],
      },
    };
    wrap(<Harness view={applyInboundMessage(alphaInboxView, inbound)} />);
    const chip = screen.getByText('scan-failed.png').closest('[data-scan-state]');
    expect(chip?.getAttribute('data-scan-state')).toBe('failed');
    expect(within(chip as HTMLElement).getByText(inboxText('en', 'scanFailed'))).toBeTruthy();
    expect(within(chip as HTMLElement).queryByRole('button')).toBeNull();
  });

});

describe('InboxScreen send and work commands', () => {
  it('names the recipient and pending files on Send and keeps a blank composer inert', () => {
    const onSend = vi.fn();
    wrap(<Harness onSend={onSend} />);
    fireEvent.change(composer(), { target: { value: 'Thank you' } });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));
    expect(onSend).toHaveBeenCalledWith({
      threadId: 'thread-001',
      version: 3,
      recipientName: 'Synthetic Grace Okafor',
      recipientId: 'synthetic-patient-001',
      draft: 'Thank you',
      pendingFiles: ['prep-instructions.pdf'],
    });
    expect(screen.getByText(/prep-instructions.pdf/)).toBeTruthy();

    fireEvent.change(composer(), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'send') }));
    expect(onSend).toHaveBeenCalledTimes(2);
    expect(onSend).toHaveBeenLastCalledWith(
      expect.objectContaining({ draft: '', recipientName: 'Synthetic Grace Okafor' }),
    );
  });

  it('emits a reason draft for Assign and Resolve', () => {
    const onWorkSubmit = vi.fn();
    wrap(<Harness onWorkSubmit={onWorkSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'assign') }));
    let dialog = within(screen.getByRole('dialog'));
    fireEvent.change(dialog.getByLabelText(inboxText('en', 'reasonLabel')), {
      target: { value: 'handed to backup' },
    });
    fireEvent.click(dialog.getByRole('button', { name: inboxText('en', 'recordAction') }));
    expect(onWorkSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ threadId: 'thread-001', action: 'assign', reason: 'handed to backup' }),
    );

    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'resolve') }));
    dialog = within(screen.getByRole('dialog'));
    fireEvent.change(dialog.getByLabelText(inboxText('en', 'reasonLabel')), {
      target: { value: 'answered' },
    });
    fireEvent.click(dialog.getByRole('button', { name: inboxText('en', 'recordAction') }));
    expect(onWorkSubmit).toHaveBeenLastCalledWith(
      expect.objectContaining({ threadId: 'thread-001', action: 'resolve', reason: 'answered' }),
    );
  });

  it('adds an inbound message as a new work item in the thread list', () => {
    const inbound: InboundMessage = {
      threadId: 'thread-010',
      patientName: { latinName: 'Synthetic Nour', localName: 'نور تجريبية' },
      patientId: 'synthetic-patient-010',
      topic: 'Saturday hours',
      message: {
        id: 'message-010',
        senderRole: 'patient',
        senderName: 'Synthetic Nour',
        body: 'Are you open on Saturday?',
        sentAt: '2026-03-09T17:00:00Z',
        timeZone: 'America/New_York',
        attachments: [],
      },
    };
    wrap(<Harness view={applyInboundMessage(alphaInboxView, inbound)} />);
    expect(screen.getAllByText('Synthetic Nour').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Saturday hours').length).toBeGreaterThan(0);
    expect(screen.getAllByText(inboxText('en', 'stateNew'))).toHaveLength(2);
  });

  it('asks before leaving a thread with an unsaved reply', () => {
    const onDiscardDraft = vi.fn();
    wrap(<Harness onDiscardDraft={onDiscardDraft} />);
    fireEvent.change(composer(), { target: { value: 'unsaved reply' } });
    // Selecting the already-open thread is a no-op, so no discard prompt opens.
    fireEvent.click(
      screen.getByRole('button', { name: /Synthetic Grace Okafor: Appointment change/ }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.click(screen.getByText('Refill question'));
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByText(inboxText('en', 'discardTitle'))).toBeTruthy();
    fireEvent.click(dialog.getByRole('button', { name: inboxText('en', 'stay') }));
    expect(onDiscardDraft).not.toHaveBeenCalled();
    expect(composer()).toBeTruthy();

    fireEvent.click(screen.getByText('Refill question'));
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: inboxText('en', 'leave') }),
    );
    expect(onDiscardDraft).toHaveBeenCalledTimes(1);
  });

  it('switches to another thread when the composer is empty', () => {
    const onDiscardDraft = vi.fn();
    wrap(<Harness onDiscardDraft={onDiscardDraft} />);
    fireEvent.click(screen.getByText('Refill question'));
    expect(screen.getByText(inboxText('en', 'noMessages'))).toBeTruthy();
    expect(onDiscardDraft).not.toHaveBeenCalled();
  });

  it('records a changed assignee in the reason draft', () => {
    const onWorkSubmit = vi.fn();
    wrap(<Harness onWorkSubmit={onWorkSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: inboxText('en', 'assign') }));
    const dialog = within(screen.getByRole('dialog'));
    fireEvent.change(dialog.getByLabelText(inboxText('en', 'assigneeLabel')), {
      target: { value: 'synthetic.other.backup' },
    });
    fireEvent.change(dialog.getByLabelText(inboxText('en', 'reasonLabel')), {
      target: { value: 'handover' },
    });
    fireEvent.click(dialog.getByRole('button', { name: inboxText('en', 'recordAction') }));
    expect(onWorkSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ assignee: 'synthetic.other.backup' }),
    );
  });

  it('exposes Send as a native focusable touch target', () => {
    wrap(<Harness />);
    const sendButton = screen.getByRole('button', { name: inboxText('en', 'send') });
    expect(sendButton.tagName).toBe('BUTTON');
    expect(sendButton.classList.contains('touch')).toBe(true);
    sendButton.focus();
    expect(document.activeElement).toBe(sendButton);
  });

  it('states when no files are pending and announces an opened clean attachment', () => {
    wrap(<Harness view={{ ...alphaInboxView, pendingFileNames: [] }} />);
    expect(screen.getByText(inboxText('en', 'hintNoFiles'))).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Open attachment visit-summary.pdf/ }));
    expect(screen.getByRole('status').textContent).toContain('visit-summary.pdf');
  });

  it('stacks the thread and conversation panels on a compact viewport', () => {
    vi.stubGlobal('matchMedia', () => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    try {
      wrap(<Harness />);
      const view = document.querySelector('[data-inbox-layout]');
      expect(view?.getAttribute('data-inbox-layout')).toBe('stacked');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('mirrors the inbox under Arabic right-to-left', () => {
    wrap(<Harness />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText(/صندوق الوحدة/)).toBeTruthy();
    expect(screen.getByText(inboxText('ar', 'serviceHours'))).toBeTruthy();
  });
});

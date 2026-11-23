import { useState } from 'react';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { defined } from '../../components/invariant';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { alphaQueuesView } from './fixtures';
import { operationsText } from './labels';
import { QueuesScreen } from './QueuesScreen';
import type { QueueDraft } from './types';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Own the draft so the dialog can be opened and validated like the real page. */
function Harness({
  onSubmit = vi.fn(),
  onDraftChange = vi.fn(),
}: {
  onSubmit?: (draft: QueueDraft) => void;
  onDraftChange?: (draft: QueueDraft | undefined) => void;
}) {
  const [draft, setDraft] = useState<QueueDraft | undefined>(undefined);
  return (
    <QueuesScreen
      view={alphaQueuesView}
      draft={draft}
      reasonError={undefined}
      onDraftChange={(next) => {
        setDraft(next);
        onDraftChange(next);
      }}
      onSubmit={onSubmit}
    />
  );
}

describe('QueuesScreen', () => {
  it('renders all seven queues with a word and a tone role beside each count', () => {
    wrap(<Harness />);
    expect(
      screen.getByRole('heading', { level: 2, name: operationsText('en', 'title') }),
    ).toBeTruthy();
    expect(screen.getAllByRole('rowheader')).toHaveLength(7);
    expect(screen.getByText(operationsText('en', 'unsigned-encounters'))).toBeTruthy();

    const urgent = screen.getByRole('button', { name: /Unacknowledged critical findings/ });
    expect(urgent.getAttribute('data-tone')).toBe('urgent');
    expect(urgent.textContent).toContain(operationsText('en', 'toneUrgent'));
    expect(urgent.textContent).toContain('2');

    const empty = screen.getByRole('button', { name: /Document failures/ });
    expect(empty.getAttribute('data-tone')).toBe('calm');
    expect(empty.textContent).toContain(operationsText('en', 'toneCalm'));
  });

  it('opens a reason dialog from a queue pill and records a chosen action', () => {
    const onSubmit = vi.fn();
    wrap(<Harness onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: /Unsigned encounters/ }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('heading')).toBeTruthy();

    fireEvent.change(within(dialog).getByLabelText(operationsText('en', 'actionLabel')), {
      target: { value: 'escalate' },
    });
    fireEvent.change(within(dialog).getByLabelText(operationsText('en', 'reasonLabel')), {
      target: { value: 'handed to supervisor' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: operationsText('en', 'recordAction') }));

    expect(onSubmit).toHaveBeenCalledWith({
      queueId: 'unsigned-encounters',
      action: 'escalate',
      reason: 'handed to supervisor',
      version: 1,
    });
  });

  it('emits the blank-reason draft for the page to validate', () => {
    const onSubmit = vi.fn();
    wrap(<Harness onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: /Rejected exchanges/ }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: operationsText('en', 'recordAction') }));
    expect(onSubmit).toHaveBeenCalledWith({
      queueId: 'rejected-exchanges',
      action: 'assign',
      reason: '',
      version: 2,
    });
  });

  it('closes the dialog on Cancel and offers Assign backup for the coverage card', () => {
    const onDraftChange = vi.fn();
    wrap(<Harness onDraftChange={onDraftChange} />);
    fireEvent.click(screen.getByRole('button', { name: /Unsigned encounters/ }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {
      name: operationsText('en', 'cancel'),
    }));
    expect(onDraftChange).toHaveBeenLastCalledWith(undefined);
    expect(screen.queryByRole('dialog')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: operationsText('en', 'assignBackup') }));
    expect(onDraftChange).toHaveBeenLastCalledWith({
      queueId: 'missing-coverage',
      action: 'assign',
      reason: '',
      version: 2,
    });
  });

  it('exposes the pill as a native focusable touch target', () => {
    const onDraftChange = vi.fn();
    wrap(<Harness onDraftChange={onDraftChange} />);
    const pill = screen.getByRole('button', { name: /Unsigned encounters/ });
    expect(pill.tagName).toBe('BUTTON');
    expect(pill.classList.contains('touch')).toBe(true);
    pill.focus();
    expect(document.activeElement).toBe(pill);
    fireEvent.click(pill);
    expect(onDraftChange).toHaveBeenCalledTimes(1);
  });

  it('stacks the panels on a compact viewport', () => {
    vi.stubGlobal('matchMedia', () => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    try {
      wrap(<Harness />);
      const view = defined(
        document.querySelector<HTMLElement>('[data-operations-layout]'),
        'operations layout',
      );
      expect(view.getAttribute('data-operations-layout')).toBe('stacked');
      // A fixed 320px minimum would overflow a 320px phone after page padding.
      expect(view.style.minInlineSize).toBe('0');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('mirrors the queue card under Arabic right-to-left', () => {
    wrap(<Harness />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText(operationsText('ar', 'title'))).toBeTruthy();
    expect(screen.getByRole('table', { name: operationsText('ar', 'queueCardLabel') })).toBeTruthy();
  });
});

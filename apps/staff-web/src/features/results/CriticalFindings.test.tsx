import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { CriticalFindings } from './CriticalFindings';
import { onTrackFinding, overdueFinding, resultsTimeZone } from './fixtures';

/** A fixed instant that makes the first fixture overdue and the second on time. */
const NOW = '2026-03-09T08:50:00Z';

/** Read a required element without an unsafe non-null assertion. */
function required<T>(value: T | undefined): T {
  if (value === undefined) {
    throw new Error('Expected element to be present');
  }
  return value;
}

/** Mount the panel with the same providers as the shell. */
function renderPanel(
  locale: Locale,
  onReadBack = vi.fn(),
  onAcknowledge = vi.fn(),
  onEscalate = vi.fn(),
) {
  render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <CriticalFindings
          findings={[overdueFinding, onTrackFinding]}
          locale={locale}
          now={NOW}
          timeZone={resultsTimeZone}
          onReadBack={onReadBack}
          onAcknowledge={onAcknowledge}
          onEscalate={onEscalate}
        />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { onReadBack, onAcknowledge, onEscalate };
}

describe('CriticalFindings presentation', () => {
  it('shows the panel title, action count and escalation policy', () => {
    renderPanel('en');
    expect(screen.getByRole('heading', { name: 'Critical findings' })).toBeTruthy();
    expect(screen.getByText('2 need action')).toBeTruthy();
    expect(screen.getByText(/Escalation policy/)).toBeTruthy();
  });

  it('shows the finding identity, verification and linkage for each card', () => {
    renderPanel('en');
    const card = required(screen.getAllByTestId('finding-card')[0]);
    expect(within(card).getByText('Serum potassium')).toBeTruthy();
    expect(within(card).getByText('6.4 mmol/L')).toBeTruthy();
    expect(within(card).getByText('Amira Bassam')).toBeTruthy();
    expect(within(card).getByText('MRN-SYN-4417')).toBeTruthy();
    expect(within(card).getByText('ACC-SYN-90031')).toBeTruthy();
    expect(within(card).getAllByRole('time')).toHaveLength(6);
  });

  it('marks an overdue card with a word, an icon and a data attribute', () => {
    renderPanel('en');
    const card = required(screen.getAllByTestId('finding-card')[0]);
    expect(card.getAttribute('data-overdue')).toBe('true');
    expect(within(card).getByText(/Overdue by 15 min/)).toBeTruthy();
    expect(within(card).getByLabelText('Overdue')).toBeTruthy();
  });

  it('marks a card inside its deadline with an on-time word', () => {
    renderPanel('en');
    const card = required(screen.getAllByTestId('finding-card')[1]);
    expect(card.getAttribute('data-overdue')).toBe('false');
    expect(within(card).getByText('On time')).toBeTruthy();
  });

  it('uses the Arabic overdue word under an RTL locale', () => {
    renderPanel('ar');
    expect(screen.getByText(/متأخرة بـ/)).toBeTruthy();
  });

  it('says Due now at the exact deadline instead of zero minutes overdue', () => {
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <CriticalFindings
            findings={[{ ...overdueFinding, dueAt: NOW }]}
            locale="en"
            now={NOW}
            timeZone={resultsTimeZone}
            onReadBack={vi.fn()}
            onAcknowledge={vi.fn()}
            onEscalate={vi.fn()}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getByText('Due now')).toBeTruthy();
  });

  it('tags the patient run with the script actually displayed', () => {
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="ar">
          <CriticalFindings
            findings={[{ ...overdueFinding, patient: { latinName: 'Synthetic NoLocalName' } }]}
            locale="ar"
            now={NOW}
            timeZone={resultsTimeZone}
            onReadBack={vi.fn()}
            onAcknowledge={vi.fn()}
            onEscalate={vi.fn()}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    const run = screen.getByText('Synthetic NoLocalName');
    expect(run.getAttribute('lang')).toBe('en');
  });

  it('shows the full event trail in supplied order, including duplicate and reordered events', () => {
    const firstEvent = required(overdueFinding.events[0]);
    const alertEvent = required(overdueFinding.events[1]);
    const lastEvent = required(overdueFinding.events[3]);
    const reversed = {
      ...overdueFinding,
      events: [lastEvent, firstEvent, alertEvent, { ...alertEvent, id: 'event-duplicate' }],
    };
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <CriticalFindings
            findings={[reversed]}
            locale="en"
            now={NOW}
            timeZone={resultsTimeZone}
            onReadBack={vi.fn()}
            onAcknowledge={vi.fn()}
            onEscalate={vi.fn()}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    const events = screen.getAllByTestId('finding-event').map((item) => item.textContent);
    expect(events).toHaveLength(4);
    expect(events[0]).toContain('Deadline passed');
    expect(events[1]).toContain('Verified by lab');
    expect(events[2]).toContain('Alert sent');
    expect(events[3]).toContain('Alert sent');
  });

  it('shows the owner and coverage, with words when no owner is staffed', () => {
    renderPanel('en');
    const cards = screen.getAllByTestId('finding-card');
    expect(within(required(cards[0])).getByText(/Dr Synthetic Oryx/)).toBeTruthy();
    const card = required(cards[1]);
    expect(within(card).getByText(/No named owner staffed/)).toBeTruthy();
    expect(within(card).getByText(/Escalation queue/)).toBeTruthy();
  });

  it('renders an explicit empty state when no finding needs action', () => {
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <CriticalFindings
            findings={[]}
            locale="en"
            now={NOW}
            timeZone={resultsTimeZone}
            onReadBack={vi.fn()}
            onAcknowledge={vi.fn()}
            onEscalate={vi.fn()}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getByText('No critical findings need action.')).toBeTruthy();
  });
});

describe('CriticalFindings announcement', () => {
  it('announces the open critical region once without moving focus', () => {
    renderPanel('en');
    const status = screen.getByRole('status');
    expect(status.textContent).toBe('Critical findings: 2 need action');
    expect(document.activeElement).toBe(document.body);
  });

  it('suppresses the announcement while the user is typing', () => {
    // Focus a text field before mounting the region: the announcement decision
    // is made from the active element at load, and autoFocus is an a11y defect.
    const entry = document.createElement('input');
    entry.setAttribute('aria-label', 'Synthetic entry');
    document.body.appendChild(entry);
    entry.focus();
    try {
      render(
        <ThemeProvider>
          <I18nProvider initialLocale="en">
            <CriticalFindings
              findings={[overdueFinding]}
              locale="en"
              now={NOW}
              timeZone={resultsTimeZone}
              onReadBack={vi.fn()}
              onAcknowledge={vi.fn()}
              onEscalate={vi.fn()}
            />
          </I18nProvider>
        </ThemeProvider>,
      );
      expect(screen.getByRole('status').textContent).toBe('');
    } finally {
      entry.remove();
    }
  });
});

describe('CriticalFindings actions', () => {
  it('offers all three actions and dispatches acknowledgement and escalation', () => {
    const { onAcknowledge, onEscalate } = renderPanel('en');
    const card = required(screen.getAllByTestId('finding-card')[0]);
    fireEvent.click(within(card).getByRole('button', { name: 'Acknowledge' }));
    fireEvent.click(within(card).getByRole('button', { name: 'Escalate now' }));
    expect(onAcknowledge).toHaveBeenCalledWith('finding-synthetic-01');
    expect(onEscalate).toHaveBeenCalledWith('finding-synthetic-01');
  });

  it('refuses a read-back when the finding has no recipient', () => {
    const { onReadBack } = renderPanel('en');
    const card = required(screen.getAllByTestId('finding-card')[0]);
    fireEvent.click(within(card).getByRole('button', { name: 'Record contact and read-back' }));
    expect(within(card).getByRole('alert').textContent).toContain('A recipient is required');
    expect(onReadBack).not.toHaveBeenCalled();
  });

  it('records a read-back when a recipient is present', () => {
    const { onReadBack } = renderPanel('en');
    const card = required(screen.getAllByTestId('finding-card')[1]);
    fireEvent.click(within(card).getByRole('button', { name: 'Record contact and read-back' }));
    expect(onReadBack).toHaveBeenCalledWith('finding-synthetic-02');
    expect(within(card).queryByRole('alert')).toBeNull();
  });
});

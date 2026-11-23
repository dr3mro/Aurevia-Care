import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../theme/breakpoints';
import { ThemeProvider } from '../../theme/ThemeProvider';
import {
  mismatchedReconciliation,
  onTrackFinding,
  overdueFinding,
  resultsTimeZone,
  resultsViewerId,
  sampleInboxRows,
} from './fixtures';
import { ResultsScreen } from './ResultsScreen';

/** A fixed instant that makes one finding overdue and one on time. */
const NOW = '2026-03-09T08:50:00Z';

/** Install a deterministic matchMedia stand-in for the requested band. */
function installMatchMedia(band: 'compact' | 'expanded'): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: answers[query] ?? false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    })),
  );
}

/** Mount the whole screen under the shell providers. */
function renderScreen(
  locale: Locale = 'en',
  onReadBack = vi.fn(),
): { onReadBack: ReturnType<typeof vi.fn> } {
  const element: ReactElement = (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ResultsScreen
          snapshot={{
            organizationId: '11111111-1111-1111-1111-111111111111',
            findings: [overdueFinding, onTrackFinding],
            rows: sampleInboxRows,
            reconciliation: mismatchedReconciliation,
          }}
          now={NOW}
          timeZone={resultsTimeZone}
          viewerId={resultsViewerId}
          onReadBack={onReadBack}
          onAcknowledge={vi.fn()}
          onEscalate={vi.fn()}
          onAskLab={vi.fn()}
          onAssignOwner={vi.fn()}
          onLink={vi.fn()}
        />
      </I18nProvider>
    </ThemeProvider>
  );
  render(element);
  return { onReadBack };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ResultsScreen layout and filters', () => {
  it('renders the heading, subtitle, three chips and both lower panels', () => {
    renderScreen();
    expect(screen.getByRole('heading', { name: 'Results' })).toBeTruthy();
    expect(screen.getByText(/Every final, corrected and critical result/)).toBeTruthy();
    expect(screen.getByRole('button', { name: /Mine/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /My coverage queue/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Unmatched/ })).toBeTruthy();
    expect(screen.getByRole('group', { name: 'Filter results' })).toBeTruthy();
    expect(document.body.querySelector('[data-layout]')?.getAttribute('data-layout')).toBe(
      'side-by-side',
    );
    expect(screen.getByRole('heading', { name: 'Inbox' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Critical findings' })).toBeTruthy();
    expect(screen.getByText('Reconciliation: unmatched report')).toBeTruthy();
  });

  it('switches visible rows and aria-pressed when a filter chip is chosen', () => {
    renderScreen();
    expect(screen.getByText(/Nadia Farouk/)).toBeTruthy();
    expect(screen.queryByText(/Yusuf Idris/)).toBeNull();

    const coverage = screen.getByRole('button', { name: /My coverage queue/ });
    fireEvent.click(coverage);
    expect(coverage.getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByText(/Yusuf Idris/)).toBeTruthy();
    expect(screen.queryByText(/Nadia Farouk/)).toBeNull();

    const unmatched = screen.getByRole('button', { name: /Unmatched/ });
    fireEvent.click(unmatched);
    expect(unmatched.getAttribute('aria-pressed')).toBe('true');
    expect(coverage.getAttribute('aria-pressed')).toBe('false');
    expect(screen.getByText(/Layla Munir/)).toBeTruthy();
  });

  it('keeps the primary action focusable and activates it', () => {
    const { onReadBack } = renderScreen();
    const actions = screen.getAllByRole('button', { name: 'Record contact and read-back' });
    // The second fixture carries a confirmed recipient, so its read-back is
    // the one that can actually be recorded.
    const primary = actions[1];
    if (primary === undefined) {
      throw new Error('Expected a primary action');
    }
    primary.focus();
    expect(document.activeElement).toBe(primary);
    fireEvent.click(primary);
    expect(onReadBack).toHaveBeenCalledWith('finding-synthetic-02');
  });

  it('renders in Arabic right-to-left with the table still present', () => {
    renderScreen('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByRole('table')).toBeTruthy();
  });

  it('stacks the two lower panels on a compact viewport', () => {
    installMatchMedia('compact');
    renderScreen();
    const panels = document.body.querySelector('[data-layout]');
    expect(panels?.getAttribute('data-layout')).toBe('stacked');
  });
});

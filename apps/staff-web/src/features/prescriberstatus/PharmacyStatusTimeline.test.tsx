import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import {
  syntheticEmptyStatus,
  syntheticOtherStatus,
  syntheticRejectedStatus,
  syntheticStatus,
} from './fixtures';
import { prescriberText } from './labels';
import { PharmacyStatusTimeline } from './PharmacyStatusTimeline';
import type { StatusViewResponse } from '../pharmacyinbox/types';

const timeZone = 'UTC';

function wrap(status: StatusViewResponse, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <PharmacyStatusTimeline status={status} locale={locale} timeZone={timeZone} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

function rowFor(id: string): HTMLElement {
  return screen.getByTestId(`timeline-row-${id}`);
}

describe('PharmacyStatusTimeline', () => {
  it('keeps submitted, delivered, clarification, accepted and dispensing in separate rows', () => {
    wrap(syntheticStatus);

    expect(screen.getAllByTestId(/^timeline-row-/)).toHaveLength(5);
    expect(
      within(rowFor('event-synthetic-submitted')).getByText(prescriberText('en', 'eventSubmitted')),
    ).toBeTruthy();
    expect(
      within(rowFor('event-synthetic-delivery')).getByText('Synthetic partner confirmed receipt'),
    ).toBeTruthy();
    expect(
      within(rowFor('event-synthetic-clarification')).getByText(
        'Which strength should be dispensed?',
      ),
    ).toBeTruthy();
    expect(
      within(rowFor('event-synthetic-accept')).getByText(prescriberText('en', 'eventAccepted')),
    ).toBeTruthy();
    expect(
      within(rowFor('event-synthetic-dispensed')).getByText('30 synthetic tablets'),
    ).toBeTruthy();
    expect(
      within(rowFor('event-synthetic-dispensed')).getByText('2026-11-21T10:30:00Z'),
    ).toBeTruthy();
  });

  it('never merges dispensing evidence into the acceptance row', () => {
    wrap(syntheticStatus);

    expect(within(rowFor('event-synthetic-accept')).queryByText('30 synthetic tablets')).toBeNull();
    expect(
      within(rowFor('event-synthetic-accept')).queryByText(
        prescriberText('en', 'dispensingEvidence'),
      ),
    ).toBeNull();
    expect(
      within(rowFor('event-synthetic-submitted')).queryByText(prescriberText('en', 'factDetail')),
    ).toBeNull();
  });

  it('shows a rejection as its own row with the reason', () => {
    wrap(syntheticRejectedStatus);

    expect(screen.getAllByTestId(/^timeline-row-/)).toHaveLength(1);
    expect(
      within(rowFor('event-synthetic-reject')).getByText(prescriberText('en', 'eventRejected')),
    ).toBeTruthy();
    expect(
      within(rowFor('event-synthetic-reject')).getByText('Synthetic stock unavailable'),
    ).toBeTruthy();
  });

  it('shows a generic row for an unknown fact type', () => {
    wrap(syntheticOtherStatus);

    expect(
      within(rowFor('event-synthetic-other')).getByText(prescriberText('en', 'eventOther')),
    ).toBeTruthy();
  });

  it('shows the empty state when no fact has been recorded', () => {
    wrap(syntheticEmptyStatus);

    expect(screen.getByText(prescriberText('en', 'noEvents'))).toBeTruthy();
    expect(screen.queryByTestId(/^timeline-row-/)).toBeNull();
  });

  it('renders Arabic labels, isolated clinical runs and a right-to-left document', () => {
    wrap(syntheticStatus, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(
      screen.getByRole('heading', { name: prescriberText('ar', 'timelineTitle') }),
    ).toBeTruthy();
    expect(screen.getByText(prescriberText('ar', 'eventDispensed'))).toBeTruthy();
    const isolated = screen.getByText('Synthetic partner confirmed receipt');
    expect(isolated.tagName).toBe('BDI');
    expect(isolated.getAttribute('dir')).toBe('auto');
    expect(screen.getByText('event-synthetic-dispensed').getAttribute('dir')).toBe('ltr');
  });
});

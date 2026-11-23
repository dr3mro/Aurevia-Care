import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import { alphaOrganizationId, betaOrganizationId, syntheticCharts } from './fixtures';
import { chartText } from './messages';
import { ChartScreen } from './ChartScreen';
import type { NoteEditorProps } from './NoteEditor';
import type * as NoteEditorModule from './NoteEditor';
import { must } from '../../../tests/must';

/** Records the tenant of every note revision the screen renders, including interim renders. */
const observed = vi.hoisted(() => ({ noteEditorTenants: [] as string[] }));

vi.mock('./NoteEditor', async (importOriginal) => {
  const actual = await importOriginal<typeof NoteEditorModule>();
  return {
    ...actual,
    NoteEditor: (props: NoteEditorProps) => {
      observed.noteEditorTenants.push(props.revision.organizationId);
      return <actual.NoteEditor {...props} />;
    },
  };
});

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function screenFor(organizationId: string, locale: Locale = 'en') {
  return wrap(<ChartScreen charts={syntheticCharts} organizationId={organizationId} />, locale);
}

describe('ChartScreen', () => {
  it('shows only the active tenant and a read-only signed revision with history', () => {
    screenFor(alphaOrganizationId);
    expect(screen.getAllByText('Synthetic Layla').length).toBeGreaterThan(0);
    expect(screen.queryByText('Synthetic Reem')).toBeNull();
    expect(screen.getByRole('heading', { level: 2, name: chartText('en', 'chart') })).toBeTruthy();
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: chartText('en', 'plan') }).readOnly,
    ).toBe(true);
    expect(screen.getByRole('button', { name: chartText('en', 'amend') })).toBeTruthy();
    expect(screen.getByRole('list', { name: chartText('en', 'history') })).toBeTruthy();
    expect(screen.getByTestId('chart-status').textContent).toBe('');
  });

  it('signs a tenant-scoped draft only after attestation', () => {
    screenFor(betaOrganizationId);
    expect(screen.getAllByText('Synthetic Reem').length).toBeGreaterThan(0);
    expect(screen.queryByText('Synthetic Layla')).toBeNull();
    const sign = screen.getByRole<HTMLButtonElement>('button', { name: chartText('en', 'sign') });
    expect(sign.disabled).toBe(true);
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    fireEvent.click(sign);
    expect(screen.getByTestId('chart-status').textContent).toContain(
      chartText('en', 'signedNotice'),
    );
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: chartText('en', 'plan') }).readOnly,
    ).toBe(true);
    expect(screen.queryByRole('button', { name: chartText('en', 'sign') })).toBeNull();
  });

  it('edits a draft, creates an amendment and supersedes the source revision', () => {
    screenFor(betaOrganizationId);
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'assessment') }), {
      target: { value: 'Synthetic amended assessment' },
    });
    expect(screen.getByTestId('chart-status').textContent).toContain(
      chartText('en', 'updateNotice'),
    );
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'sign') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'amend') }));
    expect(screen.getByTestId('chart-status').textContent).toContain(
      chartText('en', 'amendNotice'),
    );
    expect(screen.getByText(chartText('en', 'noteAmendment'))).toBeTruthy();
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: chartText('en', 'plan') }).readOnly,
    ).toBe(false);
    expect(screen.queryByRole('button', { name: chartText('en', 'amend') })).toBeNull();
  });

  it('preserves the signed source revision in history after amendment', () => {
    screenFor(betaOrganizationId);
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'sign') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'amend') }));

    // The signed source is retained in the append-only history, never edited,
    // and the new amendment draft names the exact revision it supersedes (CLN-05).
    const history = screen.getByRole('list', { name: chartText('en', 'history') });
    expect(
      within(history)
        .getAllByRole('listitem')
        .map((item) => item.getAttribute('data-revision-id')),
    ).toEqual(['rev-001', 'rev-002']);
    expect(screen.getByTestId('amendment-notice').textContent).toContain('rev-002');
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: chartText('en', 'plan') }).readOnly,
    ).toBe(false);
  });

  it('records entered-in-error while preserving the prior revision', () => {
    screenFor(alphaOrganizationId);
    const history = screen.getByRole('list', { name: chartText('en', 'history') });
    expect(within(history).getAllByRole('listitem')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'markEnteredInError') }));
    expect(screen.getByRole('alert').textContent).toContain(chartText('en', 'enteredInError'));
    expect(screen.getByTestId('chart-status').textContent).toContain(
      chartText('en', 'enterErrorNotice'),
    );
    expect(screen.queryByRole('button', { name: chartText('en', 'amend') })).toBeNull();
    expect(within(history).getAllByRole('listitem')).toHaveLength(1);
  });

  it('voids and rotates only the selected document association', () => {
    screenFor(alphaOrganizationId);
    const table = screen.getByRole('table', { name: chartText('en', 'documentList') });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(5);
    const liveRow = must(rows[1]);
    fireEvent.click(within(liveRow).getByRole('button', { name: chartText('en', 'rotate') }));
    expect(screen.getByTestId('chart-status').textContent).toContain(
      chartText('en', 'rotateNotice'),
    );
    fireEvent.click(within(liveRow).getByRole('button', { name: chartText('en', 'void') }));
    expect(screen.getByTestId('chart-status').textContent).toContain(chartText('en', 'voidNotice'));
    expect(
      within(liveRow).getByText(
        (content, element) =>
          element?.tagName === 'TD' && content.startsWith(chartText('en', 'documentVoided')),
      ),
    ).toBeTruthy();
    expect(
      within(liveRow).getByRole<HTMLButtonElement>('button', { name: chartText('en', 'void') })
        .disabled,
    ).toBe(true);
  });
});

describe('ChartScreen tenant switching and empty states', () => {
  it('resets local state when the active tenant changes', () => {
    const { rerender } = screenFor(alphaOrganizationId);
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'markEnteredInError') }));
    expect(screen.getByRole('alert')).toBeTruthy();

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <ChartScreen charts={syntheticCharts} organizationId={betaOrganizationId} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getAllByText('Synthetic Reem').length).toBeGreaterThan(0);
    expect(screen.queryByText('Synthetic Layla')).toBeNull();
    expect(screen.getByRole('button', { name: chartText('en', 'sign') })).toBeTruthy();
    expect(screen.getByTestId('chart-status').textContent).toBe('');
  });

  it('renders bilingual RTL copy and local names under the Arabic locale', () => {
    screenFor(alphaOrganizationId, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { level: 2, name: chartText('ar', 'chart') })).toBeTruthy();
    expect(screen.getByRole('table', { name: chartText('ar', 'vitals') })).toBeTruthy();
    expect(screen.getAllByText('ليلى تجريبية')[0]?.getAttribute('dir')).toBe('auto');
    expect(screen.getByRole('button', { name: chartText('ar', 'amend') })).toBeTruthy();
  });

  it('shows a concealment-safe empty state when no chart is visible', () => {
    wrap(<ChartScreen charts={syntheticCharts} organizationId="unknown" />);
    expect(screen.getByText(chartText('en', 'noChart'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('shows a note empty state when the visible chart has no revisions', () => {
    const chartWithoutNotes = { ...must(syntheticCharts[0]), revisions: [] };
    wrap(<ChartScreen charts={[chartWithoutNotes]} organizationId={alphaOrganizationId} />);
    expect(screen.getByText(chartText('en', 'noRevisions'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: chartText('en', 'amend') })).toBeNull();
  });

  it('never renders the previous tenant note state when the active organization changes', () => {
    observed.noteEditorTenants.length = 0;
    const { rerender } = screenFor(alphaOrganizationId);
    expect(observed.noteEditorTenants.length).toBeGreaterThan(0);
    expect(observed.noteEditorTenants.every((id) => id === alphaOrganizationId)).toBe(true);

    observed.noteEditorTenants.length = 0;
    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <ChartScreen charts={syntheticCharts} organizationId={betaOrganizationId} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(observed.noteEditorTenants.length).toBeGreaterThan(0);
    // A lagging useEffect would render one frame with alpha's revisions under
    // beta's chart; every rendered revision must belong to the active tenant.
    expect(observed.noteEditorTenants.every((id) => id === betaOrganizationId)).toBe(true);
  });

  it('gates signing on the required Plan field and preserves typed work', () => {
    screenFor(betaOrganizationId);
    const plan = screen.getByRole<HTMLTextAreaElement>('textbox', {
      name: chartText('en', 'plan'),
    });
    fireEvent.change(plan, { target: { value: '' } });
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    const sign = screen.getByRole<HTMLButtonElement>('button', { name: chartText('en', 'sign') });
    expect(sign.disabled).toBe(true);
    expect(screen.getByText(chartText('en', 'signDisabledReason'))).toBeTruthy();
    expect(plan.getAttribute('aria-invalid')).toBe('true');
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: 'Synthetic preserved concern' },
    });
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: chartText('en', 'subjective') }).value,
    ).toBe('Synthetic preserved concern');
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: chartText('en', 'plan') }).readOnly,
    ).toBe(false);
  });

  it('announces the domain rejection when a non-Plan required field is empty', () => {
    screenFor(betaOrganizationId);
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'subjective') }), {
      target: { value: '' },
    });
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'sign') }));
    expect(screen.getByRole('alert').textContent).toContain(chartText('en', 'signError'));
    expect(screen.getByTestId('chart-status').textContent).toBe('');
  });
});

describe('ChartScreen approved-design chrome', () => {
  it('shows the synthetic patient banner with demographics, chips and actions', () => {
    screenFor(alphaOrganizationId);
    const banner = screen.getByRole('region', { name: chartText('en', 'patientBanner') });
    expect(within(banner).getByText('Synthetic Layla')).toBeTruthy();

    const demographics = must(banner.querySelector('[data-part="demographics"]'));
    expect(demographics.textContent).toContain('63 y');
    expect(demographics.textContent).toContain('female');
    expect(demographics.textContent).toContain(chartText('en', 'born'));
    expect(demographics.textContent).toContain(chartText('en', 'mrn'));
    expect(demographics.textContent).toContain('SYN-099804');
    expect(demographics.textContent).toContain(chartText('en', 'issuer'));
    expect(demographics.textContent).toContain('Harbor Family Clinic');

    const chips = must(banner.querySelector('[data-part="chips"]'));
    expect(chips.textContent).toContain(chartText('en', 'allergy'));
    expect(chips.textContent).toContain('Synthetic penicillin');
    expect(chips.textContent).toContain('rash');
    expect(chips.textContent).toContain(chartText('en', 'verificationVerified'));
    expect(chips.textContent).toContain(chartText('en', 'language'));
    expect(chips.textContent).toContain(chartText('en', 'languageEn'));
    expect(chips.textContent).toContain('1');
    expect(chips.textContent).toContain(chartText('en', 'resultsToReview'));

    expect(within(banner).getByRole('button', { name: chartText('en', 'newOrder') })).toBeTruthy();
    expect(within(banner).getByRole('button', { name: chartText('en', 'prescribe') })).toBeTruthy();
  });

  it('offers six chart tabs with Overview selected and lets another tab take the current state', () => {
    screenFor(alphaOrganizationId);
    const tabs = screen.getByRole('navigation', { name: chartText('en', 'chartTabs') });
    const overview = within(tabs).getByRole('button', { name: chartText('en', 'overview') });
    expect(within(tabs).getAllByRole('button')).toHaveLength(6);
    expect(overview.getAttribute('aria-current')).toBe('page');
    for (const key of ['notes', 'orders', 'results', 'documents', 'messages'] as const) {
      expect(within(tabs).getByRole('button', { name: chartText('en', key) })).toBeTruthy();
    }
    const notes = within(tabs).getByRole('button', { name: chartText('en', 'notes') });
    fireEvent.click(notes);
    expect(notes.getAttribute('aria-current')).toBe('page');
    expect(overview.getAttribute('aria-current')).toBeNull();
  });

  it('composes the overview, note and results regions for any tenant', () => {
    screenFor(alphaOrganizationId);
    expect(screen.getByRole('region', { name: chartText('en', 'overviewCards') })).toBeTruthy();
    expect(screen.getByRole('region', { name: chartText('en', 'recentResults') })).toBeTruthy();
    const content = must(screen.getByRole('region', { name: chartText('en', 'chart') }).querySelector<HTMLElement>('[data-part="chart-content"]'));
    expect(content.querySelectorAll('[data-part="overview-cards"], [data-part="note"]')).toHaveLength(2);
    expect(content.style.gridTemplateColumns).toContain('20rem');
  });

  it('omits the follow-up card for a draft chart without a suggestion', () => {
    const withoutSuggestion = { ...must(syntheticCharts[1]), followUp: undefined };
    wrap(<ChartScreen charts={[withoutSuggestion]} organizationId={betaOrganizationId} />);
    expect(screen.queryByRole('heading', { name: chartText('en', 'followUpSuggestion') })).toBeNull();
    expect(screen.getByRole('button', { name: chartText('en', 'sign') })).toBeTruthy();
  });

  it('composes the follow-up and sign affordances for a draft revision', () => {
    screenFor(betaOrganizationId);
    expect(screen.getByRole('heading', { name: chartText('en', 'followUpSuggestion') })).toBeTruthy();
    expect(screen.getByRole('heading', { name: chartText('en', 'signSection') })).toBeTruthy();
  });

  it('omits the allergy and results chips when the chart has no such data', () => {
    screenFor(betaOrganizationId);
    const banner = screen.getByRole('region', { name: chartText('en', 'patientBanner') });
    const chips = must(banner.querySelector('[data-part="chips"]'));
    expect(chips.textContent).not.toContain(chartText('en', 'allergy'));
    expect(chips.textContent).not.toContain(chartText('en', 'resultsToReview'));
  });

  it('announces a preview notice for banner actions instead of pretending to book', () => {
    screenFor(alphaOrganizationId);
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'newOrder') }));
    expect(screen.getByTestId('chart-status').textContent).toContain(chartText('en', 'previewNotice'));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'prescribe') }));
    expect(screen.getByTestId('chart-status').textContent).toContain(chartText('en', 'previewNotice'));
  });

  it('announces follow-up booking and override outcomes from the draft chart', () => {
    screenFor(betaOrganizationId);
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'confirmAndBook') }));
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: chartText('en', 'confirmBooking'),
      }),
    );
    expect(screen.getByTestId('chart-status').textContent).toContain(chartText('en', 'bookedNotice'));

    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'overrideWithReason') }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(
      within(dialog).getByRole('textbox', { name: chartText('en', 'overrideReason') }),
      { target: { value: 'Synthetic scheduling conflict' } },
    );
    fireEvent.click(
      within(dialog).getByRole('button', { name: chartText('en', 'confirmOverride') }),
    );
    expect(screen.getByTestId('chart-status').textContent).toContain(
      chartText('en', 'overrideNotice'),
    );
  });

  it('renders an Arabic banner under RTL', () => {
    screenFor(alphaOrganizationId, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    const banner = screen.getByRole('region', { name: chartText('ar', 'patientBanner') });
    expect(within(banner).getByText('ليلى تجريبية')).toBeTruthy();
    expect(must(banner.querySelector('[data-part="chips"]')).textContent).toContain(
      chartText('ar', 'allergy'),
    );
  });

  it('omits synthetic-only cards when the chart has no demo summary, results or follow-up', () => {
    const bare = {
      ...must(syntheticCharts[0]),
      patientSummary: undefined,
      recentResults: undefined,
      followUp: undefined,
    };
    wrap(<ChartScreen charts={[bare]} organizationId={alphaOrganizationId} />);
    expect(screen.queryByRole('region', { name: chartText('en', 'patientBanner') })).toBeNull();
    expect(screen.queryByRole('region', { name: chartText('en', 'recentResults') })).toBeNull();
    expect(screen.queryByRole('heading', { name: chartText('en', 'followUpSuggestion') })).toBeNull();
  });
});

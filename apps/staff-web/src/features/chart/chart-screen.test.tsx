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

  it('announces an accessible error and keeps the draft editable when signing an incomplete note', () => {
    screenFor(betaOrganizationId);
    for (const field of ['subjective', 'objective', 'assessment', 'plan'] as const) {
      fireEvent.change(screen.getByRole('textbox', { name: chartText('en', field) }), {
        target: { value: '' },
      });
    }
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'sign') }));
    expect(screen.getByRole('alert').textContent).toContain(chartText('en', 'signError'));
    expect(screen.getByTestId('chart-status').textContent).toBe('');
    expect(
      screen.getByRole<HTMLTextAreaElement>('textbox', { name: chartText('en', 'plan') }).readOnly,
    ).toBe(false);
  });
});

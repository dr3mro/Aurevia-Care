import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { DEFAULT_FROM, DEFAULT_TO, ChartSectionPicker } from './ChartSectionPicker';
import { chartPrintSections } from './domain';
import type { ChartPrintScope, ChartPrintSection, ChartPrintVisit } from './domain';
import { syntheticVisits } from './fixtures';
import { chartPrintText, chartSectionMessageKey } from './labels';

function renderPicker(
  scope: ChartPrintScope,
  sections: readonly ChartPrintSection[],
  visits: readonly ChartPrintVisit[] = syntheticVisits,
  locale: Locale = 'en',
) {
  const onScopeChange = vi.fn<(next: ChartPrintScope) => void>();
  const onSectionToggle = vi.fn<(section: ChartPrintSection) => void>();
  const ui: ReactElement = (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ChartSectionPicker
          controlId="test-picker"
          scope={scope}
          sections={sections}
          visits={visits}
          locale={locale}
          onScopeChange={onScopeChange}
          onSectionToggle={onSectionToggle}
        />
      </I18nProvider>
    </ThemeProvider>
  );
  render(ui);
  return { onScopeChange, onSectionToggle };
}

const visitScope: ChartPrintScope = { kind: 'visit', visitId: 'visit-001' };
const rangeScope: ChartPrintScope = { kind: 'range', from: '2026-03-01', to: '2026-03-31' };

describe('chart section picker', () => {
  it('shows a visit selector and a checked choice for every section by default', () => {
    renderPicker(visitScope, chartPrintSections);
    const visitRadio = screen.getByRole('radio', { name: chartPrintText('en', 'visit') });
    const rangeRadio = screen.getByRole('radio', { name: chartPrintText('en', 'dateRange') });
    expect((visitRadio as HTMLInputElement).checked).toBe(true);
    expect((rangeRadio as HTMLInputElement).checked).toBe(false);
    const selector = screen.getByLabelText<HTMLSelectElement>(chartPrintText('en', 'visitId'));
    expect(selector.value).toBe('visit-001');
    expect(selector.options).toHaveLength(2);
    for (const section of chartPrintSections) {
      const key = chartSectionMessageKey[section];
      expect(
        screen.getByRole<HTMLInputElement>('checkbox', { name: chartPrintText('en', key) }).checked,
      ).toBe(true);
    }
  });

  it('reports a changed visit and a toggled section', () => {
    const { onScopeChange, onSectionToggle } = renderPicker(visitScope, chartPrintSections);
    fireEvent.change(screen.getByLabelText(chartPrintText('en', 'visitId')), {
      target: { value: 'visit-002' },
    });
    expect(onScopeChange).toHaveBeenCalledWith({ kind: 'visit', visitId: 'visit-002' });
    fireEvent.click(
      screen.getByRole('checkbox', { name: chartPrintText('en', 'sectionProblems') }),
    );
    expect(onSectionToggle).toHaveBeenCalledWith('problems');
  });

  it('shows editable date inputs for a date range', () => {
    const { onScopeChange } = renderPicker(rangeScope, ['notes']);
    const from = screen.getByLabelText<HTMLInputElement>(chartPrintText('en', 'from'));
    const to = screen.getByLabelText<HTMLInputElement>(chartPrintText('en', 'to'));
    expect(from.value).toBe('2026-03-01');
    expect(to.value).toBe('2026-03-31');
    expect(
      screen.getByRole<HTMLInputElement>('checkbox', { name: chartPrintText('en', 'sectionNotes') })
        .checked,
    ).toBe(true);
    expect(
      screen.getByRole<HTMLInputElement>('checkbox', {
        name: chartPrintText('en', 'sectionProblems'),
      }).checked,
    ).toBe(false);
    fireEvent.change(from, { target: { value: '2026-02-01' } });
    expect(onScopeChange).toHaveBeenCalledWith({
      kind: 'range',
      from: '2026-02-01',
      to: '2026-03-31',
    });
    fireEvent.change(to, { target: { value: '2026-04-01' } });
    expect(onScopeChange).toHaveBeenLastCalledWith({
      kind: 'range',
      from: '2026-03-01',
      to: '2026-04-01',
    });
  });

  it('switches between visit and date range with bounded defaults', () => {
    const { onScopeChange } = renderPicker(visitScope, ['vitals']);
    fireEvent.click(screen.getByRole('radio', { name: chartPrintText('en', 'dateRange') }));
    expect(onScopeChange).toHaveBeenCalledWith({
      kind: 'range',
      from: DEFAULT_FROM,
      to: DEFAULT_TO,
    });
  });

  it('switches back to the first available visit', () => {
    const { onScopeChange } = renderPicker(rangeScope, ['vitals']);
    fireEvent.click(screen.getByRole('radio', { name: chartPrintText('en', 'visit') }));
    expect(onScopeChange).toHaveBeenCalledWith({ kind: 'visit', visitId: 'visit-001' });
  });

  it('names the missing visit state instead of offering an empty selector', () => {
    renderPicker({ kind: 'visit', visitId: '' }, ['vitals'], []);
    expect(screen.getByText(chartPrintText('en', 'noVisit'))).toBeTruthy();
    expect(screen.queryByRole('combobox')).toBeNull();
  });

  it('selects an empty visit id when no visit exists', () => {
    const { onScopeChange } = renderPicker(rangeScope, ['vitals'], []);
    fireEvent.click(screen.getByRole('radio', { name: chartPrintText('en', 'visit') }));
    expect(onScopeChange).toHaveBeenCalledWith({ kind: 'visit', visitId: '' });
  });

  it('renders a fully Arabic picker in a right-to-left document', () => {
    renderPicker(visitScope, chartPrintSections, syntheticVisits, 'ar');
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('radio', { name: chartPrintText('ar', 'visit') })).toBeTruthy();
    expect(
      screen.getByRole('checkbox', { name: chartPrintText('ar', 'sectionNotes') }),
    ).toBeTruthy();
    expect(screen.getByLabelText(chartPrintText('ar', 'visitId'))).toBeTruthy();
  });
});

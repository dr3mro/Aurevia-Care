import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ReactElement } from 'react';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { ChartPrintPreview } from './ChartPrintPreview';
import type { ChartPrintPreview as ChartPrintPreviewModel } from './domain';
import { syntheticChartPatient, syntheticDraftReport, syntheticSignedReport } from './fixtures';
import { chartPrintText } from './labels';
import { buildChartPrintPreview, buildSignedReportPreview, chartPrintSections } from './domain';

function renderPreview(model: ChartPrintPreviewModel, locale: Locale = 'en') {
  const ui: ReactElement = (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ChartPrintPreview locale={locale} model={model} />
      </I18nProvider>
    </ThemeProvider>
  );
  render(ui);
}

const chartModel = buildChartPrintPreview({
  patientName: syntheticChartPatient,
  scope: { kind: 'visit', visitId: 'visit-001' },
  sections: chartPrintSections,
  counts: { problems: 2, allergies: 1, medications: 1, vitals: 3, notes: 0, results: 0 },
  notes: [
    { id: 'n1', title: 'Synthetic signed note', state: 'signed', encounterId: 'visit-001' },
    { id: 'n2', title: 'Synthetic unsigned note', state: 'draft', encounterId: 'visit-001' },
  ],
  results: [
    { id: 'r1', title: 'Synthetic released result', withheld: false },
    { id: 'r2', title: 'Synthetic withheld result', withheld: true },
  ],
});

describe('chart summary print preview', () => {
  it('shows the patient, the chosen visit and every included section with its count', () => {
    renderPreview(chartModel);
    expect(screen.getByRole('heading', { name: chartPrintText('en', 'chartPrint') })).toBeTruthy();
    expect(screen.getByText('Synthetic Layla')).toBeTruthy();
    expect(screen.getByText(`${chartPrintText('en', 'sectionProblems')}: 2`)).toBeTruthy();
    expect(screen.getByText(`${chartPrintText('en', 'sectionNotes')}: 1`)).toBeTruthy();
    expect(screen.getByText('visit-001')).toBeTruthy();
  });

  it('lists a left-out unsigned draft with its reason', () => {
    renderPreview(chartModel);
    const leftOut = screen.getByRole('list', { name: chartPrintText('en', 'leftOut') });
    expect(leftOut.textContent).toContain('Synthetic unsigned note');
    expect(leftOut.textContent).toContain(chartPrintText('en', 'reasonUnsigned'));
  });

  it('lists a left-out withheld result with its reason', () => {
    renderPreview(chartModel);
    const leftOut = screen.getByRole('list', { name: chartPrintText('en', 'leftOut') });
    expect(leftOut.textContent).toContain('Synthetic withheld result');
    expect(leftOut.textContent).toContain(chartPrintText('en', 'reasonWithheld'));
  });

  it('renders the chosen date range instead of a visit', () => {
    renderPreview(
      buildChartPrintPreview({
        patientName: syntheticChartPatient,
        scope: { kind: 'range', from: '2026-03-01', to: '2026-03-31' },
        sections: ['notes'],
        counts: { problems: 0, allergies: 0, medications: 0, vitals: 0, notes: 0, results: 0 },
        notes: [],
        results: [],
      }),
    );
    expect(screen.getByText('2026-03-01')).toBeTruthy();
    expect(screen.getByText('2026-03-31')).toBeTruthy();
    expect(screen.getByText(chartPrintText('en', 'leftOutEmpty'))).toBeTruthy();
  });

  it('keeps the section choice visible in the preview', () => {
    renderPreview(
      buildChartPrintPreview({
        patientName: syntheticChartPatient,
        scope: { kind: 'visit', visitId: 'visit-001' },
        sections: ['vitals'],
        counts: { problems: 2, allergies: 1, medications: 1, vitals: 3, notes: 0, results: 0 },
        notes: [],
        results: [],
      }),
    );
    expect(
      screen.getByRole('list', { name: chartPrintText('en', 'included') }).children,
    ).toHaveLength(1);
    expect(screen.getByText(`${chartPrintText('en', 'sectionVitals')}: 3`)).toBeTruthy();
    expect(screen.queryByText(chartPrintText('en', 'sectionProblems'))).toBeNull();
  });

  it('renders a signed report with its provenance', () => {
    renderPreview(buildSignedReportPreview(syntheticSignedReport));
    expect(screen.getByRole('heading', { name: chartPrintText('en', 'reportPrint') })).toBeTruthy();
    expect(screen.getByText('Synthetic signed report')).toBeTruthy();
    expect(screen.getByText(/synthetic\.clinician · 2026-03-09T12:00:00Z/)).toBeTruthy();
  });

  it('refuses a draft report in words', () => {
    renderPreview(buildSignedReportPreview(syntheticDraftReport));
    expect(screen.getByText(chartPrintText('en', 'notPrintable'))).toBeTruthy();
    const leftOut = screen.getByRole('list', { name: chartPrintText('en', 'leftOut') });
    expect(leftOut.textContent).toContain(chartPrintText('en', 'reasonUnsigned'));
  });

  it('translates the omitted items and reason into Arabic', () => {
    renderPreview(chartModel, 'ar');
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    const leftOut = screen.getByRole('list', { name: chartPrintText('ar', 'leftOut') });
    expect(leftOut.textContent).toContain(chartPrintText('ar', 'reasonUnsigned'));
    expect(leftOut.textContent).toContain(chartPrintText('ar', 'reasonWithheld'));
    expect(screen.getByText('ليلى تجريبية')).toBeTruthy();
  });
});

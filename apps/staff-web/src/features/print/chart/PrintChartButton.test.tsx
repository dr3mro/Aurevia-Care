import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { printText } from '../components/messages';
import type { PrintReason } from '../components/messages';
import { PrintChartButton } from './PrintChartButton';
import type { ChartPrintPreviewModel as PreviewModel, PrintChartTarget } from './PrintChartButton';
import type { ChartPrintVisit } from './domain';
import {
  syntheticChartPatient,
  syntheticCounts,
  syntheticDraftReport,
  syntheticNotes,
  syntheticResults,
  syntheticSignedReport,
  syntheticVisits,
} from './fixtures';
import { chartPrintText } from './labels';

const chartTarget: PrintChartTarget = {
  kind: 'chart',
  patientName: syntheticChartPatient,
  counts: syntheticCounts,
  notes: syntheticNotes,
  results: syntheticResults,
  visits: syntheticVisits,
};

function renderButton(
  target: PrintChartTarget,
  locale: Locale = 'en',
  visits?: readonly ChartPrintVisit[],
) {
  const onPrint = vi.fn<(model: PreviewModel, reason: PrintReason) => void>();
  const effectiveTarget: PrintChartTarget =
    target.kind === 'chart' && visits !== undefined ? { ...target, visits } : target;
  const ui: ReactElement = (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <PrintChartButton locale={locale} target={effectiveTarget} onPrint={onPrint} />
      </I18nProvider>
    </ThemeProvider>
  );
  render(ui);
  return { onPrint };
}

function trigger(locale: Locale, target: PrintChartTarget) {
  const label =
    target.kind === 'chart'
      ? chartPrintText(locale, 'chartPrint')
      : chartPrintText(locale, 'reportPrint');
  return screen.getByRole('button', { name: label });
}

describe('PrintChartButton', () => {
  it('opens a preview that lists unsigned and withheld content with its reasons', () => {
    renderButton(chartTarget);
    const button = trigger('en', chartTarget);
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('region', { name: chartPrintText('en', 'preview') })).toBeNull();
    fireEvent.click(button);
    expect(button.getAttribute('aria-expanded')).toBe('true');
    const panel = screen.getByRole('region', { name: chartPrintText('en', 'preview') });
    const leftOut = required(
      screen.getAllByRole('list', { name: chartPrintText('en', 'leftOut') })[0],
    );
    expect(leftOut.textContent).toContain('Synthetic unsigned draft note');
    expect(leftOut.textContent).toContain(chartPrintText('en', 'reasonUnsigned'));
    expect(leftOut.textContent).toContain('Synthetic withheld result');
    expect(leftOut.textContent).toContain(chartPrintText('en', 'reasonWithheld'));
    expect(panel.textContent).toContain(chartPrintText('en', 'syntheticNotice'));
  });

  it('persists section choices across a close and a re-preview', () => {
    renderButton(chartTarget);
    fireEvent.click(trigger('en', chartTarget));
    const problems = screen.getByRole('checkbox', {
      name: chartPrintText('en', 'sectionProblems'),
    });
    expect((problems as HTMLInputElement).checked).toBe(true);
    fireEvent.click(problems);
    expect((problems as HTMLInputElement).checked).toBe(false);
    expect(screen.queryByText(`${chartPrintText('en', 'sectionProblems')}: 2`)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: chartPrintText('en', 'close') }));
    expect(screen.queryByRole('region', { name: chartPrintText('en', 'preview') })).toBeNull();
    expect(document.activeElement).toBe(trigger('en', chartTarget));
    fireEvent.click(trigger('en', chartTarget));
    expect(
      screen.getByRole<HTMLInputElement>('checkbox', {
        name: chartPrintText('en', 'sectionProblems'),
      }).checked,
    ).toBe(false);
    expect(screen.queryByText(`${chartPrintText('en', 'sectionProblems')}: 2`)).toBeNull();
    fireEvent.click(
      screen.getByRole('checkbox', { name: chartPrintText('en', 'sectionProblems') }),
    );
    expect(screen.getByText(`${chartPrintText('en', 'sectionProblems')}: 2`)).toBeTruthy();
  });

  it('requires a reason before printing and then passes the preview with it', () => {
    const { onPrint } = renderButton(chartTarget);
    fireEvent.click(trigger('en', chartTarget));
    fireEvent.click(screen.getByRole('button', { name: chartPrintText('en', 'print') }));
    expect(onPrint).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toBe(printText('en', 'reasonRequired'));
    expect(document.activeElement).toBe(screen.getByRole('alert'));
    fireEvent.click(screen.getByRole('radio', { name: printText('en', 'reasonGiveToPatient') }));
    fireEvent.click(screen.getByRole('button', { name: chartPrintText('en', 'print') }));
    expect(onPrint).toHaveBeenCalledTimes(1);
    const [model, reason] = required(onPrint.mock.calls[0]);
    expect(reason).toBe('give-to-patient');
    expect(model.kind).toBe('chart');
    expect(model.leftOut.map((item) => item.reason)).toEqual(['unsigned', 'withheld']);
    expect(screen.queryByRole('region', { name: chartPrintText('en', 'preview') })).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('prints a signed report from its own screen through the same button', () => {
    const target: PrintChartTarget = { kind: 'report', report: syntheticSignedReport };
    const { onPrint } = renderButton(target);
    fireEvent.click(trigger('en', target));
    expect(screen.queryByRole('group', { name: chartPrintText('en', 'sections') })).toBeNull();
    expect(screen.getByText(/synthetic\.clinician · 2026-03-09T12:00:00Z/)).toBeTruthy();
    fireEvent.click(screen.getByRole('radio', { name: printText('en', 'reasonReferral') }));
    fireEvent.click(screen.getByRole('button', { name: chartPrintText('en', 'print') }));
    expect(onPrint).toHaveBeenCalledTimes(1);
    const [model, reason] = required(onPrint.mock.calls[0]);
    expect(reason).toBe('referral');
    if (model.kind !== 'report') {
      throw new Error('Expected a signed-report preview');
    }
    expect(model.printable).toBe(true);
  });

  it('shows an unsigned draft report as left out and offers no Print action', () => {
    const target: PrintChartTarget = { kind: 'report', report: syntheticDraftReport };
    const { onPrint } = renderButton(target);
    fireEvent.click(trigger('en', target));
    expect(screen.getByText(chartPrintText('en', 'notPrintable'))).toBeTruthy();
    const leftOut = required(
      screen.getAllByRole('list', { name: chartPrintText('en', 'leftOut') })[0],
    );
    expect(leftOut.textContent).toContain(chartPrintText('en', 'reasonUnsigned'));
    fireEvent.click(screen.getByRole('radio', { name: printText('en', 'reasonReferral') }));
    expect(screen.queryByRole('button', { name: chartPrintText('en', 'print') })).toBeNull();
    expect(onPrint).not.toHaveBeenCalled();
  });

  it('names the missing visit state when a chart has no visits', () => {
    renderButton(chartTarget, 'en', []);
    fireEvent.click(trigger('en', chartTarget));
    expect(screen.getByText(chartPrintText('en', 'noVisit'))).toBeTruthy();
  });

  it('keeps two instances on one page independent of each other', () => {
    const onPrint = vi.fn<(model: PreviewModel, reason: PrintReason) => void>();
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <PrintChartButton locale="en" target={chartTarget} onPrint={onPrint} />
          <PrintChartButton locale="en" target={chartTarget} onPrint={onPrint} />
        </I18nProvider>
      </ThemeProvider>,
    );
    const buttons = screen.getAllByRole('button', { name: chartPrintText('en', 'chartPrint') });
    expect(buttons).toHaveLength(2);
    fireEvent.click(required(buttons[0]));
    fireEvent.click(required(buttons[1]));
    const selectors = screen.getAllByLabelText<HTMLSelectElement>(chartPrintText('en', 'visitId'));
    expect(selectors).toHaveLength(2);
    expect(required(selectors[0]).id).not.toBe(required(selectors[1]).id);
    const firstReason = required(
      screen.getAllByRole('radio', { name: printText('en', 'reasonGiveToPatient') })[0],
    );
    const secondReason = required(
      screen.getAllByRole('radio', { name: printText('en', 'reasonReferral') })[1],
    );
    fireEvent.click(firstReason);
    fireEvent.click(secondReason);
    expect((firstReason as HTMLInputElement).checked).toBe(true);
    expect((secondReason as HTMLInputElement).checked).toBe(true);
    expect(
      (
        screen.getAllByRole('radio', {
          name: printText('en', 'reasonGiveToPatient'),
        })[1] as HTMLInputElement
      ).checked,
    ).toBe(false);
  });

  it('renders the button, the omissions and the reason in Arabic', () => {
    const { onPrint } = renderButton(chartTarget, 'ar');
    fireEvent.click(trigger('ar', chartTarget));
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    const leftOut = required(
      screen.getAllByRole('list', { name: chartPrintText('ar', 'leftOut') })[0],
    );
    expect(leftOut.textContent).toContain(chartPrintText('ar', 'reasonUnsigned'));
    expect(leftOut.textContent).toContain(chartPrintText('ar', 'reasonWithheld'));
    fireEvent.click(screen.getByRole('radio', { name: printText('ar', 'reasonReferral') }));
    fireEvent.click(screen.getByRole('button', { name: chartPrintText('ar', 'print') }));
    expect(onPrint).toHaveBeenCalledTimes(1);
    expect(required(onPrint.mock.calls[0])[1]).toBe('referral');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}

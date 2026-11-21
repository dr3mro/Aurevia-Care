import { MixedScriptText } from '../../../components/MixedScriptText';
import type { Locale } from '../../../i18n/messages';
import { useTheme } from '../../../theme/ThemeProvider';
import { chartPrintSections } from './domain';
import type { ChartPrintScope, ChartPrintSection, ChartPrintVisit } from './domain';
import { chartPrintText, chartSectionMessageKey } from './labels';

/** Bounded default range used when a clinician switches to a date range. */
export const DEFAULT_FROM = '2026-01-01';
export const DEFAULT_TO = '2026-12-31';

/** Props for the scope and section chooser used before a chart summary prints. */
export interface ChartSectionPickerProps {
  scope: ChartPrintScope;
  sections: readonly ChartPrintSection[];
  visits: readonly ChartPrintVisit[];
  locale: Locale;
  onScopeChange: (scope: ChartPrintScope) => void;
  onSectionToggle: (section: ChartPrintSection) => void;
}

/**
 * Controlled visit/date-range and section chooser. It owns no clinical state:
 * the owning button holds the choices so they survive a preview close, and all
 * labels come from the bilingual feature dictionary.
 */
export function ChartSectionPicker({
  scope,
  sections,
  visits,
  locale,
  onScopeChange,
  onSectionToggle,
}: ChartSectionPickerProps) {
  const { tokens } = useTheme();
  return (
    <section style={{ display: 'grid', gap: tokens.spacing.md }}>
      <fieldset style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <legend>{chartPrintText(locale, 'scope')}</legend>
        <label>
          <input
            type="radio"
            name="chart-print-scope"
            value="visit"
            checked={scope.kind === 'visit'}
            onChange={() =>
              onScopeChange({ kind: 'visit', visitId: visits[0]?.id ?? '' })
            }
          />{' '}
          {chartPrintText(locale, 'visit')}
        </label>
        <label>
          <input
            type="radio"
            name="chart-print-scope"
            value="range"
            checked={scope.kind === 'range'}
            onChange={() => onScopeChange({ kind: 'range', from: DEFAULT_FROM, to: DEFAULT_TO })}
          />{' '}
          {chartPrintText(locale, 'dateRange')}
        </label>
      </fieldset>
      {scope.kind === 'visit' ? (
        visits.length === 0 ? (
          <p>{chartPrintText(locale, 'noVisit')}</p>
        ) : (
          <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
            <label htmlFor="chart-print-visit">{chartPrintText(locale, 'visitId')}</label>
            <select
              id="chart-print-visit"
              value={scope.visitId}
              onChange={(event) =>
                onScopeChange({ kind: 'visit', visitId: event.target.value })
              }
            >
              {visits.map((visit) => (
                <option key={visit.id} value={visit.id}>
                  {visit.label}
                </option>
              ))}
            </select>
          </div>
        )
      ) : (
        <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
          <label htmlFor="chart-print-from">{chartPrintText(locale, 'from')}</label>
          <input
            id="chart-print-from"
            type="date"
            value={scope.from}
            onChange={(event) =>
              onScopeChange({ kind: 'range', from: event.target.value, to: scope.to })
            }
          />
          <label htmlFor="chart-print-to">{chartPrintText(locale, 'to')}</label>
          <input
            id="chart-print-to"
            type="date"
            value={scope.to}
            onChange={(event) =>
              onScopeChange({ kind: 'range', from: scope.from, to: event.target.value })
            }
          />
        </div>
      )}
      <fieldset aria-label={chartPrintText(locale, 'sections')}>
        <legend>{chartPrintText(locale, 'sections')}</legend>
        {chartPrintSections.map((section) => (
          <label key={section} style={{ display: 'block' }}>
            <input
              type="checkbox"
              checked={sections.includes(section)}
              onChange={() => onSectionToggle(section)}
            />{' '}
            <MixedScriptText text={chartPrintText(locale, chartSectionMessageKey[section])} lang={locale} />
          </label>
        ))}
      </fieldset>
    </section>
  );
}

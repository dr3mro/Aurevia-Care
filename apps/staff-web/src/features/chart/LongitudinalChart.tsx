import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName, formatInstant, formatMeasurement } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { CodedEntry, Chart } from './domain';
import { chartText, verificationMessageKey } from './messages';

/** Props for the longitudinal problems/allergies/medications/vitals view. */
export interface LongitudinalChartProps {
  chart: Chart;
  locale: Locale;
}

/** Render one captioned table of coded chart entries (CLN-03). */
function CodedEntryTable({
  caption,
  entries,
  locale,
  emptyLabel,
}: {
  caption: string;
  entries: readonly CodedEntry[];
  locale: Locale;
  emptyLabel: string;
}) {
  const { tokens } = useTheme();
  const headers = [
    chartText(locale, 'code'),
    chartText(locale, 'originalText'),
    chartText(locale, 'display'),
    chartText(locale, 'verification'),
    chartText(locale, 'source'),
    chartText(locale, 'effectiveOn'),
    chartText(locale, 'clinicalAuthor'),
    chartText(locale, 'enteredBy'),
  ];
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>{caption}</caption>
      <thead>
        <tr>
          {headers.map((header) => (
            <th key={header} scope="col" style={{ border: `1px solid ${tokens.border}` }}>
              {header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {entries.length === 0 ? (
          <tr>
            <td colSpan={headers.length}>{emptyLabel}</td>
          </tr>
        ) : (
          entries.map((entry) => {
            const key = `${entry.organizationId}:${entry.id}`;
            const shown = locale === 'ar' && entry.displayLocal ? entry.displayLocal : entry.display;
            return (
              <tr key={key}>
                <th scope="row">{entry.code}</th>
                <td>{entry.originalText}</td>
                <td>
                  <MixedScriptText text={shown} lang={locale} />
                </td>
                <td>
                  <MixedScriptText
                    text={chartText(locale, verificationMessageKey[entry.verification])}
                    lang={locale}
                  />
                </td>
                <td>{entry.source}</td>
                <td>
                  <time dateTime={entry.effectiveOn}>{entry.effectiveOn}</time>
                </td>
                <td>{entry.clinicalAuthor}</td>
                <td>{entry.enteredBy}</td>
              </tr>
            );
          })
        )}
      </tbody>
    </table>
  );
}

/** Render a tenant-scoped chart with coded entries, units and effective dates. */
export function LongitudinalChart({ chart, locale }: LongitudinalChartProps) {
  const { tokens } = useTheme();
  const empty = chartText(locale, 'noEntries');
  return (
    <section
      aria-label={chartText(locale, 'longitudinalChart')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h3>{chartText(locale, 'longitudinalChart')}</h3>
      <p>
        <MixedScriptText text={displayLocalName(chart.patientName, locale)} lang={locale} />
      </p>
      <CodedEntryTable
        caption={chartText(locale, 'problems')}
        entries={chart.problems}
        locale={locale}
        emptyLabel={empty}
      />
      <CodedEntryTable
        caption={chartText(locale, 'allergies')}
        entries={chart.allergies}
        locale={locale}
        emptyLabel={empty}
      />
      <CodedEntryTable
        caption={chartText(locale, 'medications')}
        entries={chart.medications}
        locale={locale}
        emptyLabel={empty}
      />
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
          {chartText(locale, 'vitals')}
        </caption>
        <thead>
          <tr>
            <th scope="col">{chartText(locale, 'kind')}</th>
            <th scope="col">{chartText(locale, 'measurement')}</th>
            <th scope="col">{chartText(locale, 'measuredAt')}</th>
          </tr>
        </thead>
        <tbody>
          {chart.vitals.length === 0 ? (
            <tr>
              <td colSpan={3}>{empty}</td>
            </tr>
          ) : (
            chart.vitals.map((vital) => (
              <tr key={`${vital.organizationId}:${vital.id}`}>
                <th scope="row">{vital.kind}</th>
                <td>{formatMeasurement(vital.value, vital.unit, locale)}</td>
                <td>
                  <time dateTime={vital.measuredAt}>
                    {formatInstant(vital.measuredAt, vital.timeZone, locale)}
                  </time>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </section>
  );
}

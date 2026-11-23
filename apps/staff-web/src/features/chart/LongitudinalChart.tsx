import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName, formatInstant, formatMeasurement } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { Breakpoint } from '../../theme/breakpoints';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type { CodedEntry, Chart, Vital } from './domain';
import { chartText, verificationMessageKey } from './messages';

/** Keyboard-focusable scroll regions join the natural tab order. */
const FOCUSABLE_TAB_INDEX = 0;

/** Props for the longitudinal problems/allergies/medications/vitals view. */
export interface LongitudinalChartProps {
  chart: Chart;
  locale: Locale;
}

/** Logical wrapping for a clinical value: long English and Arabic text wraps. */
const WRAPPING_STYLE = {
  minInlineSize: 0,
  overflowWrap: 'anywhere',
  whiteSpace: 'normal',
} as const;

/**
 * Keep a column label in the accessibility tree without adding visible text.
 * The compact card view is a description list, so each value keeps the same
 * `kind` / `measurement` / `measuredAt` name a table column header provides.
 */
const LABEL_STYLE = {
  position: 'absolute',
  inlineSize: 1,
  blockSize: 1,
  overflow: 'hidden',
  clipPath: 'inset(50%)',
  whiteSpace: 'nowrap',
} as const;

/**
 * Render one captioned table of coded chart entries (CLN-03).
 *
 * The table, caption and every `<th scope="col">` are identical at every
 * breakpoint, so no clinical column is added, removed or hidden. On compact
 * viewports the table is wrapped in the shared `.table-region` class as a
 * labelled, keyboard-scrollable region; every column stays reachable without a
 * page-level horizontal scroll (WEB-07, UX-01). Cells use logical wrapping so a
 * long value never clips or forces the region wider than the viewport.
 */
export function CodedEntryTable({
  caption,
  entries,
  locale,
  emptyLabel,
  breakpoint,
}: {
  caption: string;
  entries: readonly CodedEntry[];
  locale: Locale;
  emptyLabel: string;
  breakpoint: Breakpoint;
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
  const border = `1px solid ${tokens.border}`;
  const table = (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>{caption}</caption>
      <thead>
        <tr>
          {headers.map((header) => (
            <th key={header} scope="col" style={{ ...WRAPPING_STYLE, border }}>
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
            const localDisplay = entry.displayLocal ?? '';
            const shown = locale === 'ar' && localDisplay !== '' ? localDisplay : entry.display;
            return (
              <tr key={key}>
                <th scope="row" style={{ ...WRAPPING_STYLE, border }}>
                  {entry.code}
                </th>
                <td style={{ ...WRAPPING_STYLE, border }}>{entry.originalText}</td>
                <td style={{ ...WRAPPING_STYLE, border }}>
                  <MixedScriptText text={shown} lang={locale} />
                </td>
                <td style={{ ...WRAPPING_STYLE, border }}>
                  <MixedScriptText
                    text={chartText(locale, verificationMessageKey[entry.verification])}
                    lang={locale}
                  />
                </td>
                <td style={{ ...WRAPPING_STYLE, border }}>{entry.source}</td>
                <td style={{ ...WRAPPING_STYLE, border }}>
                  <time dateTime={entry.effectiveOn}>{entry.effectiveOn}</time>
                </td>
                <td style={{ ...WRAPPING_STYLE, border }}>{entry.clinicalAuthor}</td>
                <td style={{ ...WRAPPING_STYLE, border }}>{entry.enteredBy}</td>
              </tr>
            );
          })
        )}
      </tbody>
    </table>
  );

  // Compact: a labelled scroll region keeps the full table reachable with no
  // clinical column hidden. The label names the region for keyboard and screen
  // reader users; tabIndex makes the overflow scrollable from the keyboard.
  if (breakpoint === 'compact') {
    return (
      <div
        className="table-region"
        role="region"
        aria-label={caption}
        tabIndex={FOCUSABLE_TAB_INDEX}
        data-part="coded-entries"
        data-breakpoint={breakpoint}
      >
        {table}
      </div>
    );
  }

  return table;
}

/**
 * Render the vitals timeline as the measured-at table from 768 px upward and as
 * a vertical single-column card list on phones (WEB-07, UX-01).
 *
 * The card view keeps every clinical value and its unit; the measurement date
 * is a block-level `<time>` on its own line beneath the observation. A
 * description list keeps the three column names in the accessibility tree so a
 * screen reader still identifies each value at phone width. DOM order — and
 * therefore chronological order — is unchanged, so right-to-left mirrors
 * presentation without reversing the data.
 */
export function VitalTimeline({
  vitals,
  locale,
  emptyLabel,
  breakpoint,
}: {
  vitals: readonly Vital[];
  locale: Locale;
  emptyLabel: string;
  breakpoint: Breakpoint;
}) {
  const { tokens } = useTheme();
  const caption = chartText(locale, 'vitals');
  const border = `1px solid ${tokens.border}`;
  const table = (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>{caption}</caption>
      <thead>
        <tr>
          <th scope="col" style={{ ...WRAPPING_STYLE, border }}>
            {chartText(locale, 'kind')}
          </th>
          <th scope="col" style={{ ...WRAPPING_STYLE, border }}>
            {chartText(locale, 'measurement')}
          </th>
          <th scope="col" style={{ ...WRAPPING_STYLE, border }}>
            {chartText(locale, 'measuredAt')}
          </th>
        </tr>
      </thead>
      <tbody>
        {vitals.length === 0 ? (
          <tr>
            <td colSpan={3}>{emptyLabel}</td>
          </tr>
        ) : (
          vitals.map((vital) => (
            <tr key={`${vital.organizationId}:${vital.id}`}>
              <th scope="row" style={{ ...WRAPPING_STYLE, border }}>
                {vital.kind}
              </th>
              <td style={{ ...WRAPPING_STYLE, border }}>
                {formatMeasurement(vital.value, vital.unit, locale)}
              </td>
              <td style={{ ...WRAPPING_STYLE, border }}>
                <time dateTime={vital.measuredAt}>
                  {formatInstant(vital.measuredAt, vital.timeZone, locale)}
                </time>
              </td>
            </tr>
          ))
        )}
      </tbody>
    </table>
  );

  if (breakpoint !== 'compact') {
    return table;
  }

  return (
    <div data-part="vitals-timeline" data-breakpoint={breakpoint}>
      <p style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm, ...WRAPPING_STYLE }}>
        {caption}
      </p>
      <div className="card-list" role="list" aria-label={caption} data-layout="single-column">
        {vitals.length === 0 ? (
          <div role="listitem">{emptyLabel}</div>
        ) : (
          vitals.map((vital) => (
            <div
              key={`${vital.organizationId}:${vital.id}`}
              role="listitem"
              style={{ display: 'grid', gap: tokens.spacing.sm }}
            >
              <dl style={{ display: 'grid', gap: tokens.spacing.sm, margin: 0 }}>
                <dt style={LABEL_STYLE}>{chartText(locale, 'kind')}</dt>
                <dd style={{ ...WRAPPING_STYLE, margin: 0 }}>{vital.kind}</dd>
                <dt style={LABEL_STYLE}>{chartText(locale, 'measurement')}</dt>
                <dd style={{ ...WRAPPING_STYLE, margin: 0 }}>
                  {formatMeasurement(vital.value, vital.unit, locale)}
                </dd>
                <dt style={LABEL_STYLE}>{chartText(locale, 'measuredAt')}</dt>
                <dd style={{ display: 'block', ...WRAPPING_STYLE, margin: 0 }}>
                  <time dateTime={vital.measuredAt} style={{ display: 'block', ...WRAPPING_STYLE }}>
                    {formatInstant(vital.measuredAt, vital.timeZone, locale)}
                  </time>
                </dd>
              </dl>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

/** Render a tenant-scoped chart with coded entries, units and effective dates. */
export function LongitudinalChart({ chart, locale }: LongitudinalChartProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const empty = chartText(locale, 'noEntries');
  return (
    <section
      aria-label={chartText(locale, 'longitudinalChart')}
      data-breakpoint={breakpoint}
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
        breakpoint={breakpoint}
      />
      <CodedEntryTable
        caption={chartText(locale, 'allergies')}
        entries={chart.allergies}
        locale={locale}
        emptyLabel={empty}
        breakpoint={breakpoint}
      />
      <CodedEntryTable
        caption={chartText(locale, 'medications')}
        entries={chart.medications}
        locale={locale}
        emptyLabel={empty}
        breakpoint={breakpoint}
      />
      <VitalTimeline
        vitals={chart.vitals}
        locale={locale}
        emptyLabel={empty}
        breakpoint={breakpoint}
      />
    </section>
  );
}

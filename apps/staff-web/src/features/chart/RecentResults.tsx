import { formatCivilDate, formatMeasurement } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { RecentResult } from './domain';
import { chartText } from './messages';

/** Props for the recent-results card (CLN-03, WEB-09). */
export interface RecentResultsProps {
  results: readonly RecentResult[];
  locale: Locale;
}

/**
 * Show each observation with its source unit, reference range, civil date and
 * a flag word. The flag is never colour-only: a distinct word and a token
 * boundary carry it, so forced colours and monochrome output stay readable.
 */
export function RecentResults({ results, locale }: RecentResultsProps) {
  const { tokens } = useTheme();
  return (
    <section
      className="page"
      aria-label={chartText(locale, 'recentResults')}
      data-part="recent-results"
      style={{ display: 'grid', gap: tokens.spacing.md, minInlineSize: 0, overflowWrap: 'anywhere' }}
    >
      <h3 style={{ marginBlock: 0 }}>{chartText(locale, 'recentResults')}</h3>
      {results.length === 0 ? (
        <p>{chartText(locale, 'noEntries')}</p>
      ) : (
        <ul
          style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: tokens.spacing.sm }}
        >
          {results.map((result) => (
            <li
              key={`${result.organizationId}:${result.id}`}
              data-part="recent-result"
              style={{
                display: 'grid',
                gap: tokens.spacing.sm,
                paddingBlock: tokens.spacing.sm,
                borderBlockEnd: `1px solid ${tokens.border}`,
                minInlineSize: 0,
                overflowWrap: 'anywhere',
              }}
            >
              <span>{result.test}</span>
              <span style={{ fontSize: 'var(--sc-font-size-numeric-lg, 1.5rem)' }}>
                {formatMeasurement(result.value, result.unit, locale)}
              </span>
              <span>
                {chartText(locale, 'referenceRange')}{' '}
                {formatMeasurement(result.referenceLow, result.unit, locale)}–
                {formatMeasurement(result.referenceHigh, result.unit, locale)} ·{' '}
                <time dateTime={result.observedOn}>{formatCivilDate(result.observedOn, locale)}</time>
              </span>
              <span
                role="note"
                data-flag={result.flag}
                style={{
                  borderInlineStart: `3px solid ${tokens.border}`,
                  paddingInlineStart: tokens.spacing.sm,
                  color: tokens.foreground,
                }}
              >
                {chartText(locale, result.flag === 'high' ? 'flagHigh' : 'flagNormal')}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

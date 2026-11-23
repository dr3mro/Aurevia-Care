import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type { Chart } from './domain';
import { CodedEntryTable, VitalTimeline } from './LongitudinalChart';
import { chartText } from './messages';

/** Props for the card-styled clinical overview (CLN-03, WEB-07). */
export interface OverviewCardsProps {
  chart: Chart;
  locale: Locale;
}

/**
 * Compose the Problems, Allergies, Medications and Vitals cards from the same
 * accessible tables the longitudinal chart uses. The tables and their full
 * column set stay in the DOM at every breakpoint; only the surrounding card
 * chrome is added. No clinical column is removed to fit a card (WCAG 1.4.10).
 */
export function OverviewCards({ chart, locale }: OverviewCardsProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const empty = chartText(locale, 'noEntries');
  return (
    <section
      className="card-list"
      aria-label={chartText(locale, 'overviewCards')}
      data-part="overview-cards"
      data-breakpoint={breakpoint}
      style={{ minInlineSize: 0, overflowWrap: 'anywhere', gap: tokens.spacing.md }}
    >
      <section data-part="problems-card" style={{ minInlineSize: 0, overflowWrap: 'anywhere' }}>
        <CodedEntryTable
          caption={chartText(locale, 'problems')}
          entries={chart.problems}
          locale={locale}
          emptyLabel={empty}
          breakpoint={breakpoint}
        />
      </section>
      <section data-part="allergies-card" style={{ minInlineSize: 0, overflowWrap: 'anywhere' }}>
        <CodedEntryTable
          caption={chartText(locale, 'allergies')}
          entries={chart.allergies}
          locale={locale}
          emptyLabel={empty}
          breakpoint={breakpoint}
        />
      </section>
      <section data-part="medications-card" style={{ minInlineSize: 0, overflowWrap: 'anywhere' }}>
        <CodedEntryTable
          caption={chartText(locale, 'medications')}
          entries={chart.medications}
          locale={locale}
          emptyLabel={empty}
          breakpoint={breakpoint}
        />
      </section>
      <section data-part="vitals-card" style={{ minInlineSize: 0, overflowWrap: 'anywhere' }}>
        <VitalTimeline
          vitals={chart.vitals}
          locale={locale}
          emptyLabel={empty}
          breakpoint={breakpoint}
        />
      </section>
    </section>
  );
}

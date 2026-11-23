import { useState } from 'react';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { ChartMessageKey } from './messages';
import { chartText } from './messages';

/** The six chart sections shown in the approved design, in reading order. */
const CHART_TABS: readonly ChartMessageKey[] = [
  'overview',
  'notes',
  'orders',
  'results',
  'documents',
  'messages',
];

/**
 * Present the chart section row. Only the Overview body is implemented in this
 * synthetic preview, but the selection is real local UI state so a keyboard
 * user can move the current marker; no clinical data changes with the tab.
 */
export function ChartTabs({ locale }: { locale: Locale }) {
  const { tokens } = useTheme();
  const [active, setActive] = useState<ChartMessageKey>('overview');
  return (
    <nav
      className="page"
      aria-label={chartText(locale, 'chartTabs')}
      data-part="chart-tabs"
      style={{
        maxInlineSize: 'none',
        display: 'flex',
        flexWrap: 'wrap',
        gap: tokens.spacing.md,
        background: tokens.background,
        color: tokens.foreground,
      }}
    >
      {CHART_TABS.map((key) => {
        const current = active === key;
        return (
          <button
            key={key}
            type="button"
            className="touch"
            aria-current={current ? 'page' : undefined}
            onClick={() => setActive(key)}
            style={{
              border: 'none',
              borderBlockEnd: current ? `3px solid ${tokens.accent}` : `1px solid ${tokens.border}`,
              background: tokens.background,
              color: current ? tokens.accent : tokens.foreground,
              paddingBlock: tokens.spacing.sm,
              paddingInline: tokens.spacing.md,
              cursor: 'pointer',
              textAlign: 'start',
            }}
          >
            {chartText(locale, key)}
          </button>
        );
      })}
    </nav>
  );
}

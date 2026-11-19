import { MixedScriptText } from '../../components/MixedScriptText';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { VisitState } from './domain';
import { schedulingText, visitStateMessageKey } from './messages';

/** Distinct glyphs keep the state legible without relying on colour alone. */
const stateGlyph: Record<VisitState, string> = {
  booked: '○',
  'checked-in': '◐',
  'in-progress': '◑',
  completed: '●',
  cancelled: '×',
  'no-show': '—',
};

/** Props for a bilingual, shape-distinct visit-state badge. */
export interface VisitStateBadgeProps {
  state: VisitState;
  locale: Locale;
}

/** Show the translated state word beside a fixed glyph with no live region. */
export function VisitStateBadge({ state, locale }: VisitStateBadgeProps) {
  const { tokens } = useTheme();
  const label = schedulingText(locale, visitStateMessageKey[state]);
  return (
    <span
      data-state={state}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: tokens.spacing.sm,
        paddingInline: tokens.spacing.sm,
        border: `1px solid ${tokens.border}`,
        borderRadius: tokens.spacing.sm,
      }}
    >
      <span data-badge-icon="true" aria-hidden="true">
        {stateGlyph[state]}
      </span>
      <MixedScriptText text={label} lang={locale} />
    </span>
  );
}

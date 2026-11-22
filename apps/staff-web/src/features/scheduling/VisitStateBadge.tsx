import { MixedScriptText } from '../../components/MixedScriptText';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
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

/**
 * Show the translated state word beside a fixed glyph with no live region.
 *
 * Width is a layout input only and never changes the state, its word or its
 * shape: the badge keeps one DOM and accessibility tree at every breakpoint.
 * The glyph and label wrap instead of clipping or forcing a page-level
 * horizontal scroll, and no fixed block size or font size is set so the text
 * stays readable at 200 percent zoom. Every side-aware declaration is logical
 * (`paddingInline`, `minInlineSize`), so the badge mirrors under right-to-left
 * without a second rule set. `responsive.css` has no dedicated badge class, so
 * the wrapping uses logical inline styles; the active band is exposed as
 * `data-breakpoint` for layout checks (WEB-07, UX-01).
 */
export function VisitStateBadge({ state, locale }: VisitStateBadgeProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const label = schedulingText(locale, visitStateMessageKey[state]);
  return (
    <span
      data-state={state}
      data-breakpoint={breakpoint}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        flexWrap: 'wrap',
        minInlineSize: 0,
        maxInlineSize: '100%',
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

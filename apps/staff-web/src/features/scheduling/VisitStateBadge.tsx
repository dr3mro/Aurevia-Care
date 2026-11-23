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

/**
 * Token-backed tint for each state. The values are named design tokens from
 * the status palette, not literal colours; `data-tone` exposes the semantic
 * choice to tests and to assistive tooling.
 */
const stateTint: Record<VisitState, string> = {
  booked: 'info',
  'checked-in': 'success',
  'in-progress': 'warning',
  completed: 'neutral',
  cancelled: 'neutral',
  'no-show': 'critical',
};

/**
 * Resolve the surface token for a tint. A missing theme variable falls back to
 * the surface token already supplied by the active theme, so the badge never
 * invents its own palette (WEB-08).
 */
function tintSurface(tone: string, background: string): string {
  if (tone === 'neutral') {
    return background;
  }
  return `var(--sc-color-status-${tone}-surface, ${background})`;
}

/** Props for a bilingual, shape-distinct visit-state badge. */
export interface VisitStateBadgeProps {
  state: VisitState;
  locale: Locale;
}

/**
 * Show the translated state word beside a fixed glyph and a tint dot.
 *
 * The word and the distinct glyph are always present, so colour is never the
 * only signal. Width is a layout input only and never changes the state, its
 * word or its shape: the badge keeps one DOM and accessibility tree at every
 * breakpoint. Long labels wrap instead of clipping, no fixed block or font
 * size is set, and every side-aware declaration is logical so the badge
 * mirrors under right-to-left (WEB-07, UX-01).
 */
export function VisitStateBadge({ state, locale }: VisitStateBadgeProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const label = schedulingText(locale, visitStateMessageKey[state]);
  const tone = stateTint[state];
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: tokens.spacing.sm,
        minInlineSize: 0,
        maxInlineSize: '100%',
      }}
    >
      <span data-badge-dot="true" data-tone={tone} aria-hidden="true">
        •
      </span>
      <span
        data-state={state}
        data-tone={tone}
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
          borderRadius: '9999px',
          background: tintSurface(tone, tokens.background),
          color: tokens.foreground,
        }}
      >
        <span data-badge-icon="true" aria-hidden="true">
          {stateGlyph[state]}
        </span>
        <MixedScriptText text={label} lang={locale} />
      </span>
    </span>
  );
}

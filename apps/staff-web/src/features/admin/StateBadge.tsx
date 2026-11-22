import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';

/** Props for a compact, shape-distinct status badge. */
export interface StateBadgeProps {
  glyph: string;
  label: string;
  tone: string;
  testId?: string;
}

/**
 * Show a translated state beside a decorative, shape-distinct glyph.
 *
 * Width is a layout input only: the word and glyph are identical at every
 * breakpoint (WEB-07, UX-01). The stylesheet's `.cluster` rule supplies flex
 * wrapping and the child `min-inline-size: 0` wrapping rule, so a long English
 * or Arabic label wraps instead of clipping or forcing a page-level horizontal
 * scroll; the inline gap preserves the badge's original spacing. The active band
 * is exposed as `data-breakpoint` for layout checks. No clinical value is hidden
 * at any width.
 */
export function StateBadge({ glyph, label, tone, testId }: StateBadgeProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  // Keep the original presentation and gap inline; .cluster adds wrapping, the
  // logical gap fallback and the child min-size rule, not a new spacing value.
  return (
    <span
      className="cluster"
      data-state={tone}
      data-breakpoint={breakpoint}
      data-testid={testId}
      style={{ display: 'inline-flex', alignItems: 'center', gap: tokens.spacing.sm }}
    >
      <span aria-hidden="true">{glyph}</span>
      <MixedScriptText text={label} lang={locale} />
    </span>
  );
}

import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type { AccessState } from './domain';
import { accessStateMessageKey, adminText } from './messages';
import { StateBadge } from './StateBadge';

/** Distinct glyphs keep the three access states legible without colour. */
const stateGlyph: Record<AccessState, string> = {
  permitted: '✓',
  denied: '×',
  concealed: '—',
};

/** Props for one fixture-driven access review result. */
export interface AccessStateNoticeProps {
  state: AccessState;
  label: string;
}

/**
 * Render a denial as an alert and permitted/concealed states as status.
 *
 * Only presentation changes across widths: the badge and reviewed label stack
 * on a phone and sit inline from the medium band up, while the role, accessible
 * text and state value stay identical at every breakpoint (WEB-07, UX-01).
 * The shared `stack`/`cluster` utilities give each child a zero inline minimum
 * and `overflow-wrap: anywhere`, so long English and Arabic values wrap instead
 * of forcing a horizontal page scroll. `responsive.css` has no notice-surface
 * class, so safe-area-aware logical padding is applied inline; no clinical value
 * is hidden and no fixed block size caps the text at 200 percent zoom.
 */
export function AccessStateNotice({ state, label }: AccessStateNoticeProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const message = adminText(locale, accessStateMessageKey[state]);
  return (
    <div
      data-access-state={state}
      data-breakpoint={breakpoint}
      role={state === 'denied' ? 'alert' : 'status'}
      // Stack the two fields on compact; keep them inline from medium upward.
      className={breakpoint === 'compact' ? 'stack' : 'cluster'}
      style={{
        minInlineSize: 0,
        overflowWrap: 'anywhere',
        gap: tokens.spacing.sm,
        border: `1px solid ${tokens.border}`,
        // Logical padding floored by the safe-area insets mirrors under RTL and
        // keeps the region clear of notches; no physical side is ever named.
        paddingBlockStart: `max(${tokens.spacing.sm}, var(--safe-block-start, 0px))`,
        paddingBlockEnd: `max(${tokens.spacing.sm}, var(--safe-block-end, 0px))`,
        paddingInlineStart: `max(${tokens.spacing.sm}, var(--safe-inline-start, 0px))`,
        paddingInlineEnd: `max(${tokens.spacing.sm}, var(--safe-inline-end, 0px))`,
      }}
    >
      <StateBadge glyph={stateGlyph[state]} label={message} tone={state} />
      <MixedScriptText text={label} lang="en" />
    </div>
  );
}

import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';

/** Props for a compact, shape-distinct status badge. */
export interface StateBadgeProps {
  glyph: string;
  label: string;
  tone: string;
  testId?: string;
}

/** Show a translated state beside a decorative glyph; colour is not the only cue. */
export function StateBadge({ glyph, label, tone, testId }: StateBadgeProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <span
      data-state={tone}
      data-testid={testId}
      style={{ display: 'inline-flex', alignItems: 'center', gap: tokens.spacing.sm }}
    >
      <span aria-hidden="true">{glyph}</span>
      <MixedScriptText text={label} lang={locale} />
    </span>
  );
}

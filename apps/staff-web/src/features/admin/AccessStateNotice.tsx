import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
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

/** Render a denial as an alert and permitted/concealed states as status. */
export function AccessStateNotice({ state, label }: AccessStateNoticeProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const message = adminText(locale, accessStateMessageKey[state]);
  return (
    <div
      data-access-state={state}
      role={state === 'denied' ? 'alert' : 'status'}
      style={{
        display: 'grid',
        gap: tokens.spacing.sm,
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.sm,
      }}
    >
      <StateBadge glyph={stateGlyph[state]} label={message} tone={state} />
      <MixedScriptText text={label} lang="en" />
    </div>
  );
}

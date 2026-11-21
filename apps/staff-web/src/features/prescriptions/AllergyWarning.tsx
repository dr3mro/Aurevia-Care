import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { AllergyFinding } from './domain';
import { prescriptionText } from './messages';

/** Props for the ingredient-level allergy review banner. */
export interface AllergyWarningProps {
  findings: readonly AllergyFinding[];
}

/**
 * Surface every matched or unverified ingredient as a word and a distinct
 * shape. Colour is never the only signal, and the banner is in flow so it
 * cannot be dismissed by scrolling past it.
 */
export function AllergyWarning({ findings }: AllergyWarningProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  if (findings.length === 0) {
    return null;
  }
  return (
    <section
      role="status"
      data-testid="allergy-warning"
      aria-label={prescriptionText(locale, 'allergyWarning')}
      style={{ border: `2px solid ${tokens.border}`, padding: tokens.spacing.md }}
    >
      <h3>{prescriptionText(locale, 'allergyWarning')}</h3>
      <ul style={{ display: 'grid', gap: tokens.spacing.sm }}>
        {findings.map((finding) => (
          <li key={finding.ingredient} data-kind={finding.kind}>
            <span data-allergy-icon="true" aria-hidden="true">
              {finding.kind === 'match' ? '!' : '?'}
            </span>{' '}
            <MixedScriptText text={finding.ingredient} lang="en" />{' — '}
            <span>
              {prescriptionText(
                locale,
                finding.kind === 'match' ? 'allergyMatch' : 'allergyUnknown',
              )}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

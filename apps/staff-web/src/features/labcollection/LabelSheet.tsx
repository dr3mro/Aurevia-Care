import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { LabelData } from './domain';
import { labelRows } from './domain';
import { labText, labelFieldMessageKey } from './messages';

/** Props for the printable specimen label sheet. */
export interface LabelSheetProps {
  label: LabelData;
  organizationId: string;
  printedBy: string;
  printedAt: string;
}

/**
 * Render a print-ready label sheet. Every required field comes from `labelRows`
 * so the sheet and its test cannot drift, and identifiers stay in isolated
 * left-to-right runs regardless of the interface direction.
 */
export function LabelSheet({ label, organizationId, printedBy, printedAt }: LabelSheetProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();

  return (
    <section
      aria-label={labText(locale, 'labelSheet')}
      data-testid="label-sheet"
      style={{ display: 'grid', gap: tokens.spacing.sm }}
    >
      <h3>{labText(locale, 'labelSheet')}</h3>
      <dl style={{ display: 'grid', gap: tokens.spacing.sm }}>
        {labelRows(label).map((row) => (
          <div key={row.key}>
            <dt>{labText(locale, labelFieldMessageKey[row.key])}</dt>
            <dd>
              <MixedScriptText
                text={
                  row.key === 'collectedAt' ? formatInstant(row.value, 'UTC', locale) : row.value
                }
                lang="en"
              />
            </dd>
          </div>
        ))}
        <div>
          <dt>{labText(locale, 'organization')}</dt>
          <dd>
            <MixedScriptText text={organizationId} lang="en" />
          </dd>
        </div>
        <div>
          <dt>{labText(locale, 'printedBy')}</dt>
          <dd>
            <MixedScriptText text={printedBy} lang="en" />
          </dd>
        </div>
        <div>
          <dt>{labText(locale, 'printedAt')}</dt>
          <dd>
            <MixedScriptText text={printedAt} lang="en" />
          </dd>
        </div>
      </dl>
    </section>
  );
}

import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { annotatedName, formatCivilDate } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { canLink } from './domain';
import { differingMessageKeys, resultsText } from './resultsMessages';
import type { ReconciliationCase, ReconciliationIdentity } from './types';

/** Props for the reconciliation card. */
export interface ReconciliationPanelProps {
  reconciliation: ReconciliationCase | undefined;
  locale: Locale;
  onAskLab: () => void;
  onAssignOwner: () => void;
  onLink: () => void;
}

/** Render one labelled identity line without a link affordance. */
function IdentityLine({
  label,
  identity,
  locale,
}: {
  label: string;
  identity: ReconciliationIdentity;
  locale: Locale;
}) {
  const nameRun = annotatedName(identity.patient, locale);
  // When identity matters, the Arabic interface shows both the local form and
  // the Latin form: two records can share one local spelling while their Latin
  // identifiers differ, which is exactly the mismatch this panel explains.
  const showLatin = nameRun.lang === 'ar' && identity.patient.localName !== undefined;
  return (
    <p>
      <span>{label}</span>{' '}
      <MixedScriptText text={nameRun.text} lang={nameRun.lang} />
      {showLatin ? (
        <>
          {' ('}
          <MixedScriptText text={identity.patient.latinName} lang="en" />
          {')'}
        </>
      ) : null}{' '}
      {resultsText(locale, 'dobLabel')} {formatCivilDate(identity.dateOfBirth, locale)}{' '}
      <code dir="ltr">{identity.accession}</code>
    </p>
  );
}

/**
 * Show a report beside its closest order and keep linking gated.
 *
 * A mismatch is written as a warning word and never offers a link control;
 * asking the lab and assigning an owner remain available because they are the
 * safe next steps (XCH-04).
 */
export function ReconciliationPanel({
  reconciliation,
  locale,
  onAskLab,
  onAssignOwner,
  onLink,
}: ReconciliationPanelProps) {
  const { tokens } = useTheme();
  if (reconciliation === undefined) {
    return null;
  }
  const warning = resultsText(locale, differingMessageKeys[reconciliation.differingField]);
  const linkable = canLink(reconciliation);
  return (
    <section
      aria-label={resultsText(locale, 'reconTitle')}
      style={{
        border: `1px solid ${tokens.border}`,
        borderRadius: tokens.spacing.sm,
        padding: tokens.spacing.lg,
        display: 'grid',
        alignContent: 'start',
        gap: tokens.spacing.sm,
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <h3>{resultsText(locale, 'reconTitle')}</h3>
      <p>{resultsText(locale, 'reconDetail')}</p>
      <div
        style={{
          border: `1px solid ${tokens.border}`,
          borderRadius: tokens.spacing.sm,
          padding: tokens.spacing.md,
          display: 'grid',
          gap: tokens.spacing.sm,
        }}
      >
        <IdentityLine
          label={resultsText(locale, 'reportSays')}
          identity={reconciliation.report}
          locale={locale}
        />
        <IdentityLine
          label={resultsText(locale, 'closestOrder')}
          identity={reconciliation.closestOrder}
          locale={locale}
        />
        <p>
          <strong>{warning}</strong>
        </p>
      </div>
      <div className="cluster">
        <Button variant="secondary" onClick={onAskLab}>
          {resultsText(locale, 'actionAskLab')}
        </Button>
        <Button variant="secondary" onClick={onAssignOwner}>
          {resultsText(locale, 'actionAssignOwner')}
        </Button>
        {linkable ? <Button onClick={onLink}>{resultsText(locale, 'actionLink')}</Button> : null}
      </div>
    </section>
  );
}

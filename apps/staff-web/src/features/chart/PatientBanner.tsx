import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName, formatCivilDate } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { PatientSummary } from './domain';
import { chartText, verificationMessageKey } from './messages';

/** Props for the synthetic patient identity banner (CLN-03, UX-01). */
export interface PatientBannerProps {
  summary: PatientSummary;
  locale: Locale;
  onNewOrder: () => void;
  onPrescribe: () => void;
}

/**
 * Present the synthetic patient identity, demographics and alert chips above
 * the chart. Every value comes from fixture-owned view data; the wire only
 * carries an opaque id and display name, so nothing here is inferred from the
 * API. Allergy, language and results chips carry a word and a distinct glyph as
 * well as a token boundary, because ThemeTokens has no status colours and a
 * colour alone cannot carry clinical meaning (WCAG 1.4.1).
 */
export function PatientBanner({ summary, locale, onNewOrder, onPrescribe }: PatientBannerProps) {
  const { tokens } = useTheme();
  const chipStyle = {
    display: 'inline-flex',
    alignItems: 'center',
    gap: tokens.spacing.sm,
    paddingBlock: tokens.spacing.sm,
    paddingInline: tokens.spacing.md,
    border: `2px solid ${tokens.border}`,
    borderRadius: tokens.spacing.md,
    color: tokens.foreground,
  } as const;

  return (
    <section
      className="page"
      data-part="patient-banner"
      aria-label={chartText(locale, 'patientBanner')}
      style={{
        maxInlineSize: 'none',
        display: 'grid',
        gap: tokens.spacing.md,
        background: tokens.background,
        color: tokens.foreground,
      }}
    >
      <h3 style={{ marginBlock: 0, fontSize: 'var(--sc-font-size-heading-md, 1.5rem)' }}>
        <MixedScriptText text={displayLocalName(summary.name, locale)} lang={locale} />
      </h3>
      <p className="cluster" data-part="demographics" style={{ marginBlock: 0 }}>
        <span>{summary.age}</span>
        <span aria-hidden="true">·</span>
        <MixedScriptText text={summary.sex} lang={locale} />
        <span aria-hidden="true">·</span>
        <span>
          {chartText(locale, 'born')}{' '}
          <time dateTime={summary.dateOfBirth}>{formatCivilDate(summary.dateOfBirth, locale)}</time>
        </span>
        <span aria-hidden="true">·</span>
        <span>
          {chartText(locale, 'mrn')} <bdi dir="ltr">{summary.mrn}</bdi> ({chartText(locale, 'issuer')}:{' '}
          <MixedScriptText text={summary.mrnIssuer} lang={locale} />)
        </span>
      </p>
      <div className="cluster" data-part="chips">
        {summary.allergy === undefined ? null : (
          <span data-testid="allergy-chip" role="note" style={chipStyle}>
            <span aria-hidden="true">⚠</span>
            {chartText(locale, 'allergy')}:{' '}
            <MixedScriptText text={summary.allergy.display} lang={locale} /> (
            {chartText(locale, 'reaction')}:{' '}
            <MixedScriptText text={summary.allergy.reaction} lang={locale} />,{' '}
            {chartText(locale, verificationMessageKey[summary.allergy.verification])})
          </span>
        )}
        <span data-testid="language-chip" role="note" style={chipStyle}>
          <span aria-hidden="true">◍</span>
          {chartText(locale, 'language')}:{' '}
          {chartText(locale, summary.language === 'en' ? 'languageEn' : 'languageAr')}
        </span>
        {summary.resultsToReview > 0 ? (
          <span data-testid="results-chip" role="note" style={chipStyle}>
            <span aria-hidden="true">!</span>
            {summary.resultsToReview} {chartText(locale, 'resultsToReview')}
          </span>
        ) : null}
        <span className="cluster" style={{ marginInlineStart: 'auto' }}>
          <Button onClick={onNewOrder}>{chartText(locale, 'newOrder')}</Button>
          <Button variant="secondary" onClick={onPrescribe}>
            {chartText(locale, 'prescribe')}
          </Button>
        </span>
      </div>
    </section>
  );
}

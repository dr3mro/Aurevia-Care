/** Presentational components for laboratory report release, correction and withdrawal. */

import { MixedScriptText } from '../../components/MixedScriptText';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import {
  diffRevisions,
  differenceGlyph,
  hasDifferences,
  hasReason,
  isWithdrawn,
  releaseVisibility,
  type CorrectionValues,
  type ReleaseAudience,
  type ReleaseState,
  type ReportRevision,
  type WithdrawalDetail,
} from './domain';
import {
  differenceKey,
  formatValue,
  formatWithdrawalTime,
  releaseMessage,
  releaseText,
  stateKey,
  visibilityKey,
} from './labReleaseMessages';

/** Audiences shown in the release status panel, in reading order for both locales. */
const AUDIENCES: readonly ReleaseAudience[] = ['clinician', 'patient'];

/** Render one comparison cell: an absent reading is stated in words, never blank. */
function renderValue(value: number | null, unit: string, locale: Locale) {
  if (value === null) {
    return <span>{releaseText(locale, 'valueNotPresent')}</span>;
  }
  return <code dir="ltr">{formatValue(value, unit, locale)}</code>;
}

/** Props for the old-versus-new revision comparison. */
export interface SideBySideComparisonProps {
  before: ReportRevision;
  after: ReportRevision;
  locale: Locale;
}

/** Show each observation in the original and corrected revision side by side. */
export function SideBySideComparison({ before, after, locale }: SideBySideComparisonProps) {
  const { tokens } = useTheme();
  const differences = diffRevisions(before, after);
  if (!hasDifferences(differences)) {
    return <p>{releaseText(locale, 'noDifferences')}</p>;
  }
  const headers = [
    releaseText(locale, 'colTest'),
    releaseMessage(locale, 'colBefore', { version: String(before.version) }),
    releaseMessage(locale, 'colAfter', { version: String(after.version) }),
    releaseText(locale, 'colChange'),
  ];
  // Keep the opaque accession in its own LTR run under an Arabic interface.
  const [captionLead, captionTail] = releaseText(locale, 'comparisonCaption').split('{accession}');
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
        {captionLead}
        <code dir="ltr">{after.accession}</code>
        {captionTail}
      </caption>
      <thead>
        <tr>
          {headers.map((header) => (
            <th
              key={header}
              scope="col"
              style={{ textAlign: 'start', paddingInline: tokens.spacing.sm }}
            >
              {header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {differences.map((difference) => (
          <tr
            key={difference.testId}
            data-testid={`diff-${difference.testId}`}
            data-change={difference.kind}
          >
            <th scope="row" style={{ textAlign: 'start', paddingInline: tokens.spacing.sm }}>
              <MixedScriptText text={difference.testName} lang="en" />
            </th>
            <td style={{ paddingInline: tokens.spacing.sm }}>
              {renderValue(difference.oldValue, difference.unit, locale)}
            </td>
            <td style={{ paddingInline: tokens.spacing.sm }}>
              {renderValue(difference.newValue, difference.unit, locale)}
            </td>
            <td style={{ paddingInline: tokens.spacing.sm }}>
              <span aria-hidden="true">{differenceGlyph[difference.kind]}</span>{' '}
              {releaseText(locale, differenceKey(difference.kind))}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Props for the correction form. */
export interface CorrectionFormProps {
  values: CorrectionValues;
  locale: Locale;
  onChange: (values: CorrectionValues) => void;
  onSubmit: () => void;
}

/** Capture what changed and the mandatory reason, without discarding typed work. */
export function CorrectionForm({ values, locale, onChange, onSubmit }: CorrectionFormProps) {
  const { tokens } = useTheme();
  const missing = !hasReason(values.reason);
  return (
    <section
      aria-label={releaseText(locale, 'correctionRegion')}
      style={{
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        display: 'grid',
        gap: tokens.spacing.sm,
      }}
    >
      <h3>{releaseText(locale, 'correctionRegion')}</h3>
      <label htmlFor="release-change">{releaseText(locale, 'changeLabel')}</label>
      <textarea
        id="release-change"
        lang="en"
        dir="auto"
        value={values.changeSummary}
        onChange={(event) => onChange({ ...values, changeSummary: event.target.value })}
        style={{ minHeight: '64px' }}
      />
      <label htmlFor="release-correction-reason">{releaseText(locale, 'reasonLabel')}</label>
      <textarea
        id="release-correction-reason"
        lang="en"
        dir="auto"
        value={values.reason}
        aria-invalid={missing}
        aria-describedby="release-correction-reason-error"
        onChange={(event) => onChange({ ...values, reason: event.target.value })}
        style={{ minHeight: '64px' }}
      />
      {missing ? (
        <p
          role="alert"
          id="release-correction-reason-error"
          data-testid="correction-reason-required"
        >
          {releaseText(locale, 'reasonRequiredError')}
        </p>
      ) : null}
      <button
        type="button"
        onClick={onSubmit}
        disabled={missing}
        style={{ minHeight: '32px', paddingInline: tokens.spacing.md }}
      >
        {releaseText(locale, 'correctButton')}
      </button>
    </section>
  );
}

/** Props for the withdrawal action. */
export interface WithdrawPanelProps {
  reason: string;
  locale: Locale;
  onReasonChange: (reason: string) => void;
  onSubmit: () => void;
}

/** Explain the consequence of withdrawal and require a reason before acting. */
export function WithdrawPanel({ reason, locale, onReasonChange, onSubmit }: WithdrawPanelProps) {
  const { tokens } = useTheme();
  const missing = !hasReason(reason);
  return (
    <section
      aria-label={releaseText(locale, 'withdrawalRegion')}
      style={{
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        display: 'grid',
        gap: tokens.spacing.sm,
      }}
    >
      <h3>{releaseText(locale, 'withdrawalRegion')}</h3>
      <p>{releaseText(locale, 'withdrawalConsequence')}</p>
      <label htmlFor="release-withdraw-reason">{releaseText(locale, 'withdrawReasonLabel')}</label>
      <textarea
        id="release-withdraw-reason"
        lang="en"
        dir="auto"
        value={reason}
        aria-invalid={missing}
        aria-describedby="release-withdraw-reason-error"
        onChange={(event) => onReasonChange(event.target.value)}
        style={{ minHeight: '64px' }}
      />
      {missing ? (
        <p role="alert" id="release-withdraw-reason-error" data-testid="withdraw-reason-required">
          {releaseText(locale, 'withdrawReasonError')}
        </p>
      ) : null}
      <button
        type="button"
        onClick={onSubmit}
        disabled={missing}
        style={{ minHeight: '32px', paddingInline: tokens.spacing.md }}
      >
        {releaseText(locale, 'withdrawButton')}
      </button>
    </section>
  );
}

/** Props for the recorded-withdrawal banner. */
export interface WithdrawnNoticeProps {
  detail: WithdrawalDetail;
  locale: Locale;
}

/** Show who withdrew a report, why and when, as a persistent alert. */
export function WithdrawnNotice({ detail, locale }: WithdrawnNoticeProps) {
  const { tokens } = useTheme();
  return (
    <div
      role="alert"
      data-testid="withdrawn-notice"
      style={{
        border: `2px solid ${tokens.foreground}`,
        padding: tokens.spacing.md,
        display: 'grid',
        gap: tokens.spacing.sm,
      }}
    >
      <strong>{releaseText(locale, 'withdrawnHeading')}</strong>
      <p>{releaseText(locale, 'withdrawnDetail')}</p>
      <dl style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <dt>{releaseText(locale, 'withdrawnReason')}</dt>
        <dd>
          <MixedScriptText text={detail.reason} lang="en" />
        </dd>
        <dt>{releaseText(locale, 'withdrawnActor')}</dt>
        <dd>
          <code dir="ltr">{detail.actor}</code>
        </dd>
        <dt>{releaseText(locale, 'withdrawnAt')}</dt>
        <dd>
          <time dateTime={detail.recordedAt}>
            {formatWithdrawalTime(detail.recordedAt, locale)}
          </time>
        </dd>
      </dl>
    </div>
  );
}

/** Props for the per-audience release status panel. */
export interface ReleaseStatusPanelProps {
  state: ReleaseState;
  locale: Locale;
}

/** Show the report state and what clinician and patient can currently see. */
export function ReleaseStatusPanel({ state, locale }: ReleaseStatusPanelProps) {
  const { tokens } = useTheme();
  return (
    <section
      aria-label={releaseText(locale, 'statusRegion')}
      style={{
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        display: 'grid',
        gap: tokens.spacing.sm,
      }}
    >
      <h3>{releaseText(locale, 'statusRegion')}</h3>
      <p data-testid="release-state">
        {releaseText(locale, 'statusState')}: {releaseText(locale, stateKey(state))}
      </p>
      {AUDIENCES.map((audience) => (
        <p key={audience} data-testid={`release-status-${audience}`}>
          {releaseText(locale, audience === 'clinician' ? 'statusClinician' : 'statusPatient')}:{' '}
          {releaseText(locale, visibilityKey(releaseVisibility(state, audience)))}
        </p>
      ))}
    </section>
  );
}

/** Props for the whole release screen. */
export interface ReleaseScreenProps {
  before: ReportRevision;
  after: ReportRevision;
  state: ReleaseState;
  withdrawal: WithdrawalDetail | null;
  locale: Locale;
  correction: CorrectionValues;
  withdrawReason: string;
  correctionNotice: string | null;
  onCorrectionChange: (values: CorrectionValues) => void;
  onWithdrawReasonChange: (reason: string) => void;
  onCorrect: () => void;
  onWithdraw: () => void;
}

/** Compose the revision comparison, correction, withdrawal and release status. */
export function ReleaseScreen({
  before,
  after,
  state,
  withdrawal,
  locale,
  correction,
  withdrawReason,
  correctionNotice,
  onCorrectionChange,
  onWithdrawReasonChange,
  onCorrect,
  onWithdraw,
}: ReleaseScreenProps) {
  const { tokens } = useTheme();
  const withdrawn = isWithdrawn(state);
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{releaseText(locale, 'title')}</h2>
        <p>{releaseText(locale, 'syntheticNotice')}</p>
      </header>
      {withdrawal !== null ? <WithdrawnNotice detail={withdrawal} locale={locale} /> : null}
      <SideBySideComparison before={before} after={after} locale={locale} />
      <CorrectionForm
        values={correction}
        locale={locale}
        onChange={onCorrectionChange}
        onSubmit={onCorrect}
      />
      {!withdrawn ? (
        <WithdrawPanel
          reason={withdrawReason}
          locale={locale}
          onReasonChange={onWithdrawReasonChange}
          onSubmit={onWithdraw}
        />
      ) : null}
      <ReleaseStatusPanel state={state} locale={locale} />
      {correctionNotice !== null ? (
        <p role="status" data-testid="correction-status">
          {correctionNotice}
        </p>
      ) : null}
    </div>
  );
}

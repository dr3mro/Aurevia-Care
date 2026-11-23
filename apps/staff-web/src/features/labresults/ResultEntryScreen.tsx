/** Presentational components for laboratory result entry and verification. */

import { MixedScriptText } from '../../components/MixedScriptText';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import {
  CHANGE_GLYPH,
  canVerify,
  enteredValue,
  flagGlyph,
  hasCriticalResult,
  hasLargeChange,
  relativeChangePercent,
  type ResultFlag,
  type ResultReport,
  type ResultRow,
} from './domain';
import {
  flagKey,
  formatPercent,
  formatPreviousDate,
  formatValue,
  labMessage,
  labText,
  statusKey,
} from './labResultMessages';
import type { LabResultMessageKey } from './labResultMessages';

/** Table column keys, in reading order for both locales. */
const COLUMNS: readonly LabResultMessageKey[] = [
  'colTest',
  'colValue',
  'colUnit',
  'colRange',
  'colFlag',
  'colPrevious',
  'colChange',
];

/** Props for an abnormal flag shown as a shape-distinct glyph and a word. */
export interface FlagBadgeProps {
  flag: ResultFlag;
  locale: Locale;
}

/** Show one abnormal flag; the glyph is decorative, the word carries the meaning. */
export function FlagBadge({ flag, locale }: FlagBadgeProps) {
  const { tokens } = useTheme();
  return (
    <span
      data-flag={flag}
      style={{ display: 'inline-flex', alignItems: 'center', gap: tokens.spacing.sm }}
    >
      <span aria-hidden="true">{flagGlyph[flag]}</span>
      <MixedScriptText text={labText(locale, flagKey(flag))} lang={locale} />
    </span>
  );
}

/** Props for the configured-limit delta indicator. */
export interface ChangeIndicatorProps {
  row: ResultRow;
  locale: Locale;
}

/** Show the previous-value delta with a warning word and glyph when it exceeds the limit. */
export function ChangeIndicator({ row, locale }: ChangeIndicatorProps) {
  if (row.previous === null) {
    return <span data-change="none">{labText(locale, 'changeNoPrevious')}</span>;
  }
  const change = relativeChangePercent(row.value, row.previous.value);
  if (change === null) {
    return <span data-change="none">{labText(locale, 'changeNone')}</span>;
  }
  const large = hasLargeChange(row);
  return (
    <span data-change={large ? 'large' : 'normal'}>
      {large ? (
        <>
          <span aria-hidden="true">{CHANGE_GLYPH}</span>{' '}
          {labMessage(locale, 'changeLarge', { percent: formatPercent(Math.abs(change), locale) })}
        </>
      ) : (
        labText(locale, 'changeNone')
      )}
    </span>
  );
}

/** Props for the editable observation grid. */
export interface ResultGridProps {
  report: ResultReport;
  /** Draft strings keyed by row id; a failed action must never discard them. */
  drafts: Readonly<Record<string, string>>;
  locale: Locale;
  onValueChange: (rowId: string, value: string) => void;
}

/** Render the report's observations with units, ranges, flags, previous values and delta checks. */
export function ResultGrid({ report, drafts, locale, onValueChange }: ResultGridProps) {
  const { tokens } = useTheme();
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
        {labText(locale, 'gridCaption')} <code dir="ltr">{report.accession}</code>
      </caption>
      <thead>
        <tr>
          {COLUMNS.map((key) => (
            <th
              key={key}
              scope="col"
              style={{ textAlign: 'start', paddingInline: tokens.spacing.sm }}
            >
              {labText(locale, key)}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {report.rows.map((row) => {
          const draft = drafts[row.id] ?? String(row.value);
          return (
            <tr key={row.id}>
              <th scope="row" style={{ textAlign: 'start', paddingInline: tokens.spacing.sm }}>
                <MixedScriptText text={row.testName} lang="en" />
              </th>
              <td style={{ paddingInline: tokens.spacing.sm }}>
                <input
                  id={`result-value-${row.id}`}
                  aria-label={labMessage(locale, 'valueInputLabel', { test: row.testName })}
                  inputMode="decimal"
                  value={draft}
                  onChange={(event) => onValueChange(row.id, event.target.value)}
                  style={{ width: '6rem', minHeight: '32px' }}
                />
              </td>
              <td style={{ paddingInline: tokens.spacing.sm }}>
                <code dir="ltr">{row.unit}</code>
              </td>
              <td style={{ paddingInline: tokens.spacing.sm }}>
                <code dir="ltr">{row.referenceRange}</code>
              </td>
              <td style={{ paddingInline: tokens.spacing.sm }}>
                <FlagBadge flag={row.flag} locale={locale} />
              </td>
              <td style={{ paddingInline: tokens.spacing.sm }}>
                {row.previous === null ? (
                  <span>{labText(locale, 'noPrevious')}</span>
                ) : (
                  <>
                    <div>
                      <code dir="ltr">{formatValue(row.previous.value, row.unit, locale)}</code>
                    </div>
                    <div>{formatPreviousDate(row.previous.at, locale)}</div>
                  </>
                )}
              </td>
              <td style={{ paddingInline: tokens.spacing.sm }}>
                <ChangeIndicator
                  row={{ ...row, value: enteredValue(row, draft) }}
                  locale={locale}
                />
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/** Props for the second-user verification and release-summary panel. */
export interface VerificationPanelProps {
  report: ResultReport;
  /** Draft values keyed by row id, used for the values that will be released. */
  drafts: Readonly<Record<string, string>>;
  verifier: string;
  locale: Locale;
  onVerify: () => void;
}

/** Summarise what will be released and allow only a distinct second user to verify. */
export function VerificationPanel({
  report,
  drafts,
  verifier,
  locale,
  onVerify,
}: VerificationPanelProps) {
  const { tokens } = useTheme();
  const allowed = canVerify(report.enteredBy, verifier);
  const critical = hasCriticalResult(report.rows);
  return (
    <section
      aria-label={labText(locale, 'verifyRegion')}
      style={{
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        display: 'grid',
        gap: tokens.spacing.sm,
      }}
    >
      <h3>{labText(locale, 'verifyRegion')}</h3>
      <p>{labText(locale, statusKey(report.status))}</p>
      <p>{labMessage(locale, 'enteredBy', { subject: report.enteredBy })}</p>
      <p>{labMessage(locale, 'verifyAs', { subject: verifier })}</p>
      <h4>{labText(locale, 'summaryTitle')}</h4>
      <p>{labMessage(locale, 'summaryCount', { count: String(report.rows.length) })}</p>
      <ul>
        {report.rows.map((row) => {
          const draft = drafts[row.id] ?? String(row.value);
          return (
            <li key={row.id}>
              {labMessage(locale, 'summaryItem', {
                test: row.testName,
                value: formatValue(enteredValue(row, draft), row.unit, locale),
              })}{' '}
              <FlagBadge flag={row.flag} locale={locale} />
            </li>
          );
        })}
      </ul>
      {critical ? (
        <div role="note" data-testid="critical-notification-step">
          <h4>{labText(locale, 'notifyStepTitle')}</h4>
          <p>{labText(locale, 'notifyStepDetail')}</p>
        </div>
      ) : null}
      {!allowed ? (
        <p role="alert" data-testid="verify-blocked">
          {labText(locale, 'sameUserBlocked')}
        </p>
      ) : null}
      <button
        type="button"
        onClick={onVerify}
        disabled={!allowed}
        style={{ minHeight: '32px', paddingInline: tokens.spacing.md }}
      >
        {labText(locale, 'verifyButton')}
      </button>
    </section>
  );
}

/** Props for the whole result-entry screen. */
export interface ResultEntryScreenProps {
  report: ResultReport;
  drafts: Readonly<Record<string, string>>;
  verifier: string;
  locale: Locale;
  verified: boolean;
  onValueChange: (rowId: string, value: string) => void;
  onVerify: () => void;
}

/** Render the entry grid, the verification summary and the verification outcome. */
export function ResultEntryScreen({
  report,
  drafts,
  verifier,
  locale,
  verified,
  onValueChange,
  onVerify,
}: ResultEntryScreenProps) {
  const { tokens } = useTheme();
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{labText(locale, 'title')}</h2>
        <p>{labText(locale, 'syntheticNotice')}</p>
        <p>{labText(locale, 'gridCaption')}</p>
      </header>
      <ResultGrid report={report} drafts={drafts} locale={locale} onValueChange={onValueChange} />
      <VerificationPanel
        report={report}
        drafts={drafts}
        verifier={verifier}
        locale={locale}
        onVerify={onVerify}
      />
      {verified ? (
        <p role="status" data-testid="verification-status">
          {labMessage(locale, 'verifiedStatus', { subject: verifier })}
        </p>
      ) : null}
    </div>
  );
}

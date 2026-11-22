/** Presentational components for the duplicate review worklist and detail. */

import type { ReactElement } from 'react';
import type { Locale } from '../../i18n/messages';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { StateView } from '../../components/StateViews';
import type { TransportError } from '../../api/transport';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type {
  ApprovalFormErrors,
  ConsolidationWire,
  DuplicatePairWire,
  ReviewDetailWire,
  ReviewWire,
} from './domain';
import { identifierReferences, languageForName } from './domain';
import type { DuplicatesMessageKey } from './messages';
import {
  dependencyKindKey,
  dependencyStateKey,
  dobText,
  duplicatesMessage,
  duplicatesText,
} from './messages';

/**
 * Place a captioned table in the shared labelled scroll region on compact
 * viewports and leave it in the normal flow from medium upward. The table,
 * caption and headers are unchanged, so no clinical column disappears and the
 * accessibility tree keeps the same names at every width (WEB-07, UX-01).
 */
function ResponsiveTableRegion({
  compact,
  label,
  children,
}: {
  compact: boolean;
  label: string;
  children: ReactElement;
}) {
  if (compact) {
    return (
      <div className="table-region" role="region" aria-label={label} tabIndex={0}>
        {children}
      </div>
    );
  }
  return children;
}

/** Logical wrapping for a clinical table cell using the active theme border. */
function cellStyle(border: string) {
  return {
    border: `1px solid ${border}`,
    minInlineSize: 0,
    overflowWrap: 'anywhere',
    whiteSpace: 'normal',
  } as const;
}

/** Props for the worklist table. */
export interface WorklistScreenProps {
  pairs: readonly DuplicatePairWire[];
  locale: Locale;
  onSelect: (pair: DuplicatePairWire) => void;
}

/** Render the same-tenant duplicate pairs as an accessible worklist table. */
export function WorklistScreen({ pairs, locale, onSelect }: WorklistScreenProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  // The .touch class owns both target floors; an inline min-height would
  // outrank its 44px coarse-pointer rule and shrink the row action on phones.
  const caption = duplicatesText(locale, 'worklistCaption');
  const cell = cellStyle(tokens.border);
  const table = (
    <table data-breakpoint={breakpoint} style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
        {caption}
      </caption>
      <thead>
        <tr>
          <th scope="col" style={cell}>{duplicatesText(locale, 'colSurvivor')}</th>
          <th scope="col" style={cell}>{duplicatesText(locale, 'colDuplicate')}</th>
          <th scope="col" style={cell}>{duplicatesText(locale, 'colAction')}</th>
        </tr>
      </thead>
      <tbody>
        {pairs.map((pair) => (
          <tr key={`${pair.survivorId}:${pair.duplicateId}`}>
            <td style={cell}>
              <MixedScriptText
                text={pair.survivorName}
                lang={languageForName(pair.survivorName)}
              />
              <code dir="ltr"> {pair.survivorId}</code>
            </td>
            <td style={cell}>
              <MixedScriptText
                text={pair.duplicateName}
                lang={languageForName(pair.duplicateName)}
              />
              <code dir="ltr"> {pair.duplicateId}</code>
            </td>
            <td style={cell}>
              <button
                type="button"
                className="touch"
                onClick={() => onSelect(pair)}
                aria-label={duplicatesMessage(locale, 'reviewActionNamed', {
                  survivor: pair.survivorId,
                  duplicate: pair.duplicateId,
                })}
                style={{
                  paddingBlock: tokens.spacing.sm,
                  paddingInline: tokens.spacing.md,
                  border: `1px solid ${tokens.border}`,
                  borderRadius: tokens.spacing.sm,
                  background: tokens.accent,
                  color: tokens.background,
                  cursor: 'pointer',
                  overflowWrap: 'anywhere',
                  whiteSpace: 'normal',
                  textAlign: 'start',
                }}
              >
                {duplicatesText(locale, 'reviewAction')}
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
  return (
    <ResponsiveTableRegion compact={breakpoint === 'compact'} label={caption}>
      {table}
    </ResponsiveTableRegion>
  );
}

/** Render one identity card; dates use the shared civil-date formatter. */
function IdentityCard({
  title,
  id,
  patient,
  locale,
}: {
  title: string;
  id: string;
  patient: ReviewDetailWire['survivor'];
  locale: Locale;
}) {
  const { tokens } = useTheme();
  return (
    <section
      aria-label={title}
      className="stack"
      style={{
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        gap: tokens.spacing.sm,
        minInlineSize: 0,
      }}
    >
      <h3>{title}</h3>
      <p>
        <MixedScriptText
          text={patient.displayName}
          lang={languageForName(patient.displayName)}
        />
      </p>
      <dl className="stack" style={{ gap: tokens.spacing.sm, margin: 0 }}>
        <dt>{duplicatesText(locale, 'reviewId')}</dt>
        <dd>
          <code dir="ltr">{patient.id}</code>
        </dd>
        <dt>{duplicatesText(locale, 'colMrnShort')}</dt>
        <dd>
          <MixedScriptText text={`${patient.mrn}@${patient.mrnIssuer}`} lang="en" />
        </dd>
        <dt>{duplicatesText(locale, 'colDob')}</dt>
        <dd>{dobText(patient.dob, patient.dobUnknownReason, locale)}</dd>
        <dt>{duplicatesText(locale, 'colLanguage')}</dt>
        <dd>{patient.language}</dd>
      </dl>
      <span hidden>{id}</span>
    </section>
  );
}

/** Props for the read-only side-by-side review detail. */
export interface ReviewScreenProps {
  detail: ReviewDetailWire;
  locale: Locale;
}

/** Render both identities, conflicting demographics, dependencies and the dry run. */
export function ReviewScreen({ detail, locale }: ReviewScreenProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const compact = breakpoint === 'compact';
  const cell = cellStyle(tokens.border);
  const conflictsTitle = duplicatesText(locale, 'conflictsTitle');
  return (
    <div data-breakpoint={breakpoint} style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <section aria-label={duplicatesText(locale, 'identitiesTitle')}>
        <h3>{duplicatesText(locale, 'identitiesTitle')}</h3>
        {/*
          .stack is one column on phones; .grid-2 becomes two columns from the
          768px breakpoint. Both identities keep the same DOM order and labels.
        */}
        <div
          className={compact ? 'stack' : 'grid-2'}
          data-layout={compact ? 'stacked' : 'side-by-side'}
        >
          <IdentityCard
            title={duplicatesText(locale, 'survivor')}
            id={detail.survivor.id}
            patient={detail.survivor}
            locale={locale}
          />
          <IdentityCard
            title={duplicatesText(locale, 'duplicate')}
            id={detail.duplicate.id}
            patient={detail.duplicate}
            locale={locale}
          />
        </div>
      </section>
      <section aria-label={conflictsTitle}>
        <h3>{conflictsTitle}</h3>
        {detail.conflicts.length === 0 ? (
          <p>{duplicatesText(locale, 'noConflicts')}</p>
        ) : (
          <ResponsiveTableRegion compact={compact} label={conflictsTitle}>
            <table style={{ borderCollapse: 'collapse', width: '100%' }}>
              <caption style={{ textAlign: 'start' }}>{conflictsTitle}</caption>
              <thead>
                <tr>
                  <th scope="col" style={cell}>{duplicatesText(locale, 'colField')}</th>
                  <th scope="col" style={cell}>{duplicatesText(locale, 'colSurvivorValue')}</th>
                  <th scope="col" style={cell}>{duplicatesText(locale, 'colDuplicateValue')}</th>
                </tr>
              </thead>
              <tbody>
                {detail.conflicts.map((conflict) => (
                  <tr key={conflict.field}>
                    <th scope="row" style={cell}>{conflict.field}</th>
                    <td style={cell}>
                      <MixedScriptText
                        text={conflict.survivorValue}
                        lang={languageForName(conflict.survivorValue)}
                      />
                    </td>
                    <td style={cell}>
                      <MixedScriptText
                        text={conflict.duplicateValue}
                        lang={languageForName(conflict.duplicateValue)}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ResponsiveTableRegion>
        )}
      </section>
      <section aria-label={duplicatesText(locale, 'dependenciesTitle')}>
        <h3>{duplicatesText(locale, 'dependenciesTitle')}</h3>
        {detail.dependencies.length === 0 ? (
          <p>{duplicatesText(locale, 'noDependencies')}</p>
        ) : (
          <ul className="stack" style={{ gap: tokens.spacing.sm }}>
            {detail.dependencies.map((dependency) => (
              <li key={dependency.id}>
                {duplicatesText(locale, dependencyKindKey(dependency.kind))}:{' '}
                <code dir="ltr">{dependency.reference}</code> —{' '}
                {duplicatesText(locale, dependencyStateKey(dependency.resolved, dependency.ambiguous))}
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-label={duplicatesText(locale, 'dryRunTitle')}>
        <h3>{duplicatesText(locale, 'dryRunTitle')}</h3>
        {/* A <pre> does not wrap by default; let the dry run reflow on phones. */}
        <pre
          style={{
            margin: 0,
            minInlineSize: 0,
            overflowWrap: 'anywhere',
            whiteSpace: 'pre-wrap',
          }}
        >
          {detail.dryRun}
        </pre>
      </section>
    </div>
  );
}

/** Props for the open/approve command panel. */
export interface ApprovalPanelProps {
  locale: Locale;
  detail: ReviewDetailWire;
  review: ReviewWire | null;
  reason: string;
  confirmed: readonly string[];
  alternateProcess: string;
  errors: ApprovalFormErrors;
  notice: DuplicatesMessageKey | undefined;
  openError: TransportError | undefined;
  approvalError: TransportError | undefined;
  approved: ConsolidationWire | undefined;
  onOpenReview: () => void;
  onReasonChange: (value: string) => void;
  onToggleReference: (reference: string) => void;
  onAlternateChange: (value: string) => void;
  onApprove: (review: ReviewWire) => void;
}

/** Render the open-review action, then the second-reviewer approval form. */
export function ApprovalPanel({
  locale,
  detail,
  review,
  reason,
  confirmed,
  alternateProcess,
  errors,
  notice,
  openError,
  approvalError,
  approved,
  onOpenReview,
  onReasonChange,
  onToggleReference,
  onAlternateChange,
  onApprove,
}: ApprovalPanelProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const references = identifierReferences(detail);
  return (
    <section
      aria-label={duplicatesText(locale, 'approvalTitle')}
      data-breakpoint={breakpoint}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h3>{duplicatesText(locale, 'approvalTitle')}</h3>
      {openError !== undefined && StateView.forTransportError(openError)}
      {review === null ? (
        <div>
          <Button onClick={onOpenReview}>{duplicatesText(locale, 'openReview')}</Button>
          <p role="status">{duplicatesText(locale, 'openFirst')}</p>
        </div>
      ) : (
        <>
          <p>
            {duplicatesText(locale, 'reviewId')}: <code dir="ltr">{review.id}</code>
          </p>
          <p>
            {duplicatesText(locale, 'openedBy')}:{' '}
            <MixedScriptText text={review.openedBySubject} lang="en" />
          </p>
          {notice !== undefined && (
            <p role="alert">{duplicatesText(locale, notice)}</p>
          )}
          {approved !== undefined && (
            <p role="status">{duplicatesText(locale, 'approved')}</p>
          )}
          {approvalError !== undefined && StateView.forTransportError(approvalError)}
          <Form
            label={duplicatesText(locale, 'approvalTitle')}
            submitLabel={duplicatesText(locale, 'approve')}
            onSubmit={() => onApprove(review)}
          >
            <FormField
              id="duplicate-reason"
              label={duplicatesText(locale, 'reasonLabel')}
              value={reason}
              onChange={onReasonChange}
              {...(errors.reason === undefined ? {} : { error: duplicatesText(locale, 'reasonLabel') })}
            />
            <fieldset style={{ border: `1px solid ${tokens.border}` }}>
              <legend>{duplicatesText(locale, 'confirmTitle')}</legend>
              <p>{duplicatesText(locale, 'confirmHint')}</p>
              {references.map((reference) => (
                <label key={reference} htmlFor={`confirm-${reference}`} className="cluster">
                  <input
                    id={`confirm-${reference}`}
                    type="checkbox"
                    className="touch"
                    checked={confirmed.includes(reference)}
                    onChange={() => onToggleReference(reference)}
                  />{' '}
                  <MixedScriptText text={reference} lang="en" />
                </label>
              ))}
              <FormField
                id="duplicate-alternate"
                label={duplicatesText(locale, 'alternateLabel')}
                value={alternateProcess}
                onChange={onAlternateChange}
                {...(errors.confirmation === undefined
                  ? {}
                  : { error: duplicatesText(locale, 'confirmHint') })}
              />
              <p>{duplicatesText(locale, 'alternateHint')}</p>
            </fieldset>
          </Form>
        </>
      )}
    </section>
  );
}

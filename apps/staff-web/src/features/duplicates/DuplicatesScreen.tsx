/** Presentational components for the duplicate review worklist and detail. */

import type { Locale } from '../../i18n/messages';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { StateView } from '../../components/StateViews';
import type { TransportError } from '../../api/transport';
import { useTheme } from '../../theme/ThemeProvider';
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

/** Props for the worklist table. */
export interface WorklistScreenProps {
  pairs: readonly DuplicatePairWire[];
  locale: Locale;
  onSelect: (pair: DuplicatePairWire) => void;
}

/** Render the same-tenant duplicate pairs as an accessible worklist table. */
export function WorklistScreen({ pairs, locale, onSelect }: WorklistScreenProps) {
  const { tokens } = useTheme();
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
        {duplicatesText(locale, 'worklistCaption')}
      </caption>
      <thead>
        <tr>
          <th scope="col">{duplicatesText(locale, 'colSurvivor')}</th>
          <th scope="col">{duplicatesText(locale, 'colDuplicate')}</th>
          <th scope="col">{duplicatesText(locale, 'colAction')}</th>
        </tr>
      </thead>
      <tbody>
        {pairs.map((pair) => (
          <tr key={`${pair.survivorId}:${pair.duplicateId}`}>
            <td>
              <MixedScriptText
                text={pair.survivorName}
                lang={languageForName(pair.survivorName)}
              />
              <code dir="ltr"> {pair.survivorId}</code>
            </td>
            <td>
              <MixedScriptText
                text={pair.duplicateName}
                lang={languageForName(pair.duplicateName)}
              />
              <code dir="ltr"> {pair.duplicateId}</code>
            </td>
            <td>
              <button
                type="button"
                onClick={() => onSelect(pair)}
                aria-label={duplicatesMessage(locale, 'reviewActionNamed', {
                  survivor: pair.survivorId,
                  duplicate: pair.duplicateId,
                })}
                style={{
                  minHeight: '32px',
                  padding: `${tokens.spacing.sm} ${tokens.spacing.md}`,
                  border: `1px solid ${tokens.border}`,
                  borderRadius: tokens.spacing.sm,
                  background: tokens.accent,
                  color: tokens.background,
                  cursor: 'pointer',
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
      style={{
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        flex: 1,
      }}
    >
      <h3>{title}</h3>
      <p>
        <MixedScriptText
          text={patient.displayName}
          lang={languageForName(patient.displayName)}
        />
      </p>
      <dl>
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
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <section aria-label={duplicatesText(locale, 'identitiesTitle')}>
        <h3>{duplicatesText(locale, 'identitiesTitle')}</h3>
        <div style={{ display: 'flex', gap: tokens.spacing.md }}>
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
      <section aria-label={duplicatesText(locale, 'conflictsTitle')}>
        <h3>{duplicatesText(locale, 'conflictsTitle')}</h3>
        {detail.conflicts.length === 0 ? (
          <p>{duplicatesText(locale, 'noConflicts')}</p>
        ) : (
          <table style={{ borderCollapse: 'collapse', width: '100%' }}>
            <caption style={{ textAlign: 'start' }}>
              {duplicatesText(locale, 'conflictsTitle')}
            </caption>
            <thead>
              <tr>
                <th scope="col">{duplicatesText(locale, 'colField')}</th>
                <th scope="col">{duplicatesText(locale, 'colSurvivorValue')}</th>
                <th scope="col">{duplicatesText(locale, 'colDuplicateValue')}</th>
              </tr>
            </thead>
            <tbody>
              {detail.conflicts.map((conflict) => (
                <tr key={conflict.field}>
                  <th scope="row">{conflict.field}</th>
                  <td>
                    <MixedScriptText
                      text={conflict.survivorValue}
                      lang={languageForName(conflict.survivorValue)}
                    />
                  </td>
                  <td>
                    <MixedScriptText
                      text={conflict.duplicateValue}
                      lang={languageForName(conflict.duplicateValue)}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <section aria-label={duplicatesText(locale, 'dependenciesTitle')}>
        <h3>{duplicatesText(locale, 'dependenciesTitle')}</h3>
        {detail.dependencies.length === 0 ? (
          <p>{duplicatesText(locale, 'noDependencies')}</p>
        ) : (
          <ul>
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
        <pre>{detail.dryRun}</pre>
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
  const references = identifierReferences(detail);
  return (
    <section aria-label={duplicatesText(locale, 'approvalTitle')} style={{ display: 'grid', gap: tokens.spacing.md }}>
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
                <label key={reference} htmlFor={`confirm-${reference}`} style={{ display: 'block' }}>
                  <input
                    id={`confirm-${reference}`}
                    type="checkbox"
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

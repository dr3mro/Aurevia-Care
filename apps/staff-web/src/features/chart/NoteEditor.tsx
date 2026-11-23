import { useState } from 'react';
import type { CSSProperties } from 'react';
import { Button } from '../../components/Button';
import { ConflictState } from '../../components/StateViews';
import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint, usePointerCoarse } from '../../theme/useBreakpoint';
import type { NoteRevision, SoapContent } from './domain';
import { canAmend, isEditable } from './domain';
import { EnteredInErrorBanner } from './EnteredInErrorBanner';
import { RevisionHistory } from './RevisionHistory';
import { chartText } from './messages';

/** Props for the SOAP note draft/signed editor (CLN-05). */
export interface NoteEditorProps {
  revision: NoteRevision;
  history: readonly NoteRevision[];
  locale: Locale;
  onContentChange: (field: keyof SoapContent, value: string) => void;
  onSign: () => void;
  onAmend: () => void;
  onMarkEnteredInError: () => void;
  /**
   * Enforce the design's required Plan field. Opt-in: the wire-backed note
   * stores one content string, so only the synthetic chart preview can show a
   * four-field requiredness rule without changing the API contract.
   */
  planRequired?: boolean | undefined;
  /** Show the stale-version recovery surface inside the note card. */
  conflict?: boolean | undefined;
  onCompare?: (() => void) | undefined;
  onReload?: (() => void) | undefined;
  onCopy?: (() => void) | undefined;
}

/** SOAP field labels in display order; each maps to a typed content key. */
const soapFields: readonly { field: keyof SoapContent; key: Parameters<typeof chartText>[1] }[] = [
  { field: 'subjective', key: 'subjective' },
  { field: 'objective', key: 'objective' },
  { field: 'assessment', key: 'assessment' },
  { field: 'plan', key: 'plan' },
];

/** The amendment pointer, shown only for a revision that amends another. */
function AmendmentNotice({ amends, locale }: { amends: string | undefined; locale: Locale }) {
  if ((amends ?? '') === '') {
    return null;
  }
  return (
    <p data-testid="amendment-notice">
      {chartText(locale, 'amendmentOf')} {amends}
    </p>
  );
}

/** Template, revision, save time and draft state above the SOAP fields. */
function NoteHeader({
  revision,
  locale,
  editable,
}: {
  revision: NoteRevision;
  locale: Locale;
  editable: boolean;
}) {
  const { tokens } = useTheme();
  return (
    <div data-part="note-header" style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3 style={{ marginBlock: 0 }}>
        {chartText(locale, 'clinicalNote')} · {revision.templateVersion}
      </h3>
      <p data-revision-id={revision.id} data-state={revision.state} style={{ marginBlock: 0 }}>
        {`${chartText(locale, 'revision')} ${revision.version} · ${
          editable ? chartText(locale, 'noteDraft') : chartText(locale, 'noteSigned')
        }`}
        {revision.savedAt === undefined ? null : (
          <>
            {' · '}
            {chartText(locale, 'savedAt')}{' '}
            <time dateTime={revision.savedAt}>
              {formatInstant(revision.savedAt, 'UTC', locale)}
            </time>
          </>
        )}
        {revision.kind === 'amendment' ? (
          <span style={{ paddingInline: tokens.spacing.sm }}>
            {chartText(locale, 'noteAmendment')}
          </span>
        ) : null}
      </p>
    </div>
  );
}

/** The four labelled SOAP textareas with an optional bound Plan error. */
function SoapFields({
  revision,
  locale,
  editable,
  planRequired,
  requiredMissing,
  onContentChange,
}: {
  revision: NoteRevision;
  locale: Locale;
  editable: boolean;
  planRequired: boolean;
  requiredMissing: boolean;
  onContentChange: (field: keyof SoapContent, value: string) => void;
}) {
  const { tokens } = useTheme();
  const planErrorId = `plan-error-${revision.id}`;
  return (
    <div className="stack" data-part="fields" data-layout="single-column">
      {soapFields.map(({ field, key }) => {
        const invalid = field === 'plan' && requiredMissing;
        return (
          <div key={field} style={{ display: 'grid', gap: tokens.spacing.sm }}>
            <label htmlFor={`note-${field}-${revision.id}`}>
              {chartText(locale, key)}
              {field === 'plan' && planRequired ? (
                <span> ({chartText(locale, 'required')})</span>
              ) : null}
            </label>
            <textarea
              id={`note-${field}-${revision.id}`}
              className="field touch"
              aria-label={chartText(locale, key)}
              aria-invalid={invalid ? true : undefined}
              aria-describedby={invalid ? planErrorId : undefined}
              readOnly={!editable}
              value={revision.content[field]}
              onChange={
                editable ? (event) => onContentChange(field, event.target.value) : undefined
              }
              rows={2}
            />
            {invalid ? (
              <p id={planErrorId} data-testid="plan-error" style={{ color: tokens.foreground }}>
                {chartText(locale, 'planError')}
              </p>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}

/** Attestation, sign help and the gated sign action for an editable draft. */
function SignaturePanel({
  revision,
  locale,
  attested,
  signDisabled,
  requiredMissing,
  onAttestationChange,
  onSign,
  stickySignature,
}: {
  revision: NoteRevision;
  locale: Locale;
  attested: boolean;
  signDisabled: boolean;
  requiredMissing: boolean;
  onAttestationChange: (checked: boolean) => void;
  onSign: () => void;
  stickySignature: boolean;
}) {
  const { tokens } = useTheme();
  // On phones the signature is a sticky footer with logical padding; from
  // medium it is an ordinary in-flow row. The Button keeps its own .touch
  // target floor, so this wrapper only arranges the controls.
  const signatureStyle: CSSProperties = stickySignature
    ? {
        display: 'grid',
        gap: tokens.spacing.sm,
        position: 'sticky',
        insetBlockEnd: '0px',
        paddingBlock: tokens.spacing.sm,
        paddingInline: tokens.spacing.md,
        background: 'var(--sc-color-surface-default, #ffffff)',
      }
    : { display: 'grid', gap: tokens.spacing.sm };
  return (
    <div className="touch" data-part="signature" style={signatureStyle}>
      <h4 style={{ marginBlock: 0 }}>{chartText(locale, 'signSection')}</h4>
      <label htmlFor={`note-attestation-${revision.id}`}>{chartText(locale, 'attestation')}</label>
      <input
        id={`note-attestation-${revision.id}`}
        className="touch"
        type="checkbox"
        checked={attested}
        onChange={(event) => onAttestationChange(event.target.checked)}
      />
      {requiredMissing ? (
        <p data-testid="sign-disabled-reason">{chartText(locale, 'signDisabledReason')}</p>
      ) : null}
      <p>{chartText(locale, 'signHelp')}</p>
      <Button onClick={onSign} disabled={signDisabled}>
        {chartText(locale, 'sign')}
      </Button>
    </div>
  );
}

/**
 * Render an editable draft or an immutable signed revision with an amendment
 * action.
 *
 * Layout is presentation only (WEB-07, UX-01): every SOAP field and provenance
 * value stays in the DOM at every breakpoint. Fields always stack in the single
 * reading column; on compact viewports the signature control becomes a sticky
 * footer with a logical scroll-space reservation so it cannot cover a focused
 * field or hide behind the on-screen keyboard. The component is never keyed by
 * a breakpoint, so a rotation re-renders it without discarding the draft held by
 * its parent. All side-aware styles are logical properties and mirror under RTL.
 */
export function NoteEditor({
  revision,
  history,
  locale,
  onContentChange,
  onSign,
  onAmend,
  onMarkEnteredInError,
  planRequired,
  conflict,
  onCompare,
  onReload,
  onCopy,
}: NoteEditorProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const coarsePointer = usePointerCoarse();
  const [attested, setAttested] = useState(false);
  const editable = isEditable(revision);
  const amendable = canAmend(revision);
  const stickySignature = breakpoint === 'compact';
  // The domain still rejects every incomplete draft; this UI gate only marks
  // Plan because that is the field the approved design names as required.
  const requiredMissing =
    planRequired === true && editable && revision.content.plan.trim().length === 0;
  const signDisabled = !attested || requiredMissing;

  // The page class owns the reading cap, safe-area padding and the minmax(0, 1fr)
  // track; the wrapping and scroll reservation keep long clinical text inside
  // the viewport while the sticky signature bar is on screen.
  const editorStyle: CSSProperties = {
    overflowWrap: 'anywhere',
    scrollPaddingBlockEnd: 'var(--sc-space-8, 32px)',
  };

  return (
    <section
      className="page"
      aria-label={chartText(locale, 'clinicalNote')}
      data-breakpoint={breakpoint}
      data-coarse-pointer={coarsePointer}
      data-fields-layout="single-column"
      data-signature-layout={stickySignature ? 'sticky-footer' : 'inline'}
      style={editorStyle}
    >
      <NoteHeader revision={revision} locale={locale} editable={editable} />
      <AmendmentNotice amends={revision.amends} locale={locale} />
      {revision.enteredInError ? (
        <EnteredInErrorBanner detail={revision.enteredInError} locale={locale} />
      ) : null}
      <SoapFields
        revision={revision}
        locale={locale}
        editable={editable}
        planRequired={planRequired === true}
        requiredMissing={requiredMissing}
        onContentChange={onContentChange}
      />
      {conflict === true ? (
        <div data-part="conflict">
          <ConflictState onCompare={onCompare} onReload={onReload} onCopy={onCopy} />
        </div>
      ) : null}
      {revision.state === 'signed' ? (
        <dl
          data-testid="note-provenance"
          data-part="provenance"
          style={{ display: 'grid', gap: tokens.spacing.sm, overflowWrap: 'anywhere' }}
        >
          <dt>{chartText(locale, 'signer')}</dt>
          <dd>{revision.signer}</dd>
          <dt>{chartText(locale, 'signedAt')}</dt>
          <dd>
            <time dateTime={revision.signedAt}>{revision.signedAt}</time>
          </dd>
          <dt>{chartText(locale, 'effectiveDate')}</dt>
          <dd>{revision.effectiveOn}</dd>
          <dt>{chartText(locale, 'checksum')}</dt>
          <dd>{revision.checksum}</dd>
          <dt>{chartText(locale, 'templateVersion')}</dt>
          <dd>{revision.templateVersion}</dd>
        </dl>
      ) : null}
      {editable ? (
        <SignaturePanel
          revision={revision}
          locale={locale}
          attested={attested}
          signDisabled={signDisabled}
          requiredMissing={requiredMissing}
          onAttestationChange={setAttested}
          onSign={onSign}
          stickySignature={stickySignature}
        />
      ) : (
        <p data-testid="readonly-notice" data-part="readonly-notice">
          {chartText(locale, 'readOnly')}
        </p>
      )}
      <div className="cluster" data-part="actions" style={{ gap: tokens.spacing.sm }}>
        {amendable ? (
          <Button variant="secondary" onClick={onAmend}>
            {chartText(locale, 'amend')}
          </Button>
        ) : null}
        {revision.state === 'signed' && revision.enteredInError === undefined ? (
          <Button variant="secondary" onClick={onMarkEnteredInError}>
            {chartText(locale, 'markEnteredInError')}
          </Button>
        ) : null}
      </div>
      <div className="stack" data-part="history">
        <RevisionHistory revisions={history} locale={locale} />
      </div>
    </section>
  );
}

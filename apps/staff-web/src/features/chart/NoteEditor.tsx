import { useState } from 'react';
import type { CSSProperties } from 'react';
import { Button } from '../../components/Button';
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
}

/** SOAP field labels in display order; each maps to a typed content key. */
const soapFields: ReadonlyArray<{ field: keyof SoapContent; key: Parameters<typeof chartText>[1] }> = [
  { field: 'subjective', key: 'subjective' },
  { field: 'objective', key: 'objective' },
  { field: 'assessment', key: 'assessment' },
  { field: 'plan', key: 'plan' },
];

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
}: NoteEditorProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const coarsePointer = usePointerCoarse();
  const [attested, setAttested] = useState(false);
  const editable = isEditable(revision);
  const amendable = canAmend(revision);
  const stickySignature = breakpoint === 'compact';

  // The page class owns the reading cap, safe-area padding and the minmax(0, 1fr)
  // track; the wrapping and scroll reservation keep long clinical text inside
  // the viewport while the sticky signature bar is on screen.
  const editorStyle: CSSProperties = {
    overflowWrap: 'anywhere',
    scrollPaddingBlockEnd: 'var(--sc-space-8, 32px)',
  };

  // On phones the signature is a sticky footer with logical padding; from medium
  // it is an ordinary in-flow row. Either way the Button keeps its own .touch
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
    <section
      className="page"
      aria-label={chartText(locale, 'clinicalNote')}
      data-breakpoint={breakpoint}
      data-coarse-pointer={coarsePointer}
      data-fields-layout="single-column"
      data-signature-layout={stickySignature ? 'sticky-footer' : 'inline'}
      style={editorStyle}
    >
      <h3>{chartText(locale, 'clinicalNote')}</h3>
      <p data-revision-id={revision.id} data-state={revision.state}>
        {`${chartText(locale, 'revision')} ${revision.version} · ${
          editable ? chartText(locale, 'noteDraft') : chartText(locale, 'noteSigned')
        }`}
        {revision.kind === 'amendment' ? (
          <span style={{ paddingInline: tokens.spacing.sm }}>
            {chartText(locale, 'noteAmendment')}
          </span>
        ) : null}
      </p>
      {revision.amends ? (
        <p data-testid="amendment-notice">
          {chartText(locale, 'amendmentOf')} {revision.amends}
        </p>
      ) : null}
      {revision.enteredInError ? (
        <EnteredInErrorBanner detail={revision.enteredInError} locale={locale} />
      ) : null}
      <div className="stack" data-part="fields" data-layout="single-column">
        {soapFields.map(({ field, key }) => (
          <div key={field} style={{ display: 'grid', gap: tokens.spacing.sm }}>
            <label htmlFor={`note-${field}-${revision.id}`}>{chartText(locale, key)}</label>
            <textarea
              id={`note-${field}-${revision.id}`}
              className="field touch"
              aria-label={chartText(locale, key)}
              readOnly={!editable}
              value={revision.content[field]}
              onChange={editable ? (event) => onContentChange(field, event.target.value) : undefined}
              rows={2}
            />
          </div>
        ))}
      </div>
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
        <div className="touch" data-part="signature" style={signatureStyle}>
          <label htmlFor={`note-attestation-${revision.id}`}>{chartText(locale, 'attestation')}</label>
          <input
            id={`note-attestation-${revision.id}`}
            className="touch"
            type="checkbox"
            checked={attested}
            onChange={(event) => setAttested(event.target.checked)}
          />
          <Button onClick={onSign} disabled={!attested}>
            {chartText(locale, 'sign')}
          </Button>
        </div>
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

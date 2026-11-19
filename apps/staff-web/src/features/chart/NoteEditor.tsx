import { useState } from 'react';
import { Button } from '../../components/Button';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
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

/** Render an editable draft or an immutable signed revision with an amendment action. */
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
  const [attested, setAttested] = useState(false);
  const editable = isEditable(revision);
  const amendable = canAmend(revision);

  return (
    <section
      aria-label={chartText(locale, 'clinicalNote')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
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
      {soapFields.map(({ field, key }) => (
        <div key={field} style={{ display: 'grid', gap: tokens.spacing.sm }}>
          <label htmlFor={`note-${field}-${revision.id}`}>{chartText(locale, key)}</label>
          <textarea
            id={`note-${field}-${revision.id}`}
            aria-label={chartText(locale, key)}
            readOnly={!editable}
            value={revision.content[field]}
            onChange={editable ? (event) => onContentChange(field, event.target.value) : undefined}
            rows={2}
          />
        </div>
      ))}
      {revision.state === 'signed' ? (
        <dl data-testid="note-provenance" style={{ display: 'grid', gap: tokens.spacing.sm }}>
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
        <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
          <label htmlFor={`note-attestation-${revision.id}`}>{chartText(locale, 'attestation')}</label>
          <input
            id={`note-attestation-${revision.id}`}
            type="checkbox"
            checked={attested}
            onChange={(event) => setAttested(event.target.checked)}
          />
          <Button onClick={onSign} disabled={!attested}>
            {chartText(locale, 'sign')}
          </Button>
        </div>
      ) : (
        <p data-testid="readonly-notice">{chartText(locale, 'readOnly')}</p>
      )}
      <div style={{ display: 'flex', gap: tokens.spacing.sm }}>
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
      <RevisionHistory revisions={history} locale={locale} />
    </section>
  );
}

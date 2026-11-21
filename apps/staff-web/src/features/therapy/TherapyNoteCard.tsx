import { useState } from 'react';
import { Button } from '../../components/Button';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { EpisodeNote } from './domain';
import { noteStateMessageKey, therapyText } from './messages';

/** A deterministic synthetic signing instant used by the visual foundation. */
const syntheticSignedAt = '2026-03-15T12:00:00Z';

/** Props for one session or progress note revision. */
export interface TherapyNoteCardProps {
  note: EpisodeNote;
  onSign: (noteId: string, body: string, signedAt: string, attested: boolean) => void;
  onAddendum: (noteId: string, body: string) => void;
}

/**
 * Render one note revision. A signed revision is read only and can only be
 * changed through a new editable addendum draft (PT-01).
 */
export function TherapyNoteCard({ note, onSign, onAddendum }: TherapyNoteCardProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: Parameters<typeof therapyText>[1]) => therapyText(locale, key);
  const [body, setBody] = useState(note.body);
  const [attested, setAttested] = useState(false);
  const [addendumOpen, setAddendumOpen] = useState(false);
  const [addendumBody, setAddendumBody] = useState('');
  const inputId = `note-body-${note.id}`;
  const attestationId = `attestation-${note.id}`;
  const addendumId = `addendum-${note.id}`;

  return (
    <article aria-label={`${text('noteAuthor')}: ${note.author}`} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h4>{note.author}</h4>
      <p>{text(noteStateMessageKey[note.state])}</p>
      {note.signedBy === undefined ? null : (
        <p>
          {text('signedBy')}: {note.signedBy}
        </p>
      )}
      {note.state === 'signed' ? (
        <>
          <p data-testid={`read-only-body-${note.id}`}>{note.body}</p>
          <p>{text('signedReadOnly')}</p>
          <Button variant="secondary" onClick={() => setAddendumOpen(true)}>
            {text('addAddendum')}
          </Button>
          {addendumOpen ? (
            <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
              <p>{text('addendumHint')}</p>
              <label htmlFor={addendumId}>{text('addendum')}</label>
              <textarea id={addendumId} value={addendumBody} onChange={(event) => setAddendumBody(event.target.value)} />
              <Button onClick={() => onAddendum(note.id, addendumBody)}>{text('addAddendum')}</Button>
            </div>
          ) : null}
        </>
      ) : (
        <>
          <label htmlFor={inputId}>{text('noteBody')}</label>
          <textarea id={inputId} value={body} onChange={(event) => setBody(event.target.value)} />
          <label htmlFor={attestationId}>{text('attestation')}</label>
          <input
            id={attestationId}
            type="checkbox"
            checked={attested}
            onChange={(event) => setAttested(event.target.checked)}
          />
          <Button
            disabled={!attested || body.trim() === ''}
            onClick={() => onSign(note.id, body, syntheticSignedAt, attested)}
          >
            {text('signNote')}
          </Button>
        </>
      )}
    </article>
  );
}

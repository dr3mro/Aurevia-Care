import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { EpisodeNote } from './domain';
import { therapyText } from './messages';
import { TherapyNoteCard } from './TherapyNoteCard';

/** Props for the session notes panel. */
export interface SessionNotesProps {
  notes: readonly EpisodeNote[];
  onSign: (noteId: string, body: string, signedAt: string, attested: boolean) => void;
  onAddendum: (noteId: string, body: string) => void;
}

/** Render the episode's session notes with their signing controls. */
export function SessionNotes({ notes, onSign, onAddendum }: SessionNotesProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();

  return (
    <section aria-label={therapyText(locale, 'sessionNotes')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{therapyText(locale, 'sessionNotes')}</h3>
      {notes.map((note) => (
        <TherapyNoteCard key={note.id} note={note} onSign={onSign} onAddendum={onAddendum} />
      ))}
    </section>
  );
}

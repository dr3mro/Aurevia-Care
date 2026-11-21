import { useState } from 'react';
import { Button } from '../../components/Button';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { Episode, EpisodeNote } from './domain';
import { therapyText } from './messages';
import { TherapyNoteCard } from './TherapyNoteCard';

/** A deterministic synthetic signing instant used by the visual foundation. */
const syntheticSignedAt = '2026-03-16T12:00:00Z';

/** Props for the progress notes and discharge panel. */
export interface ProgressAndDischargeProps {
  episode: Episode;
  onSignProgress: (noteId: string, body: string, signedAt: string, attested: boolean) => void;
  onAddendum: (noteId: string, body: string) => void;
  onDischarge: (body: string, followUp: string, signedAt: string) => void;
}

/** Render progress notes and the signed discharge that closes the referral. */
export function ProgressAndDischarge({
  episode,
  onSignProgress,
  onAddendum,
  onDischarge,
}: ProgressAndDischargeProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: Parameters<typeof therapyText>[1]) => therapyText(locale, key);
  const [body, setBody] = useState('');
  const [followUp, setFollowUp] = useState('');
  const [attested, setAttested] = useState(false);
  const summary = episode.dischargeSummary;

  return (
    <section aria-label={text('progress')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('progressNotes')}</h3>
      {episode.progressNotes.map((note: EpisodeNote) => (
        <TherapyNoteCard key={note.id} note={note} onSign={onSignProgress} onAddendum={onAddendum} />
      ))}
      <h3>{text('discharge')}</h3>
      {summary === undefined ? (
        <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
          <p>{text('dischargeHint')}</p>
          <label htmlFor="discharge-body">{text('dischargeSummary')}</label>
          <textarea id="discharge-body" value={body} onChange={(event) => setBody(event.target.value)} />
          <label htmlFor="discharge-follow-up">{text('followUp')}</label>
          <textarea
            id="discharge-follow-up"
            value={followUp}
            onChange={(event) => setFollowUp(event.target.value)}
          />
          <label htmlFor="discharge-attestation">{text('attestation')}</label>
          <input
            id="discharge-attestation"
            type="checkbox"
            checked={attested}
            onChange={(event) => setAttested(event.target.checked)}
          />
          <Button
            disabled={!attested || body.trim() === ''}
            onClick={() => onDischarge(body, followUp, syntheticSignedAt)}
          >
            {text('signDischarge')}
          </Button>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: tokens.spacing.sm }} data-testid="signed-discharge">
          <p>{summary.body}</p>
          <p>
            {text('followUp')}: {summary.followUp}
          </p>
          <p>
            {text('signedBy')}: {summary.signedBy} · {formatInstant(summary.signedAt, 'UTC', locale)}
          </p>
          <p>{text('referrerNotified')}</p>
          <p>{text('returnAcknowledged')}</p>
        </div>
      )}
    </section>
  );
}

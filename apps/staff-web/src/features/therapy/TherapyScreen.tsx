import { useEffect, useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { EpisodeOverview } from './EpisodeOverview';
import { GoalsList } from './GoalsList';
import { OutcomeScales } from './OutcomeScales';
import { ProgressAndDischarge } from './ProgressAndDischarge';
import { ReferralWorklist } from './ReferralWorklist';
import { SessionNotes } from './SessionNotes';
import {
  closeReferral,
  createAddendum,
  episodesForTenant,
  findEpisodeForReferral,
  findReferral,
  referralsForTenant,
  signDischarge,
  signNote,
  updateNoteBody,
} from './domain';
import type { Episode, EpisodeNote, Referral } from './domain';
import { therapyText } from './messages';

/** Which note list a command targets. */
export type TherapyNoteKind = 'session' | 'progress';

/** Props for the API-backed therapy screen. */
export interface TherapyScreenProps {
  referrals: readonly Referral[];
  episodes: readonly Episode[];
  organizationId: string;
  selectedReferralId: string;
  nextCursor: string | undefined;
  loadingMore: boolean;
  onSelectReferral: (id: string) => void;
  onAcceptReferral: (id: string) => void;
  onLoadMore: () => void;
  onSignNote: (kind: TherapyNoteKind, noteId: string, body: string, signedAt: string, attested: boolean) => void;
  onAddendum: (kind: TherapyNoteKind, noteId: string, body: string) => void;
  onDischarge: (body: string, followUp: string, signedAt: string) => void;
}

/** Replace one note in the appropriate list of an episode. */
function withNote(episode: Episode, kind: TherapyNoteKind, noteId: string, update: (note: EpisodeNote) => EpisodeNote): Episode {
  if (kind === 'session') {
    return {
      ...episode,
      sessionNotes: episode.sessionNotes.map((note) => (note.id === noteId ? update(note) : note)),
    };
  }
  return {
    ...episode,
    progressNotes: episode.progressNotes.map((note) => (note.id === noteId ? update(note) : note)),
  };
}

/**
 * Compose the tenant-scoped referral worklist and the selected episode. Local
 * copies give immediate feedback on signed notes and discharge; durable
 * commands are owned by the page.
 */
export function TherapyScreen({
  referrals,
  episodes,
  organizationId,
  selectedReferralId,
  nextCursor,
  loadingMore,
  onSelectReferral,
  onAcceptReferral,
  onLoadMore,
  onSignNote,
  onAddendum,
  onDischarge,
}: TherapyScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [localReferrals, setLocalReferrals] = useState<Referral[]>(() => [
    ...referralsForTenant(referrals, organizationId),
  ]);
  const [localEpisodes, setLocalEpisodes] = useState<Episode[]>(() => [
    ...episodesForTenant(episodes, organizationId),
  ]);
  const [announcement, setAnnouncement] = useState('');
  const text = (key: Parameters<typeof therapyText>[1]) => therapyText(locale, key);

  // A reload or tenant switch supplies a fresh projection; no local id from a
  // previous organization is carried forward.
  useEffect(() => {
    setLocalReferrals([...referralsForTenant(referrals, organizationId)]);
    setLocalEpisodes([...episodesForTenant(episodes, organizationId)]);
    setAnnouncement('');
  }, [referrals, episodes, organizationId]);

  const selectedReferral = findReferral(localReferrals, organizationId, selectedReferralId);
  const episode = findEpisodeForReferral(localEpisodes, organizationId, selectedReferralId);
  const name = selectedReferral?.patientName.latinName ?? '';

  function announce() {
    setAnnouncement(`${name}: ${text('commandSent')}`);
  }

  function handleAccept(id: string) {
    announce();
    onAcceptReferral(id);
  }

  function handleSign(kind: TherapyNoteKind, noteId: string, body: string, signedAt: string, attested: boolean) {
    const target = localEpisodes.find((candidate) => candidate.id === episode?.id)!;
    const note = (kind === 'session' ? target.sessionNotes : target.progressNotes).find(
      (candidate) => candidate.id === noteId,
    )!;
    const signed = signNote(updateNoteBody(note, body), {
      signer: 'Synthetic Therapist',
      signedAt,
      attested,
      expectedVersion: note.version,
      patientLocalId: target.patientLocalId,
    });
    setLocalEpisodes((current) =>
      current.map((candidate) =>
        candidate.id === target.id ? withNote(candidate, kind, noteId, () => signed) : candidate,
      ),
    );
    announce();
    onSignNote(kind, noteId, body, signedAt, attested);
  }

  function handleAddendum(kind: TherapyNoteKind, noteId: string, body: string) {
    const target = localEpisodes.find((candidate) => candidate.id === episode?.id)!;
    const source = (kind === 'session' ? target.sessionNotes : target.progressNotes).find(
      (candidate) => candidate.id === noteId,
    )!;
    const draft = createAddendum(source, {
      id: `addendum-${noteId}`,
      author: 'Synthetic Therapist',
      authoredAt: '2026-03-15T12:00:00Z',
      body,
    });
    setLocalEpisodes((current) =>
      current.map((candidate) => {
        if (candidate.id !== target.id) {
          return candidate;
        }
        return kind === 'session'
          ? { ...candidate, sessionNotes: [...candidate.sessionNotes, draft] }
          : { ...candidate, progressNotes: [...candidate.progressNotes, draft] };
      }),
    );
    announce();
    onAddendum(kind, noteId, body);
  }

  function handleDischarge(body: string, followUp: string, signedAt: string) {
    const target = episode!;
    const referral = findReferral(localReferrals, organizationId, target.referralId)!;
    const discharged = signDischarge(target, {
      summaryId: `discharge-${target.id}`,
      body,
      followUp,
      signer: 'Synthetic Therapist',
      signedAt,
      sentToReferrerAt: signedAt,
      attested: true,
      expectedVersion: target.version,
    });
    setLocalEpisodes((current) =>
      current.map((candidate) => (candidate.id === target.id ? discharged : candidate)),
    );
    setLocalReferrals((current) =>
      current.map((candidate) => (candidate.id === referral.id ? closeReferral(referral) : candidate)),
    );
    announce();
    onDischarge(body, followUp, signedAt);
  }

  return (
    <section aria-label={text('therapy')} style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <h2>{text('therapy')}</h2>
      <p>{text('syntheticNotice')}</p>
      <p role="status" data-testid="therapy-status" aria-label={text('status')}>
        {announcement}
      </p>
      <ReferralWorklist
        referrals={localReferrals}
        selectedReferralId={selectedReferralId}
        nextCursor={nextCursor}
        loadingMore={loadingMore}
        onSelect={onSelectReferral}
        onAccept={handleAccept}
        onLoadMore={onLoadMore}
      />
      {episode === undefined ? (
        <p>{text('noEpisode')}</p>
      ) : (
        <>
          <EpisodeOverview episode={episode} />
          <GoalsList goals={episode.goals} />
          <OutcomeScales scales={episode.outcomeScales} />
          <SessionNotes
            notes={episode.sessionNotes}
            onSign={(noteId, body, signedAt, attested) =>
              handleSign('session', noteId, body, signedAt, attested)
            }
            onAddendum={(noteId, body) => handleAddendum('session', noteId, body)}
          />
          <ProgressAndDischarge
            episode={episode}
            onSignProgress={(noteId, body, signedAt, attested) =>
              handleSign('progress', noteId, body, signedAt, attested)
            }
            onAddendum={(noteId, body) => handleAddendum('progress', noteId, body)}
            onDischarge={handleDischarge}
          />
        </>
      )}
    </section>
  );
}

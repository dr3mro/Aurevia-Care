import { useEffect, useMemo, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { useTheme } from '../../theme/ThemeProvider';
import {
  acceptReferral,
  addTherapyAddendum,
  getEpisodeForReferral,
  listReferrals,
  signTherapyDischarge,
  signTherapyNote,
  toEpisode,
  toReferral,
} from './api';
import type { ReferralPageResponse } from './api';
import type { Episode, Referral } from './domain';
import { TherapyScreen } from './TherapyScreen';
import type { TherapyNoteKind } from './TherapyScreen';

/** Props for the API-backed therapy page. */
export interface TherapyPageProps {
  /** Referral selected on first render; tests and previews pin it. */
  initialReferralId?: string | undefined;
}

/** Normalise an unexpected command rejection to a typed transport failure. */
function toTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/**
 * Load the referral worklist and the selected episode from the real API, then
 * dispatch signed-note, addendum and discharge commands through callbacks.
 */
export function TherapyPage({ initialReferralId }: TherapyPageProps) {
  const organizationId = useActiveOrganization();
  const { tokens } = useTheme();
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<ReferralPageResponse['items']>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [selectedReferralId, setSelectedReferralId] = useState(initialReferralId ?? '');
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);

  const referralsResult = useApi<ReferralPageResponse>(
    () => listReferrals(organizationId, cursor),
    [cursor],
  );
  const episodeResult = useApi(
    () =>
      selectedReferralId === ''
        ? Promise.resolve(null)
        : getEpisodeForReferral(selectedReferralId, organizationId),
    [selectedReferralId],
  );

  useEffect(() => {
    if (referralsResult.status !== 'ready') {
      return;
    }
    const page = referralsResult.data;
    setAccumulated((current) => [...current, ...page.items]);
    setNextCursor(page.nextCursor ?? undefined);
  }, [referralsResult.status, referralsResult.data]);

  useEffect(() => {
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
    setCommandError(undefined);
  }, [organizationId]);

  const referrals = useMemo<Referral[]>(
    () => accumulated.map((item) => toReferral(item, organizationId)),
    [accumulated, organizationId],
  );
  const episode = useMemo<Episode | undefined>(
    () =>
      episodeResult.status === 'ready' && episodeResult.data !== null
        ? toEpisode(episodeResult.data, organizationId)
        : undefined,
    [episodeResult.status, episodeResult.data, organizationId],
  );

  useEffect(() => {
    if (selectedReferralId === '' && referrals.length > 0) {
      setSelectedReferralId(referrals[0]!.id);
    }
  }, [referrals, selectedReferralId]);

  const reloadReferrals = referralsResult.reload;
  const reloadEpisode = episodeResult.reload;

  function resetPaging() {
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
    setCommandError(undefined);
  }

  async function runCommand(action: () => Promise<unknown>, after: () => void) {
    try {
      await action();
      setCommandError(undefined);
      after();
    } catch (failure: unknown) {
      setCommandError(toTransportError(failure));
    }
  }

  function noteFor(kind: TherapyNoteKind, noteId: string) {
    const notes = kind === 'session' ? episode!.sessionNotes : episode!.progressNotes;
    return notes.find((note) => note.id === noteId)!;
  }

  function handleAccept(id: string) {
    const target = referrals.find((referral) => referral.id === id)!;
    void runCommand(
      () => acceptReferral(id, target.version, organizationId),
      () => {
        resetPaging();
        reloadReferrals();
      },
    );
  }

  function handleLoadMore() {
    setCursor(nextCursor);
  }

  function handleSignNote(kind: TherapyNoteKind, noteId: string, body: string, signedAt: string, attested: boolean) {
    const note = noteFor(kind, noteId);
    void runCommand(
      () =>
        signTherapyNote(
          episode!.id,
          noteId,
          note.version,
          body,
          signedAt,
          attested,
          organizationId,
        ),
      reloadEpisode,
    );
  }

  function handleAddendum(_kind: TherapyNoteKind, noteId: string, body: string) {
    void runCommand(
      () => addTherapyAddendum(episode!.id, noteId, body, organizationId),
      reloadEpisode,
    );
  }

  function handleDischarge(body: string, followUp: string, signedAt: string) {
    const current = episode!;
    void runCommand(
      () =>
        signTherapyDischarge(
          current.id,
          current.version,
          body,
          followUp,
          signedAt,
          organizationId,
        ),
      () => {
        resetPaging();
        reloadReferrals();
        reloadEpisode();
      },
    );
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      {commandError !== undefined && (
        <div data-testid="therapy-command-error">{StateView.forTransportError(commandError)}</div>
      )}
      {episodeResult.status === 'error' && (
        <div data-testid="therapy-episode-error">
          {StateView.forTransportError(episodeResult.error)}
        </div>
      )}
      {referralsResult.status === 'loading' && referrals.length === 0 ? (
        <LoadingState />
      ) : referralsResult.status === 'error' && referrals.length === 0 ? (
        StateView.forTransportError(referralsResult.error)
      ) : referrals.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <TherapyScreen
            referrals={referrals}
            episodes={episode === undefined ? [] : [episode]}
            organizationId={organizationId}
            selectedReferralId={selectedReferralId}
            nextCursor={nextCursor}
            loadingMore={referralsResult.status === 'loading'}
            onSelectReferral={setSelectedReferralId}
            onAcceptReferral={handleAccept}
            onLoadMore={handleLoadMore}
            onSignNote={handleSignNote}
            onAddendum={handleAddendum}
            onDischarge={handleDischarge}
          />
        </>
      )}
    </div>
  );
}

import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { send } from '../../api/transport';

/** Wire states of the therapy API (PT-01). */
export type ApiTherapyState = 'pending' | 'accepted' | 'closed';
export type ApiEpisodeState = 'active' | 'discharged';
export type ApiGoalState = 'active' | 'met' | 'discontinued';
export type ApiNoteState = 'draft' | 'signed';

/** A referral projection returned by the real API. */
export type ReferralResponse = {
  id: string;
  version: number;
  patientId: string;
  referrer: string;
  reason: string;
  receivedAt: string;
  state: ApiTherapyState;
};

/** A goal projection; the domain keeps the same fields and vocabulary. */
export type ApiGoal = {
  id: string;
  description: string;
  targetOn: string;
  state: ApiGoalState;
};

/** A measurement with its unit preserved verbatim. */
export type ApiMeasurement = {
  id: string;
  recordedAt: string;
  value: number;
  unit: string;
};

/** A baseline measurement recorded at evaluation. */
export type ApiBaselineMeasure = {
  id: string;
  name: string;
  value: number;
  unit: string;
  measuredOn: string;
};

/** A versioned outcome scale with its measurements. */
export type ApiOutcomeScale = {
  id: string;
  name: string;
  code: string;
  scaleVersion: number;
  unit: string;
  measurements: readonly ApiMeasurement[];
};

/** One note revision as returned by the API. */
export type ApiTherapyNote = {
  id: string;
  episodeId: string;
  patientLocalId: string;
  author: string;
  authoredAt: string;
  body: string;
  state: ApiNoteState;
  version: number;
  signedBy: string | undefined;
  signedAt: string | undefined;
  addendumOf: string | undefined;
};

/** A signed discharge summary, or null while the episode is open. */
export type ApiDischargeSummary = {
  id: string;
  body: string;
  followUp: string;
  signedBy: string;
  signedAt: string;
  sentToReferrerAt: string | undefined;
  returnAcknowledgedAt: string | undefined;
};

/** A complete episode projection returned by the real API. */
export type EpisodeResponse = {
  id: string;
  referralId: string;
  patientId: string;
  timeZone: string;
  state: ApiEpisodeState;
  evaluation: string;
  problems: readonly string[];
  baseline: readonly ApiBaselineMeasure[];
  goals: readonly ApiGoal[];
  plannedFrequency: string;
  plannedDuration: string;
  interventions: readonly string[];
  outcomeScales: readonly ApiOutcomeScale[];
  sessionNotes: readonly ApiTherapyNote[];
  progressNotes: readonly ApiTherapyNote[];
  dischargeSummary: ApiDischargeSummary | null;
  version: number;
};

/** One cursor-paginated page of therapy referrals. */
export type ReferralPageResponse = {
  items: readonly ReferralResponse[];
  nextCursor: string | null;
};

/** Sub-paths are not published in the generated contract yet; only the URL is replaced. */
function therapyDescriptor(
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor('POST', '/api/v1/appointments', meta, body);
  return { ...base, url: path };
}

/** Fetch one page of referrals in the active organization. */
export async function listReferrals(
  organizationId: string,
  cursor?: string | undefined,
  state?: ApiTherapyState | undefined,
): Promise<ReferralPageResponse> {
  const base = buildRequestDescriptor('GET', '/api/v1/appointments', {
    activeOrganization: organizationId,
  });
  const params = new URLSearchParams();
  if (cursor !== undefined) {
    params.set('cursor', cursor);
  }
  if (state !== undefined) {
    params.set('state', state);
  }
  const query = params.toString();
  return send<ReferralPageResponse>({
    ...base,
    url: query === '' ? base.url : `${base.url}?${query}`,
  });
}

/** Read one episode by its tenant-local identifier. */
export async function getEpisode(
  id: string,
  organizationId: string,
): Promise<EpisodeResponse> {
  const base = buildRequestDescriptor('GET', '/api/v1/appointments', {
    activeOrganization: organizationId,
  });
  return send<EpisodeResponse>({
    ...base,
    url: `/api/v1/therapy/episodes/${encodeURIComponent(id)}`,
  });
}

/** Read the episode opened by one referral. */
export async function getEpisodeForReferral(
  referralId: string,
  organizationId: string,
): Promise<EpisodeResponse> {
  const base = buildRequestDescriptor('GET', '/api/v1/appointments', {
    activeOrganization: organizationId,
  });
  return send<EpisodeResponse>({
    ...base,
    url: `/api/v1/therapy/referrals/${encodeURIComponent(referralId)}/episode`,
  });
}

/** Accept a referral, guarded by its version and a fresh idempotency key. */
export async function acceptReferral(
  id: string,
  version: number,
  organizationId: string,
): Promise<ReferralResponse> {
  return send<ReferralResponse>(
    therapyDescriptor(`/api/v1/therapy/referrals/${encodeURIComponent(id)}/accept`, {
      activeOrganization: organizationId,
      ifMatch: version,
      idempotencyKey: crypto.randomUUID(),
    }),
  );
}

/** Sign a session or progress note with its body and version precondition. */
export async function signTherapyNote(
  episodeId: string,
  noteId: string,
  version: number,
  body: string,
  signedAt: string,
  attested: boolean,
  organizationId: string,
): Promise<ApiTherapyNote> {
  return send<ApiTherapyNote>(
    therapyDescriptor(
      `/api/v1/therapy/episodes/${encodeURIComponent(episodeId)}/notes/${encodeURIComponent(noteId)}/sign`,
      {
        activeOrganization: organizationId,
        ifMatch: version,
        idempotencyKey: crypto.randomUUID(),
      },
      { body, signedAt, attested },
    ),
  );
}

/** Record an editable addendum to a signed note. */
export async function addTherapyAddendum(
  episodeId: string,
  noteId: string,
  body: string,
  organizationId: string,
): Promise<ApiTherapyNote> {
  return send<ApiTherapyNote>(
    therapyDescriptor(
      `/api/v1/therapy/episodes/${encodeURIComponent(episodeId)}/notes/${encodeURIComponent(noteId)}/addendum`,
      {
        activeOrganization: organizationId,
        idempotencyKey: crypto.randomUUID(),
      },
      { body },
    ),
  );
}

/** Sign a discharge summary; the referral is closed by the same command. */
export async function signTherapyDischarge(
  episodeId: string,
  version: number,
  body: string,
  followUp: string,
  sentToReferrerAt: string,
  organizationId: string,
): Promise<EpisodeResponse> {
  return send<EpisodeResponse>(
    therapyDescriptor(
      `/api/v1/therapy/episodes/${encodeURIComponent(episodeId)}/discharge`,
      {
        activeOrganization: organizationId,
        ifMatch: version,
        idempotencyKey: crypto.randomUUID(),
      },
      { body, followUp, sentToReferrerAt },
    ),
  );
}

/** Map one API referral into the tenant-scoped display type. */
export function toReferral(response: ReferralResponse, organizationId: string) {
  return {
    organizationId,
    id: response.id,
    patientName: { latinName: response.patientId },
    patientLocalId: response.patientId,
    referrer: response.referrer,
    reason: response.reason,
    receivedAt: response.receivedAt,
    state: response.state,
    version: response.version,
  };
}

/** Map one API note into the tenant-scoped display type. */
function toNote(note: ApiTherapyNote, organizationId: string) {
  return {
    organizationId,
    id: note.id,
    episodeId: note.episodeId,
    patientLocalId: note.patientLocalId,
    author: note.author,
    authoredAt: note.authoredAt,
    body: note.body,
    state: note.state,
    version: note.version,
    signedBy: note.signedBy,
    signedAt: note.signedAt,
    addendumOf: note.addendumOf,
  };
}

/** Map one API episode view into the tenant-scoped display type. */
export function toEpisode(response: EpisodeResponse, organizationId: string) {
  return {
    organizationId,
    id: response.id,
    referralId: response.referralId,
    patientName: { latinName: response.patientId },
    patientLocalId: response.patientId,
    timeZone: response.timeZone,
    state: response.state,
    evaluation: response.evaluation,
    problems: response.problems,
    baseline: response.baseline,
    goals: response.goals,
    plannedFrequency: response.plannedFrequency,
    plannedDuration: response.plannedDuration,
    interventions: response.interventions,
    outcomeScales: response.outcomeScales,
    sessionNotes: response.sessionNotes.map((note) => toNote(note, organizationId)),
    progressNotes: response.progressNotes.map((note) => toNote(note, organizationId)),
    dischargeSummary:
      response.dischargeSummary === null
        ? undefined
        : {
            ...response.dischargeSummary,
          },
    version: response.version,
  };
}

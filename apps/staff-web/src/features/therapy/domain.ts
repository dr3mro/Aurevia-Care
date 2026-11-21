import type { PersonName } from '../../i18n/format';

/**
 * Lifecycle of a physical therapy referral (PT-01). Acceptance opens an
 * episode; discharge closes the referral so it cannot be accepted twice.
 */
export type ReferralState = 'pending' | 'accepted' | 'closed';

/** Lifecycle of a therapy episode. */
export type EpisodeState = 'active' | 'discharged';

/** Editability of a clinical note revision; signed revisions are immutable. */
export type NoteState = 'draft' | 'signed';

/** State of one treatment goal. */
export type GoalState = 'active' | 'met' | 'discontinued';

/** A synthetic referral carrying the tenant key and a tenant-local id. */
export interface Referral {
  organizationId: string;
  id: string;
  patientName: PersonName;
  patientLocalId: string;
  referrer: string;
  reason: string;
  receivedAt: string;
  state: ReferralState;
  version: number;
}

/** One treatment goal with a civil target date. */
export interface Goal {
  id: string;
  description: string;
  targetOn: string;
  state: GoalState;
}

/** One measurement carrying the unit exactly as supplied by the source. */
export interface Measurement {
  id: string;
  recordedAt: string;
  value: number;
  unit: string;
}

/** A versioned outcome scale and its units-bearing measurements (PT-01). */
export interface OutcomeScale {
  id: string;
  name: string;
  code: string;
  scaleVersion: number;
  unit: string;
  measurements: readonly Measurement[];
}

/** A baseline measurement recorded at evaluation. */
export interface BaselineMeasure {
  id: string;
  name: string;
  value: number;
  unit: string;
  measuredOn: string;
}

/**
 * One session or progress note revision. An addendum is a new editable draft
 * linked to a signed note; the signed original is never modified.
 */
export interface EpisodeNote {
  organizationId: string;
  id: string;
  episodeId: string;
  patientLocalId: string;
  author: string;
  authoredAt: string;
  body: string;
  state: NoteState;
  version: number;
  signedBy?: string | undefined;
  signedAt?: string | undefined;
  addendumOf?: string | undefined;
}

/** The signed discharge summary that closes an episode (PT-01). */
export interface DischargeSummary {
  id: string;
  body: string;
  followUp: string;
  signedBy: string;
  signedAt: string;
  sentToReferrerAt?: string | undefined;
  returnAcknowledgedAt?: string | undefined;
}

/** A complete synthetic therapy episode owned by one organization. */
export interface Episode {
  organizationId: string;
  id: string;
  referralId: string;
  patientName: PersonName;
  patientLocalId: string;
  timeZone: string;
  state: EpisodeState;
  evaluation: string;
  problems: readonly string[];
  baseline: readonly BaselineMeasure[];
  goals: readonly Goal[];
  plannedFrequency: string;
  plannedDuration: string;
  interventions: readonly string[];
  outcomeScales: readonly OutcomeScale[];
  sessionNotes: readonly EpisodeNote[];
  progressNotes: readonly EpisodeNote[];
  dischargeSummary?: DischargeSummary | undefined;
  version: number;
}

/** A signed note carries a version precondition and an explicit attestation. */
export interface SignNoteCommand {
  signer: string;
  signedAt: string;
  attested: boolean;
  expectedVersion: number;
  patientLocalId: string;
}

/** The content of an editable addendum to a signed note. */
export interface AddendumCommand {
  id: string;
  author: string;
  authoredAt: string;
  body: string;
}

/** Attested inputs required to sign a discharge and close an episode. */
export interface DischargeCommand {
  summaryId: string;
  body: string;
  followUp: string;
  signer: string;
  signedAt: string;
  sentToReferrerAt: string;
  attested: boolean;
  expectedVersion: number;
}

/** Thrown when a referral is not in the state a command requires. */
export class ReferralNotActionable extends Error {
  constructor(id: string, state: ReferralState) {
    super(`Referral ${id} cannot be changed from ${state}`);
    this.name = 'ReferralNotActionable';
  }
}

/** Thrown when an edit or signature targets an immutable signed note. */
export class SignedNoteLocked extends Error {
  constructor(id: string) {
    super(`Signed note is read only: ${id}`);
    this.name = 'SignedNoteLocked';
  }
}

/** Thrown when a signer has not attested to a clinical note. */
export class MissingAttestation extends Error {
  constructor(id: string) {
    super(`Signer attestation is required: ${id}`);
    this.name = 'MissingAttestation';
  }
}

/** Thrown when the caller's note or episode version is stale. */
export class StaleTherapyVersion extends Error {
  constructor(expected: number, actual: number) {
    super(`Stale therapy version: expected ${expected}, supplied ${actual}`);
    this.name = 'StaleTherapyVersion';
  }
}

/** Thrown when a note is associated with a different patient. */
export class WrongTherapyPatient extends Error {
  constructor(id: string) {
    super(`Wrong patient context for therapy note: ${id}`);
    this.name = 'WrongTherapyPatient';
  }
}

/** Thrown when an addendum is requested from a note that cannot be amended. */
export class AddendumNotAllowed extends Error {
  constructor(id: string) {
    super(`Note cannot receive an addendum: ${id}`);
    this.name = 'AddendumNotAllowed';
  }
}

/** Thrown when an episode cannot be discharged. */
export class EpisodeNotDischargeable extends Error {
  constructor(id: string) {
    super(`Episode cannot be discharged: ${id}`);
    this.name = 'EpisodeNotDischargeable';
  }
}

/** Select every referral owned by the active organization. */
export function referralsForTenant(
  referrals: readonly Referral[],
  organizationId: string,
): Referral[] {
  return referrals.filter((referral) => referral.organizationId === organizationId);
}

/** Find one referral by both the tenant key and its local id. */
export function findReferral(
  referrals: readonly Referral[],
  organizationId: string,
  id: string,
): Referral | undefined {
  return referrals.find(
    (referral) => referral.organizationId === organizationId && referral.id === id,
  );
}

/** Select every episode owned by the active organization. */
export function episodesForTenant(episodes: readonly Episode[], organizationId: string): Episode[] {
  return episodes.filter((episode) => episode.organizationId === organizationId);
}

/** Find the episode linked to one referral within the active tenant. */
export function findEpisodeForReferral(
  episodes: readonly Episode[],
  organizationId: string,
  referralId: string,
): Episode | undefined {
  return episodes.find(
    (episode) => episode.organizationId === organizationId && episode.referralId === referralId,
  );
}

/**
 * Accept a pending referral and open its empty episode. The referral version
 * is advanced so a stale accept is rejected by the server.
 */
export function acceptReferral(
  referral: Referral,
  episodeId: string,
): { referral: Referral; episode: Episode } {
  if (referral.state !== 'pending') {
    throw new ReferralNotActionable(referral.id, referral.state);
  }
  return {
    referral: { ...referral, state: 'accepted', version: referral.version + 1 },
    episode: {
      organizationId: referral.organizationId,
      id: episodeId,
      referralId: referral.id,
      patientName: referral.patientName,
      patientLocalId: referral.patientLocalId,
      timeZone: 'UTC',
      state: 'active',
      evaluation: '',
      problems: [],
      baseline: [],
      goals: [],
      plannedFrequency: '',
      plannedDuration: '',
      interventions: [],
      outcomeScales: [],
      sessionNotes: [],
      progressNotes: [],
      version: 1,
    },
  };
}

/** Only a draft note can be edited in the interface. */
export function isEditableNote(note: EpisodeNote): boolean {
  return note.state === 'draft';
}

/** A clean signed original may receive an addendum; a draft or an addendum may not. */
export function canAddAddendum(note: EpisodeNote): boolean {
  return note.state === 'signed' && note.addendumOf === undefined;
}

/** Change the body of a draft note; a signed note is read only. */
export function updateNoteBody(note: EpisodeNote, body: string): EpisodeNote {
  if (!isEditableNote(note)) {
    throw new SignedNoteLocked(note.id);
  }
  return { ...note, body };
}

/** Sign a complete draft, stamping provenance and advancing its version. */
export function signNote(note: EpisodeNote, command: SignNoteCommand): EpisodeNote {
  if (!isEditableNote(note)) {
    throw new SignedNoteLocked(note.id);
  }
  if (!command.attested) {
    throw new MissingAttestation(note.id);
  }
  if (command.expectedVersion !== note.version) {
    throw new StaleTherapyVersion(note.version, command.expectedVersion);
  }
  if (command.patientLocalId !== note.patientLocalId) {
    throw new WrongTherapyPatient(note.id);
  }
  return {
    ...note,
    state: 'signed',
    signedBy: command.signer,
    signedAt: command.signedAt,
    version: note.version + 1,
  };
}

/** Create a new editable draft linked to a clean signed note. */
export function createAddendum(note: EpisodeNote, command: AddendumCommand): EpisodeNote {
  if (!canAddAddendum(note)) {
    throw new AddendumNotAllowed(note.id);
  }
  return {
    organizationId: note.organizationId,
    id: command.id,
    episodeId: note.episodeId,
    patientLocalId: note.patientLocalId,
    author: command.author,
    authoredAt: command.authoredAt,
    body: command.body,
    state: 'draft',
    version: note.version + 1,
    addendumOf: note.id,
  };
}

/** Close an accepted referral when its episode is discharged. */
export function closeReferral(referral: Referral): Referral {
  if (referral.state !== 'accepted') {
    throw new ReferralNotActionable(referral.id, referral.state);
  }
  return { ...referral, state: 'closed', version: referral.version + 1 };
}

/**
 * Sign the discharge summary and mark the episode discharged. A signed
 * progress note is required so closure never skips documented progress.
 */
export function signDischarge(episode: Episode, command: DischargeCommand): Episode {
  if (episode.state !== 'active') {
    throw new EpisodeNotDischargeable(episode.id);
  }
  if (!command.attested) {
    throw new MissingAttestation(episode.id);
  }
  if (command.expectedVersion !== episode.version) {
    throw new StaleTherapyVersion(episode.version, command.expectedVersion);
  }
  if (!episode.progressNotes.some((note) => note.state === 'signed')) {
    throw new EpisodeNotDischargeable(episode.id);
  }
  return {
    ...episode,
    state: 'discharged',
    version: episode.version + 1,
    dischargeSummary: {
      id: command.summaryId,
      body: command.body,
      followUp: command.followUp,
      signedBy: command.signer,
      signedAt: command.signedAt,
      sentToReferrerAt: command.sentToReferrerAt,
    },
  };
}

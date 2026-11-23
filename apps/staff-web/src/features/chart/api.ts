import type { PersonName } from '../../i18n/format';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../api/client';
import { buildRequestDescriptor } from '../../api/client';
import { send } from '../../api/transport';
import type {
  CodedEntry,
  NoteRevision,
  NoteState,
  SoapContent,
  Vital,
  Chart,
  ChartVerification,
} from './domain';

/**
 * A path already declared in the generated contract. Chart, note and patient
 * commands are not published there yet, so this module borrows the shared
 * header builder from a real contract path and substitutes only the URL and
 * method. Tenant, correlation, idempotency and version headers stay owned by
 * `buildRequestDescriptor`; the contract is never edited from a screen story.
 */
const HEADER_SOURCE_PATH: ApiPath = '/api/v1/facilities';

/** Build a descriptor for an unpublished versioned route without faking a contract path. */
function descriptor(
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor('POST', HEADER_SOURCE_PATH, meta, body);
  // The shared descriptor type predates the PUT draft-write command; the
  // transport treats every non-GET method as a protected write, so the runtime
  // semantics are correct and the contract stays untouched.
  return { ...base, url: path, method: method as 'GET' | 'POST' };
}

/** Wire state of a chart entry family (CLN-03). */
export type ApiEntryType = 'PROBLEM' | 'ALLERGY' | 'MEDICATION' | 'VITAL';

/** Wire source of a chart entry (CLN-03). */
export type ApiEntrySource =
  'PATIENT_REPORTED' | 'CLINICIAN_OBSERVED' | 'EXTERNAL_RECORD' | 'DEVICE';

/** Wire clinical assertion of a chart entry (CLN-03). */
export type ApiPresenceState = 'PRESENT' | 'ABSENT' | 'UNKNOWN';

/** Wire verification state of a chart entry (CLN-03). */
export type ApiVerificationState = 'UNVERIFIED' | 'VERIFIED';

/** One immutable chart entry as returned by `GET /api/v1/chart/records/patients/{id}/entries`. */
export interface ChartEntryResponse {
  id: string;
  patientId: string;
  encounterId: string | null;
  type: ApiEntryType;
  originalText: string;
  originalCode: string | null;
  codeSystem: string | null;
  mappedCode: string | null;
  mappedText: string | null;
  catalogVersion: number | null;
  source: ApiEntrySource;
  presence: ApiPresenceState;
  unknownReason: string | null;
  verification: ApiVerificationState;
  verifiedBy: string | null;
  verifiedAt: string | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  measuredAt: string | null;
  value: string | null;
  unit: string | null;
  clinicalAuthor: string | null;
  dataEntryActor: string;
  version: number;
  recordedAt: string;
}

/**
 * The chart projection. The API returns all entries in one response, but the
 * optional `nextCursor` lets a future cursor-paged API be consumed unchanged.
 */
export interface ChartViewResponse {
  entries: readonly ChartEntryResponse[];
  nextCursor?: string | null;
}

/** The patient summary needed to show identity alongside the opaque id. */
export interface PatientViewResponse {
  patient: { id: string; displayName: string };
  duplicates: readonly unknown[];
}

/** Wire lifecycle of one note revision (CLN-05). */
export type ApiNoteState = 'draft' | 'signed' | 'amendment_draft' | 'addendum' | 'entered_in_error';

/** One immutable note revision as returned by the real clinical-note API. */
export interface ClinicalNoteRevisionResponse {
  id: string;
  version: number;
  state: ApiNoteState;
  patientId: string;
  encounterId: string | null;
  title: string;
  content: string;
  language: string;
  templateCode: string;
  templateVersion: number;
  clinicalAuthorSubject: string;
  dataEntrySubject: string;
  effectiveAt: string | null;
  signedAt: string | null;
  signerSubject: string | null;
  attestation: string | null;
  contentChecksum: string | null;
  supersedesVersion: number | null;
  enteredErrorReason: string | null;
  enteredErrorActor: string | null;
  affectedRecipients: string | null;
  createdAt: string;
}

/** The current revision and its append-only history. */
export interface NoteViewResponse {
  current: ClinicalNoteRevisionResponse;
  history: readonly ClinicalNoteRevisionResponse[];
}

/** Body of `POST /api/v1/clinical-notes`. */
export interface CreateNoteBody {
  patientId: string;
  encounterId: string | null;
  title: string;
  content: string;
  language: string;
  templateCode: string;
  templateVersion: number;
  clinicalAuthorSubject: string;
  effectiveAt: string | null;
}

/** Body of `PUT /api/v1/clinical-notes/{id}`. */
export interface EditNoteBody {
  title: string;
  content: string;
}

/** Body of the sign and sign-addendum commands. */
export interface SignNoteBody {
  attestation: string;
}

/** Body of the entered-in-error command. */
export interface EnteredInErrorBody {
  reason: string;
  affectedRecipients: string;
}

/** Read the effective patient that owns a chart (IAM-03). */
export async function getPatient(id: string, organizationId: string): Promise<PatientViewResponse> {
  return send<PatientViewResponse>(
    descriptor('GET', `/api/v1/patients/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Read one page of a patient's longitudinal chart (CLN-03). */
export async function listChartEntries(
  patientId: string,
  organizationId: string,
  cursor?: string,
): Promise<ChartViewResponse> {
  const base = descriptor(
    'GET',
    `/api/v1/chart/records/patients/${encodeURIComponent(patientId)}/entries`,
    { activeOrganization: organizationId },
  );
  if (cursor === undefined) {
    return send<ChartViewResponse>(base);
  }
  const params = new URLSearchParams({ cursor });
  return send<ChartViewResponse>({ ...base, url: `${base.url}?${params.toString()}` });
}

/** Read one chart entry by id (CLN-03). */
export async function getChartEntry(
  id: string,
  organizationId: string,
): Promise<ChartEntryResponse> {
  return send<ChartEntryResponse>(
    descriptor('GET', `/api/v1/chart/records/entries/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Create the first draft of a clinical note (CLN-05). */
export async function createNote(
  body: CreateNoteBody,
  organizationId: string,
): Promise<ClinicalNoteRevisionResponse> {
  return send<ClinicalNoteRevisionResponse>(
    descriptor(
      'POST',
      '/api/v1/clinical-notes',
      {
        activeOrganization: organizationId,
        idempotencyKey: crypto.randomUUID(),
      },
      { ...body },
    ),
  );
}

/** Read the current revision and history of one note (CLN-05). */
export async function getNote(id: string, organizationId: string): Promise<NoteViewResponse> {
  return send<NoteViewResponse>(
    descriptor('GET', `/api/v1/clinical-notes/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Append a new draft version, guarded by the current revision (CLN-05). */
export async function updateNote(
  id: string,
  version: number,
  body: EditNoteBody,
  organizationId: string,
): Promise<ClinicalNoteRevisionResponse> {
  return send<ClinicalNoteRevisionResponse>(
    descriptor(
      'PUT',
      `/api/v1/clinical-notes/${encodeURIComponent(id)}`,
      {
        activeOrganization: organizationId,
        ifMatch: version,
      },
      { ...body },
    ),
  );
}

/** Sign a draft after explicit attestation (CLN-05). */
export async function signNote(
  id: string,
  version: number,
  attestation: string,
  organizationId: string,
): Promise<ClinicalNoteRevisionResponse> {
  return send<ClinicalNoteRevisionResponse>(
    descriptor(
      'POST',
      `/api/v1/clinical-notes/${encodeURIComponent(id)}/sign`,
      { activeOrganization: organizationId, ifMatch: version },
      { attestation },
    ),
  );
}

/** Sign an amendment draft as a linked addendum (CLN-05). */
export async function signAddendum(
  id: string,
  version: number,
  attestation: string,
  organizationId: string,
): Promise<ClinicalNoteRevisionResponse> {
  return send<ClinicalNoteRevisionResponse>(
    descriptor(
      'POST',
      `/api/v1/clinical-notes/${encodeURIComponent(id)}/sign-addendum`,
      { activeOrganization: organizationId, ifMatch: version },
      { attestation },
    ),
  );
}

/** Open a separate amendment draft from a signed revision (CLN-05). */
export async function amendNote(
  id: string,
  version: number,
  organizationId: string,
): Promise<ClinicalNoteRevisionResponse> {
  return send<ClinicalNoteRevisionResponse>(
    descriptor('POST', `/api/v1/clinical-notes/${encodeURIComponent(id)}/amend`, {
      activeOrganization: organizationId,
      ifMatch: version,
    }),
  );
}

/** Record entered-in-error against a signed revision (CLN-05). */
export async function markEnteredInError(
  id: string,
  version: number,
  evidence: { reason: string; affectedRecipients: readonly string[] },
  organizationId: string,
): Promise<ClinicalNoteRevisionResponse> {
  const { reason, affectedRecipients } = evidence;
  return send<ClinicalNoteRevisionResponse>(
    descriptor(
      'POST',
      `/api/v1/clinical-notes/${encodeURIComponent(id)}/entered-in-error`,
      { activeOrganization: organizationId, ifMatch: version },
      { reason, affectedRecipients: JSON.stringify(affectedRecipients) },
    ),
  );
}

/** Map wire presence and verification into the chart domain's explicit states. */
export function toVerification(entry: ChartEntryResponse): ChartVerification {
  if (entry.presence === 'UNKNOWN') {
    return 'unknown';
  }
  if (entry.presence === 'ABSENT') {
    return 'none';
  }
  return entry.verification === 'VERIFIED' ? 'verified' : 'unverified';
}

/** Map one wire chart entry to a coded domain entry, preserving originals. */
export function toCodedEntry(entry: ChartEntryResponse, organizationId: string): CodedEntry {
  return {
    organizationId,
    id: entry.id,
    code: entry.mappedCode ?? entry.originalCode ?? '',
    originalText: entry.originalText,
    display: entry.mappedText ?? entry.originalText,
    source: entry.source,
    effectiveOn: entry.effectiveFrom ?? entry.recordedAt,
    verification: toVerification(entry),
    clinicalAuthor: entry.clinicalAuthor ?? '',
    enteredBy: entry.dataEntryActor,
  };
}

/** Map one wire vital; the API has no time-zone field, so display is labelled UTC. */
export function toVital(entry: ChartEntryResponse, organizationId: string): Vital {
  return {
    organizationId,
    id: entry.id,
    kind: entry.originalText,
    value: Number(entry.value ?? '0'),
    unit: entry.unit ?? '',
    measuredAt: entry.measuredAt ?? entry.effectiveFrom ?? entry.recordedAt,
    timeZone: 'UTC',
  };
}

/**
 * Map a chart view to the existing domain type. Notes and documents are fetched
 * by separate workflows and are intentionally empty on this slice.
 */
export function toChart(
  view: ChartViewResponse,
  organizationId: string,
  patientId: string,
  patientName: PersonName,
): Chart {
  const problems: CodedEntry[] = [];
  const allergies: CodedEntry[] = [];
  const medications: CodedEntry[] = [];
  const vitals: Vital[] = [];
  for (const entry of view.entries) {
    if (entry.type === 'PROBLEM') {
      problems.push(toCodedEntry(entry, organizationId));
    } else if (entry.type === 'ALLERGY') {
      allergies.push(toCodedEntry(entry, organizationId));
    } else if (entry.type === 'MEDICATION') {
      medications.push(toCodedEntry(entry, organizationId));
    } else {
      vitals.push(toVital(entry, organizationId));
    }
  }
  return {
    organizationId,
    localId: patientId,
    patientName,
    problems,
    allergies,
    medications,
    vitals,
    revisions: [],
    documents: [],
  };
}

/** Map each wire note state into the domain's draft or signed state. */
export function toNoteState(state: ApiNoteState): NoteState {
  if (state === 'draft' || state === 'amendment_draft') {
    return 'draft';
  }
  return 'signed';
}

/** The API stores content as one string; the editor shows it as the first SOAP field. */
export function contentToSoap(content: string): SoapContent {
  return { subjective: content, objective: '', assessment: '', plan: '' };
}

/** Serialize the four edited SOAP fields back into the API's single content string. */
export function soapToContent(content: SoapContent): string {
  return [content.subjective, content.objective, content.assessment, content.plan]
    .filter((part) => part.trim().length > 0)
    .join('\n\n');
}

/** Parse a JSON recipient array defensively; an absent or malformed value is empty. */
export function parseRecipients(raw: string | null): string[] {
  if (raw === null) {
    return [];
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed.filter((item): item is string => typeof item === 'string');
    }
    return [];
  } catch {
    return [];
  }
}

/** Map one wire revision to the existing immutable note-revision domain type. */
export function toNoteRevision(
  rev: ClinicalNoteRevisionResponse,
  organizationId: string,
): NoteRevision {
  const amendment = rev.supersedesVersion !== null;
  const enteredInError =
    rev.enteredErrorReason === null
      ? undefined
      : {
          actor: rev.enteredErrorActor ?? '',
          reason: rev.enteredErrorReason,
          affectedRecipients: parseRecipients(rev.affectedRecipients),
          recordedAt: rev.createdAt,
        };
  return {
    organizationId,
    id: rev.id,
    chartLocalId: rev.patientId,
    patientLocalId: rev.patientId,
    encounterLocalId: rev.encounterId ?? '',
    version: rev.version,
    kind: amendment ? 'amendment' : 'original',
    state: toNoteState(rev.state),
    content: contentToSoap(rev.content),
    templateVersion: String(rev.templateVersion),
    ...(rev.signerSubject !== null ? { signer: rev.signerSubject } : {}),
    ...(rev.signedAt !== null ? { signedAt: rev.signedAt } : {}),
    ...(rev.effectiveAt !== null ? { effectiveOn: rev.effectiveAt } : {}),
    ...(rev.contentChecksum !== null ? { checksum: rev.contentChecksum } : {}),
    ...(amendment ? { amends: String(rev.supersedesVersion) } : {}),
    ...(enteredInError !== undefined ? { enteredInError } : {}),
  };
}

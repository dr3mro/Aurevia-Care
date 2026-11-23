import type { PersonName } from '../../i18n/format';

/** Verification state of a coded chart entry, including explicit none/unknown (CLN-03). */
export type ChartVerification = 'verified' | 'unverified' | 'unknown' | 'none';

/** A coded clinical entry that preserves its original code and source text. */
export interface CodedEntry {
  /** Organization that owns the entry; part of every composite lookup. */
  organizationId: string;
  /** Tenant-local identifier; two tenants may use the same value. */
  id: string;
  /** Local or mapped code used for display. */
  code: string;
  /** Original code and text retained after mapping. */
  originalText: string;
  display: string;
  /** Optional local-script display form; absence is not an unknown state. */
  displayLocal?: string;
  source: string;
  /** Civil date on which the entry became clinically effective. */
  effectiveOn: string;
  verification: ChartVerification;
  /** Clinician accountable for the entry, distinct from the data-entry actor. */
  clinicalAuthor: string;
  /** Actor who recorded the entry in the chart. */
  enteredBy: string;
}

/** A measured observation with its unit preserved verbatim (CLN-03, UX-01). */
export interface Vital {
  organizationId: string;
  id: string;
  kind: string;
  value: number;
  unit: string;
  /** UTC instant of the measurement. */
  measuredAt: string;
  /** IANA zone used only for display formatting. */
  timeZone: string;
}

/** The four SOAP fields of a synthetic note draft. */
export interface SoapContent {
  subjective: string;
  objective: string;
  assessment: string;
  plan: string;
}

/** Lifecycle of a note revision; signed revisions are immutable (CLN-05). */
export type NoteState = 'draft' | 'signed';

/** An original note or a linked amendment/addendum revision. */
export type NoteKind = 'original' | 'amendment';

/** Audited evidence recorded when a signed revision is entered in error. */
export interface EnteredInError {
  actor: string;
  reason: string;
  affectedRecipients: readonly string[];
  recordedAt: string;
}

/** One immutable revision of a synthetic SOAP note. */
export interface NoteRevision {
  organizationId: string;
  id: string;
  chartLocalId: string;
  patientLocalId: string;
  encounterLocalId: string;
  /** Optimistic version precondition for draft writes and signing. */
  version: number;
  kind: NoteKind;
  state: NoteState;
  content: SoapContent;
  templateVersion: string;
  signer?: string;
  signedAt?: string;
  effectiveOn?: string;
  checksum?: string;
  /** Identifier of the revision this amendment extends. */
  amends?: string;
  enteredInError?: EnteredInError;
  /** Set when a later amendment has superseded this signed revision. */
  supersededBy?: string;
}

/** Supported synthetic document formats (CLN-07). */
export type DocumentKind = 'pdf' | 'jpeg' | 'png';

/** Language of an immutable rendered document revision. */
export type DocumentLanguage = 'en' | 'ar';

/** Audited evidence recorded when a document association is voided. */
export interface DocumentVoid {
  actor: string;
  reason: string;
  voidedAt: string;
}

/** An association between a chart and a PDF or image artifact. */
export interface DocumentAssociation {
  organizationId: string;
  id: string;
  chartLocalId: string;
  kind: DocumentKind;
  title: string;
  titleLocal?: string;
  version: number;
  language: DocumentLanguage;
  checksum: string;
  /** The chart, order or result this artifact is linked to. */
  linkedTo: string;
  /** Presentation-only rotation; the stored bytes and checksum never change. */
  rotationDegrees: number;
  void?: DocumentVoid;
}

/** A complete synthetic longitudinal chart owned by one organization. */
export interface Chart {
  organizationId: string;
  /** Tenant-local chart identifier; deliberately reused across tenants. */
  localId: string;
  patientName: PersonName;
  problems: readonly CodedEntry[];
  allergies: readonly CodedEntry[];
  medications: readonly CodedEntry[];
  vitals: readonly Vital[];
  revisions: readonly NoteRevision[];
  documents: readonly DocumentAssociation[];
}

/** Command that signs a draft after checking context, fields and attestation. */
export interface SignCommand {
  signer: string;
  attested: boolean;
  signedAt: string;
  effectiveOn: string;
  patientLocalId: string;
  encounterLocalId: string;
  expectedVersion: number;
}

/** Command that records an entered-in-error event on a signed revision. */
export interface EnteredInErrorCommand {
  actor: string;
  reason: string;
  affectedRecipients: readonly string[];
  recordedAt: string;
}

/** Command that voids a document association with an audited reason. */
export interface VoidCommand {
  actor: string;
  reason: string;
  voidedAt: string;
}

/** Thrown when a command targets a signed, immutable revision. */
export class SignedRevisionLocked extends Error {
  /** Name the revision identifier that cannot be modified. */
  constructor(id: string) {
    super(`Signed revision is read only: ${id}`);
    this.name = 'SignedRevisionLocked';
  }
}

/** Thrown when a draft has an empty required SOAP field. */
export class IncompleteDraft extends Error {
  /** Name the revision that lacks a required field. */
  constructor(id: string) {
    super(`Draft has an empty required field: ${id}`);
    this.name = 'IncompleteDraft';
  }
}

/** Thrown when a draft is signed without the signer's attestation. */
export class MissingAttestation extends Error {
  /** Name the revision that cannot be signed. */
  constructor(id: string) {
    super(`Signer attestation is required: ${id}`);
    this.name = 'MissingAttestation';
  }
}

/** Thrown when the patient or encounter context does not match the draft. */
export class WrongPatientContext extends Error {
  /** Name the revision whose context did not match. */
  constructor(id: string) {
    super(`Wrong patient context for revision: ${id}`);
    this.name = 'WrongPatientContext';
  }
}

/** Thrown when the caller's version precondition is stale. */
export class StaleDraftVersion extends Error {
  /** Record the expected and supplied versions. */
  constructor(expected: number, actual: number) {
    super(`Stale draft version: expected ${expected}, supplied ${actual}`);
    this.name = 'StaleDraftVersion';
  }
}

/** Thrown when an amendment is requested from a non-clean signed revision. */
export class AmendmentNotAllowed extends Error {
  /** Name the revision that cannot be amended. */
  constructor(id: string) {
    super(`Revision cannot be amended: ${id}`);
    this.name = 'AmendmentNotAllowed';
  }
}

/** Select every chart owned by the active organization. */
export function chartsForTenant(charts: readonly Chart[], organizationId: string): Chart[] {
  return charts.filter((chart) => chart.organizationId === organizationId);
}

/** Find a chart by both the tenant key and its local identifier. */
export function findChart(
  charts: readonly Chart[],
  organizationId: string,
  localId: string,
): Chart | undefined {
  return charts.find(
    (chart) => chart.organizationId === organizationId && chart.localId === localId,
  );
}

/** Only an unsigned draft can be edited in the UI. */
export function isEditable(revision: NoteRevision): boolean {
  return revision.state === 'draft';
}

/** A clean signed revision may be amended; drafts, errors and superseded revisions may not. */
export function canAmend(revision: NoteRevision): boolean {
  return (
    revision.state === 'signed' &&
    revision.enteredInError === undefined &&
    revision.supersededBy === undefined
  );
}

/**
 * Build a deterministic, clearly synthetic content checksum. This is a visual
 * stand-in for a server-side digest, not a clinical integrity control.
 */
export function syntheticChecksum(content: SoapContent): string {
  const payload = [content.subjective, content.objective, content.assessment, content.plan].join(
    '|',
  );
  const folded = payload
    .split('')
    .reduce((accumulator, character) => (accumulator * 31 + character.charCodeAt(0)) >>> 0, 7);
  return `synthetic:${payload.length}:${folded.toString(16)}`;
}

/** Sign a complete draft, stamping provenance and an immutable checksum. */
export function signDraft(revision: NoteRevision, command: SignCommand): NoteRevision {
  if (revision.state !== 'draft') {
    throw new SignedRevisionLocked(revision.id);
  }
  if (command.expectedVersion !== revision.version) {
    throw new StaleDraftVersion(revision.version, command.expectedVersion);
  }
  const { subjective, objective, assessment, plan } = revision.content;
  if (![subjective, objective, assessment, plan].every((value) => value.trim().length > 0)) {
    throw new IncompleteDraft(revision.id);
  }
  if (!command.attested) {
    throw new MissingAttestation(revision.id);
  }
  if (
    command.patientLocalId !== revision.patientLocalId ||
    command.encounterLocalId !== revision.encounterLocalId
  ) {
    throw new WrongPatientContext(revision.id);
  }
  return {
    ...revision,
    state: 'signed',
    signer: command.signer,
    signedAt: command.signedAt,
    effectiveOn: command.effectiveOn,
    checksum: syntheticChecksum(revision.content),
  };
}

/** Change one SOAP field of a draft; signed revisions cannot be edited. */
export function updateDraft(
  revision: NoteRevision,
  field: keyof SoapContent,
  value: string,
): NoteRevision {
  if (!isEditable(revision)) {
    throw new SignedRevisionLocked(revision.id);
  }
  return { ...revision, content: { ...revision.content, [field]: value } };
}

/** Create an editable amendment draft linked to a clean signed revision. */
export function createAmendment(revision: NoteRevision, id: string): NoteRevision {
  if (!canAmend(revision)) {
    throw new AmendmentNotAllowed(revision.id);
  }
  return {
    organizationId: revision.organizationId,
    id,
    chartLocalId: revision.chartLocalId,
    patientLocalId: revision.patientLocalId,
    encounterLocalId: revision.encounterLocalId,
    version: revision.version + 1,
    kind: 'amendment',
    state: 'draft',
    content: { ...revision.content },
    templateVersion: revision.templateVersion,
    amends: revision.id,
  };
}

/** Mark a signed revision as superseded by an amendment without editing its content. */
export function markSuperseded(revision: NoteRevision, amendmentId: string): NoteRevision {
  return { ...revision, supersededBy: amendmentId };
}

/** Preserve the content and prior revision while recording entered-in-error evidence. */
export function markEnteredInError(
  revision: NoteRevision,
  command: EnteredInErrorCommand,
): NoteRevision {
  return {
    ...revision,
    enteredInError: {
      actor: command.actor,
      reason: command.reason,
      affectedRecipients: [...command.affectedRecipients],
      recordedAt: command.recordedAt,
    },
  };
}

/** Return the revision with the highest version, or undefined for an empty history. */
export function currentRevision(revisions: readonly NoteRevision[]): NoteRevision | undefined {
  if (revisions.length === 0) {
    return undefined;
  }
  return revisions.reduce((latest, revision) =>
    revision.version > latest.version ? revision : latest,
  );
}

/** Replace one revision by tenant key and local id, leaving all others untouched. */
export function replaceRevision(
  revisions: readonly NoteRevision[],
  organizationId: string,
  id: string,
  update: (revision: NoteRevision) => NoteRevision,
): NoteRevision[] {
  return revisions.map((revision) =>
    revision.organizationId === organizationId && revision.id === id ? update(revision) : revision,
  );
}

/** Only associations without a void record are shown as live documents. */
export function visibleDocuments(documents: readonly DocumentAssociation[]): DocumentAssociation[] {
  return documents.filter((document) => document.void === undefined);
}

/** Void an association with an audited reason; the original row is not mutated. */
export function voidDocument(
  document: DocumentAssociation,
  command: VoidCommand,
): DocumentAssociation {
  return {
    ...document,
    void: { actor: command.actor, reason: command.reason, voidedAt: command.voidedAt },
  };
}

/** Normalize display rotation to 0/90/180/270 without touching the stored checksum. */
export function rotateDocument(
  document: DocumentAssociation,
  deltaDegrees: number,
): DocumentAssociation {
  const normalized = (((document.rotationDegrees + deltaDegrees) % 360) + 360) % 360;
  return { ...document, rotationDegrees: normalized };
}

/** Replace one document by tenant key and local id, leaving all others untouched. */
export function replaceDocument(
  documents: readonly DocumentAssociation[],
  organizationId: string,
  id: string,
  update: (document: DocumentAssociation) => DocumentAssociation,
): DocumentAssociation[] {
  return documents.map((document) =>
    document.organizationId === organizationId && document.id === id ? update(document) : document,
  );
}

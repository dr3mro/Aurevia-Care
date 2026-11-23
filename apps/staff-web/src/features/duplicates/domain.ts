/**
 * Wire types and pure approval rules for same-tenant duplicate review (IAM-04).
 *
 * Field names mirror the authoritative Java records in
 * `com.aurevia.smartclinic.patient.consolidation`. The routes are not yet
 * published in the generated OpenAPI contract, so this module owns the wire
 * projections and the client-side guards; the API stays authoritative for
 * authorization, locking and the actual consolidation.
 */

/** One likely-duplicate pair from `GET /api/v1/duplicate-reviews`. */
export interface DuplicatePairWire {
  survivorId: string;
  survivorName: string;
  duplicateId: string;
  duplicateName: string;
}

/** A single conflicting demographic field shown side by side. */
export interface ConflictWire {
  field: string;
  survivorValue: string;
  duplicateValue: string;
}

/**
 * A same-tenant dependency of the duplicate record. `ambiguous && !resolved`
 * is the exact condition the server refuses to consolidate (IAM-04).
 */
export interface DependencyWire {
  id: string;
  kind: string;
  reference: string;
  ambiguous: boolean;
  resolved: boolean;
}

/** An open review ticket. `status` is folded from the append-only history. */
export interface ReviewWire {
  id: string;
  survivorId: string;
  duplicateId: string;
  openedBySubject: string;
  version: number;
  status: string;
}

/** The patient subset a review displays; `null` preserves an absent value. */
export interface PatientWire {
  id: string;
  version: number;
  mrn: string;
  mrnIssuer: string;
  displayName: string;
  dob: string | null;
  dobUnknownReason: string | null;
  language: string;
}

/** Everything shown before approval; `review` is null until a ticket is opened. */
export interface ReviewDetailWire {
  review: ReviewWire | null;
  survivor: PatientWire;
  duplicate: PatientWire;
  conflicts: readonly ConflictWire[];
  dependencies: readonly DependencyWire[];
  dryRun: string;
  chartVisibilityId: string;
}

/** The appended consolidation event returned by an approval. */
export interface ConsolidationWire {
  id: string;
  reviewId: string;
  survivorId: string;
  duplicateId: string;
  version: number;
  status: string;
  approverSubject: string;
  openedBySubject: string;
  reason: string;
  correctionReason: string | null;
}

/** Request body for `POST /api/v1/duplicate-reviews`. */
export interface OpenRequestBody {
  survivorId: string;
  duplicateId: string;
}

/** Request body for the second-reviewer approval command. */
export interface ApprovalRequestBody {
  reason: string;
  confirmedIdentifiers: readonly string[];
  alternateProcess: string;
}

/** Why an approval cannot be attempted, or `undefined` when it can. */
export type ApprovalBlocker =
  'no-open-review' | 'same-reviewer' | 'unresolved-dependencies' | undefined;

/** Field-keyed local validation result for the approval form. */
export interface ApprovalFormErrors {
  reason?: 'reason';
  confirmation?: 'confirmation';
}

/** Which part of the high-risk identifier confirmation failed. */
export type ConfirmationIssue =
  'both' | 'alternate-format' | 'identifiers-required' | 'invalid-identifier' | undefined;

const REASON_PATTERN = /^[A-Za-z0-9 _.:'-]{3,200}$/;
const IDENTIFIER_REFERENCE_PATTERN = /^[A-Za-z0-9_.:-]{2,64}@[A-Za-z0-9_.:-]{2,32}$/;
const ALTERNATE_PROCESS_PATTERN = /^[a-z_]{2,32}$/;

/** Build the issuer-scoped reference the server asks a reviewer to confirm. */
export function identifierReference(patient: PatientWire): string {
  return `${patient.mrn}@${patient.mrnIssuer}`;
}

/** The two issuer-scoped references a reviewer may confirm, in survivor order. */
export function identifierReferences(detail: ReviewDetailWire): readonly [string, string] {
  return [identifierReference(detail.survivor), identifierReference(detail.duplicate)];
}

/** True when an ambiguous dependency has not been resolved by an authorized workflow. */
export function hasUnresolvedBlockers(dependencies: readonly DependencyWire[]): boolean {
  return dependencies.some((dependency) => dependency.ambiguous && !dependency.resolved);
}

/**
 * Decide whether the current reviewer may attempt an approval.
 *
 * The order mirrors `ConsolidationService.consolidate`: the opener is refused
 * first, then unresolved dependencies. A missing ticket blocks before either.
 */
export function approvalBlocker(
  review: ReviewWire | null,
  dependencies: readonly DependencyWire[],
  currentSubject: string,
): ApprovalBlocker {
  if (review === null) {
    return 'no-open-review';
  }
  if (currentSubject === review.openedBySubject) {
    return 'same-reviewer';
  }
  if (hasUnresolvedBlockers(dependencies)) {
    return 'unresolved-dependencies';
  }
  return undefined;
}

/**
 * Validate the IAM-04 high-risk confirmation: either two distinct, well-formed
 * issuer-scoped references, or one bounded alternate-process code, never both.
 */
export function confirmationIssue(
  confirmedIdentifiers: readonly string[],
  alternateProcess: string,
): ConfirmationIssue {
  const hasConfirmed = confirmedIdentifiers.length > 0;
  const hasAlternate = alternateProcess.trim() !== '';
  if (hasConfirmed && hasAlternate) {
    return 'both';
  }
  if (hasAlternate) {
    return ALTERNATE_PROCESS_PATTERN.test(alternateProcess.trim()) ? undefined : 'alternate-format';
  }
  if (confirmedIdentifiers.length < 2 || new Set(confirmedIdentifiers).size < 2) {
    return 'identifiers-required';
  }
  if (confirmedIdentifiers.some((value) => !IDENTIFIER_REFERENCE_PATTERN.test(value))) {
    return 'invalid-identifier';
  }
  return undefined;
}

/** Validate the approval form at the trust boundary before any command is sent. */
export function validateApprovalForm(
  reason: string,
  confirmedIdentifiers: readonly string[],
  alternateProcess: string,
): ApprovalFormErrors {
  const errors: ApprovalFormErrors = {};
  if (!REASON_PATTERN.test(reason.trim())) {
    errors.reason = 'reason';
  }
  if (confirmationIssue(confirmedIdentifiers, alternateProcess) !== undefined) {
    errors.confirmation = 'confirmation';
  }
  return errors;
}

/** True when an approval form has no local validation errors. */
export function approvalFormValid(errors: ApprovalFormErrors): boolean {
  return errors.reason === undefined && errors.confirmation === undefined;
}

/** Build the approval body; organization and actor are never sent by the client. */
export function approvalBody(
  reason: string,
  confirmedIdentifiers: readonly string[],
  alternateProcess: string,
): ApprovalRequestBody {
  return {
    reason: reason.trim(),
    confirmedIdentifiers: [...confirmedIdentifiers],
    alternateProcess: alternateProcess.trim(),
  };
}

/** Tag a name for bidirectional display from its script, not from the UI locale. */
export function languageForName(text: string): string {
  return /[\u0600-\u06FF]/.test(text) ? 'ar' : 'en';
}

/** Toggle one confirmed reference without duplicating it. */
export function toggleReference(
  confirmed: readonly string[],
  reference: string,
): readonly string[] {
  return confirmed.includes(reference)
    ? confirmed.filter((value) => value !== reference)
    : [...confirmed, reference];
}

import type { Locale } from '../../i18n/messages';
import { formatCivilDate } from '../../i18n/format';
import { dobReasonKey, patientsText } from './messages';

/**
 * Wire projection of one patient returned by `GET/POST /api/v1/patients`.
 * Field names come from the authoritative `patient.Patient` Java record; the
 * patient routes are not yet published in the generated OpenAPI contract.
 */
export interface PatientResponse {
  id: string;
  version: number;
  mrn: string;
  mrnIssuer: string;
  displayName: string;
  normalizedName: string;
  dob: string | null;
  dobUnknownReason: string | null;
  language: string;
  contacts: string;
  identifiers: string;
  consents: string;
}

/** A patient and its synthetic likely-duplicate candidates (IAM-03). */
export interface PatientViewResponse {
  patient: PatientResponse;
  duplicates: readonly PatientResponse[];
}

/** Tenant-scoped display projection of one patient (IAM-03). */
export interface Patient {
  organizationId: string;
  id: string;
  version: number;
  displayName: string;
  mrn: string;
  mrnIssuer: string;
  dob: string | null;
  dobUnknownReason: string | null;
  language: string;
}

/** Minimal identity of one likely-duplicate candidate; never merged here. */
export interface DuplicateCandidate {
  id: string;
  displayName: string;
  mrn: string;
}

/** The data the overview screen shows: one patient plus its warnings. */
export interface PatientOverview {
  patient: Patient;
  duplicates: readonly DuplicateCandidate[];
}

/** Every field collected by the registration form. */
export type RegistrationField =
  'displayName' | 'mrn' | 'issuer' | 'dob' | 'dobUnknownReason' | 'language';

/** The editable registration draft; date and reason are mutually exclusive. */
export type RegistrationDraft = Record<RegistrationField, string>;

/** Localized validation message key for one registration field. */
export type ValidationKey =
  | 'errorDisplayName'
  | 'errorMrn'
  | 'errorIssuer'
  | 'errorDob'
  | 'errorDobExclusive'
  | 'errorDobReason'
  | 'errorLanguage';

/** Field-keyed validation result for one form submission. */
export type RegistrationFieldErrors = Partial<Record<RegistrationField, ValidationKey>>;

/** Localized validation key for a rejected search. */
export type SearchErrorKey = 'searchTooShort';

/** Empty draft reused initially and after a successful registration. */
export const EMPTY_REGISTRATION: RegistrationDraft = {
  displayName: '',
  mrn: '',
  issuer: '',
  dob: '',
  dobUnknownReason: '',
  language: '',
};

const NAME_PATTERN = /^[\p{L}\p{M} '.-]{2,120}$/u;
const MRN_PATTERN = /^[A-Za-z0-9_.:-]{2,64}$/;
const ISSUER_PATTERN = /^[A-Za-z0-9_.:-]{2,32}$/;
const DOB_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const REASON_PATTERN = /^[a-z_]{2,32}$/;
const MIN_SEARCH_LENGTH = 2;
const SUPPORTED_LANGUAGES: readonly string[] = ['en', 'ar'];

const SERVER_FIELD_BY_CODE: Readonly<Record<string, RegistrationField>> = {
  INVALID_NAME: 'displayName',
  INVALID_MRN: 'mrn',
  INVALID_ISSUER: 'issuer',
  INVALID_DOB: 'dob',
};

const VALIDATION_KEY_BY_FIELD: Record<RegistrationField, ValidationKey> = {
  displayName: 'errorDisplayName',
  mrn: 'errorMrn',
  issuer: 'errorIssuer',
  dob: 'errorDob',
  dobUnknownReason: 'errorDobReason',
  language: 'errorLanguage',
};

/** Tag a name for bidirectional display from its script, not from the UI locale. */
export function languageForName(text: string): string {
  return /[\u0600-\u06FF]/.test(text) ? 'ar' : 'en';
}

/** Map one wire patient to the tenant-scoped display projection. */
export function toPatient(wire: PatientResponse, organizationId: string): Patient {
  return {
    organizationId,
    id: wire.id,
    version: wire.version,
    displayName: wire.displayName,
    mrn: wire.mrn,
    mrnIssuer: wire.mrnIssuer,
    dob: wire.dob,
    dobUnknownReason: wire.dobUnknownReason,
    language: wire.language,
  };
}

/** Map a wire patient view to the overview projection without merging records. */
export function toPatientOverview(
  wire: PatientViewResponse,
  organizationId: string,
): PatientOverview {
  return {
    patient: toPatient(wire.patient, organizationId),
    duplicates: wire.duplicates.map((candidate) => ({
      id: candidate.id,
      displayName: candidate.displayName,
      mrn: candidate.mrn,
    })),
  };
}

/** Format the civil date of birth, or its coded unknown reason, for display. */
export function formatDob(patient: Patient, locale: Locale): string {
  if (patient.dob !== null) {
    return formatCivilDate(patient.dob, locale);
  }
  return patientsText(locale, dobReasonKey(patient.dobUnknownReason));
}

/**
 * Validate a search before it is sent. The API rejects names shorter than two
 * characters, so the screen refuses them locally (PRD-01, IAM-03).
 */
export function searchError(name: string): SearchErrorKey | undefined {
  return name.trim().length < MIN_SEARCH_LENGTH ? 'searchTooShort' : undefined;
}

/** Validate the mutually exclusive date of birth and unknown-date reason. */
function validateDateOfBirth(draft: RegistrationDraft): RegistrationFieldErrors {
  const hasDob = draft.dob.trim() !== '';
  const hasReason = draft.dobUnknownReason.trim() !== '';
  if (hasDob && hasReason) {
    return { dob: 'errorDobExclusive' };
  }
  if (hasDob && !DOB_PATTERN.test(draft.dob.trim())) {
    return { dob: 'errorDob' };
  }
  if (!hasDob && !hasReason) {
    return { dob: 'errorDob' };
  }
  if (!hasDob && !REASON_PATTERN.test(draft.dobUnknownReason.trim())) {
    return { dobUnknownReason: 'errorDobReason' };
  }
  return {};
}

/**
 * Validate a registration draft at the trust boundary. The patterns mirror
 * `PatientService` so a bad draft is shown in the summary before any command.
 */
export function validateRegistration(draft: RegistrationDraft): RegistrationFieldErrors {
  const errors: RegistrationFieldErrors = {};
  if (!NAME_PATTERN.test(draft.displayName.trim())) {
    errors.displayName = 'errorDisplayName';
  }
  if (!MRN_PATTERN.test(draft.mrn.trim())) {
    errors.mrn = 'errorMrn';
  }
  if (!ISSUER_PATTERN.test(draft.issuer.trim())) {
    errors.issuer = 'errorIssuer';
  }
  Object.assign(errors, validateDateOfBirth(draft));
  if (!SUPPORTED_LANGUAGES.includes(draft.language.trim())) {
    errors.language = 'errorLanguage';
  }
  return errors;
}

/** Build the `RegisterRequest` body; organization and author are never sent. */
export function registrationBody(draft: RegistrationDraft): Record<string, unknown> {
  return {
    displayName: draft.displayName.trim(),
    mrn: draft.mrn.trim(),
    issuer: draft.issuer.trim(),
    dob: draft.dob.trim() === '' ? null : draft.dob.trim(),
    dobUnknownReason: draft.dobUnknownReason.trim() === '' ? null : draft.dobUnknownReason.trim(),
    language: draft.language.trim(),
    contacts: '{}',
    identifiers: '[]',
    consents: '[]',
  };
}

/** Resolve the registration field named by a server validation code. */
export function serverFieldForCode(code: string | undefined): RegistrationField | undefined {
  return SERVER_FIELD_BY_CODE[code ?? ''];
}

/** Resolve the localized validation key for one registration field. */
export function validationKeyForField(field: RegistrationField): ValidationKey {
  return VALIDATION_KEY_BY_FIELD[field];
}

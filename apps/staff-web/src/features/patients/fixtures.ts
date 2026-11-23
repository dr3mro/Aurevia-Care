import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { PatientResponse, PatientViewResponse, RegistrationDraft } from './domain';

/** The two synthetic tenants; their local MRNs intentionally collide. */
export const alphaOrganizationId = required(syntheticOrganizations[0]).id;
export const betaOrganizationId = required(syntheticOrganizations[1]).id;

/** Synthetic alpha patients with mixed-script and unknown-date identities. */
export const syntheticAlphaPatients: readonly PatientResponse[] = [
  {
    id: 'patient-a1',
    version: 1,
    mrn: 'MRN-1001',
    mrnIssuer: 'SYNTHETIC',
    displayName: 'Layla Haddad',
    normalizedName: 'layla haddad',
    dob: '1990-05-12',
    dobUnknownReason: null,
    language: 'en',
    contacts: '{}',
    identifiers: '[]',
    consents: '[]',
  },
  {
    id: 'patient-a2',
    version: 2,
    mrn: 'MRN-1002',
    mrnIssuer: 'SYNTHETIC',
    displayName: 'ليلى حداد',
    normalizedName: 'ليلى حداد',
    dob: null,
    dobUnknownReason: 'not_disclosed',
    language: 'ar',
    contacts: '{}',
    identifiers: '[]',
    consents: '[]',
  },
];

/** Synthetic beta patients reusing the same issuer-scoped MRN as alpha. */
export const syntheticBetaPatients: readonly PatientResponse[] = [
  {
    id: 'patient-b1',
    version: 1,
    mrn: 'MRN-1001',
    mrnIssuer: 'SYNTHETIC',
    displayName: 'Synthetic Beta Patient',
    normalizedName: 'synthetic beta patient',
    dob: '1988-02-01',
    dobUnknownReason: null,
    language: 'en',
    contacts: '{}',
    identifiers: '[]',
    consents: '[]',
  },
];

/** The Arabic-script patient used by the mixed-script rendering tests. */
export const arabicPatientResponse: PatientResponse = required(syntheticAlphaPatients[1]);

/** A patient view whose likely-duplicate warning is present. */
export const syntheticPatientView: PatientViewResponse = {
  patient: required(syntheticAlphaPatients[0]),
  duplicates: [required(syntheticAlphaPatients[1])],
};

/** A patient view with no likely-duplicate warning. */
export const syntheticPatientViewNoDuplicates: PatientViewResponse = {
  patient: required(syntheticAlphaPatients[0]),
  duplicates: [],
};

/** A valid registration draft used by the command tests. */
export const syntheticRegistrationDraft: RegistrationDraft = {
  displayName: 'Synthetic New Patient',
  mrn: 'MRN-2001',
  issuer: 'SYNTHETIC',
  dob: '1992-08-03',
  dobUnknownReason: '',
  language: 'en',
};

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}

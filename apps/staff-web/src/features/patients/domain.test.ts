import { describe, expect, it } from 'vitest';
import {
  EMPTY_REGISTRATION,
  formatDob,
  languageForName,
  registrationBody,
  searchError,
  serverFieldForCode,
  toPatient,
  toPatientOverview,
  validateRegistration,
  validationKeyForField,
} from './domain';
import type { PatientResponse, PatientViewResponse } from './domain';
import { alphaOrganizationId } from './fixtures';

function wire(overrides: Partial<PatientResponse> = {}): PatientResponse {
  return {
    id: 'patient-1',
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
    ...overrides,
  };
}

function view(duplicates: readonly PatientResponse[] = []): PatientViewResponse {
  return { patient: wire(), duplicates };
}

describe('patient name language', () => {
  it('tags an Arabic-script name as Arabic', () => {
    expect(languageForName('ليلى حداد')).toBe('ar');
  });

  it('tags a Latin-script name as English', () => {
    expect(languageForName('Layla Haddad')).toBe('en');
  });
});

describe('patient projections', () => {
  it('maps a wire patient to the tenant-scoped display type', () => {
    expect(toPatient(wire(), alphaOrganizationId)).toEqual({
      organizationId: alphaOrganizationId,
      id: 'patient-1',
      version: 1,
      displayName: 'Layla Haddad',
      mrn: 'MRN-1001',
      mrnIssuer: 'SYNTHETIC',
      dob: '1990-05-12',
      dobUnknownReason: null,
      language: 'en',
    });
  });

  it('maps likely duplicates from the patient view without merging them', () => {
    const duplicate = wire({ id: 'patient-2', displayName: 'ليلى حداد', dob: '1990-05-12' });
    const overview = toPatientOverview(view([duplicate]), alphaOrganizationId);
    expect(overview.patient.id).toBe('patient-1');
    expect(overview.duplicates).toEqual([
      { id: 'patient-2', displayName: 'ليلى حداد', mrn: 'MRN-1001' },
    ]);
    expect(toPatientOverview(view(), alphaOrganizationId).duplicates).toEqual([]);
  });
});

describe('date of birth display', () => {
  it('formats a civil date in the active locale', () => {
    expect(formatDob(toPatient(wire(), alphaOrganizationId), 'en')).toBe('May 12, 1990');
  });

  it('shows a localized reason when the date is unknown', () => {
    const patient = toPatient(
      wire({ dob: null, dobUnknownReason: 'not_disclosed' }),
      alphaOrganizationId,
    );
    expect(formatDob(patient, 'en')).toBe('Not disclosed');
    expect(formatDob(patient, 'ar')).toBe('لم يُفصح عنه');
  });

  it('falls back to a neutral reason for an unmapped code', () => {
    const patient = toPatient(wire({ dob: null, dobUnknownReason: 'other' }), alphaOrganizationId);
    expect(formatDob(patient, 'en')).toBe('Reason not recorded');
  });
});

describe('search validation', () => {
  it('rejects a name shorter than two characters', () => {
    expect(searchError(' A ')).toBe('searchTooShort');
    expect(searchError('')).toBe('searchTooShort');
  });

  it('accepts a name of at least two characters', () => {
    expect(searchError('La')).toBeUndefined();
    expect(searchError('ليلى')).toBeUndefined();
  });
});

describe('registration validation', () => {
  it('accepts a draft with a civil date of birth', () => {
    expect(
      validateRegistration({
        ...EMPTY_REGISTRATION,
        displayName: 'Layla Haddad',
        mrn: 'MRN-1001',
        issuer: 'SYNTHETIC',
        dob: '1990-05-12',
        language: 'en',
      }),
    ).toEqual({});
  });

  it('accepts a draft with a coded unknown-date reason', () => {
    expect(
      validateRegistration({
        ...EMPTY_REGISTRATION,
        displayName: 'ليلى حداد',
        mrn: 'MRN-1002',
        issuer: 'SYNTHETIC',
        dobUnknownReason: 'not_known',
        language: 'ar',
      }),
    ).toEqual({});
  });

  it('reports every invalid field on an empty draft', () => {
    expect(validateRegistration(EMPTY_REGISTRATION)).toEqual({
      displayName: 'errorDisplayName',
      mrn: 'errorMrn',
      issuer: 'errorIssuer',
      dob: 'errorDob',
      language: 'errorLanguage',
    });
  });

  it('rejects malformed names, MRNs and issuers', () => {
    const errors = validateRegistration({
      ...EMPTY_REGISTRATION,
      displayName: 'A1',
      mrn: '!!',
      issuer: '??',
      dob: '1990-05-12',
      language: 'en',
    });
    expect(errors).toEqual({
      displayName: 'errorDisplayName',
      mrn: 'errorMrn',
      issuer: 'errorIssuer',
    });
  });

  it('rejects a date of birth supplied together with an unknown reason', () => {
    const errors = validateRegistration({
      ...EMPTY_REGISTRATION,
      displayName: 'Layla Haddad',
      mrn: 'MRN-1001',
      issuer: 'SYNTHETIC',
      dob: '1990-05-12',
      dobUnknownReason: 'not_known',
      language: 'en',
    });
    expect(errors).toEqual({ dob: 'errorDobExclusive' });
  });

  it('rejects a malformed civil date', () => {
    const errors = validateRegistration({
      ...EMPTY_REGISTRATION,
      displayName: 'Layla Haddad',
      mrn: 'MRN-1001',
      issuer: 'SYNTHETIC',
      dob: '12-05-1990',
      language: 'en',
    });
    expect(errors).toEqual({ dob: 'errorDob' });
  });

  it('rejects a malformed unknown-date reason', () => {
    const errors = validateRegistration({
      ...EMPTY_REGISTRATION,
      displayName: 'Layla Haddad',
      mrn: 'MRN-1001',
      issuer: 'SYNTHETIC',
      dobUnknownReason: 'NOT VALID',
      language: 'en',
    });
    expect(errors).toEqual({ dobUnknownReason: 'errorDobReason' });
  });
});

describe('registration body', () => {
  it('sends a civil date with a null reason and no tenant field', () => {
    const body = registrationBody({
      ...EMPTY_REGISTRATION,
      displayName: ' Layla Haddad ',
      mrn: ' MRN-1001 ',
      issuer: ' SYNTHETIC ',
      dob: ' 1990-05-12 ',
      language: ' en ',
    });
    expect(body).toEqual({
      displayName: 'Layla Haddad',
      mrn: 'MRN-1001',
      issuer: 'SYNTHETIC',
      dob: '1990-05-12',
      dobUnknownReason: null,
      language: 'en',
      contacts: '{}',
      identifiers: '[]',
      consents: '[]',
    });
    expect(Object.keys(body)).not.toContain('organizationId');
  });

  it('sends a null date with the coded reason', () => {
    const body = registrationBody({
      ...EMPTY_REGISTRATION,
      dobUnknownReason: 'not_known',
    });
    expect(body.dob).toBeNull();
    expect(body.dobUnknownReason).toBe('not_known');
  });
});

describe('server validation mapping', () => {
  it('maps each server code to the field that caused it', () => {
    expect(serverFieldForCode('INVALID_NAME')).toBe('displayName');
    expect(serverFieldForCode('INVALID_MRN')).toBe('mrn');
    expect(serverFieldForCode('INVALID_ISSUER')).toBe('issuer');
    expect(serverFieldForCode('INVALID_DOB')).toBe('dob');
  });

  it('leaves conflict and unknown codes unmapped', () => {
    expect(serverFieldForCode('CONFLICT')).toBeUndefined();
    expect(serverFieldForCode('UNKNOWN_CODE')).toBeUndefined();
    expect(serverFieldForCode(undefined)).toBeUndefined();
  });

  it('gives every field its localized validation message key', () => {
    expect(validationKeyForField('displayName')).toBe('errorDisplayName');
    expect(validationKeyForField('mrn')).toBe('errorMrn');
    expect(validationKeyForField('issuer')).toBe('errorIssuer');
    expect(validationKeyForField('dob')).toBe('errorDob');
    expect(validationKeyForField('dobUnknownReason')).toBe('errorDobReason');
    expect(validationKeyForField('language')).toBe('errorLanguage');
  });
});

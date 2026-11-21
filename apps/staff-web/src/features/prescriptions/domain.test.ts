import { describe, expect, it } from 'vitest';
import type { AllergyFinding, DrugCandidate, DraftFields, VerifiedAllergy } from './domain';
import {
  canSubmit,
  findAllergyFindings,
  matchesDrugQuery,
  refillsSupported,
  selectDrugs,
} from './domain';

/** Build a clearly synthetic catalog entry for one assertion. */
function drug(overrides: Partial<DrugCandidate> = {}): DrugCandidate {
  return {
    code: 'DRUG-AMOX',
    version: 2,
    genericName: 'Amoxicillin',
    tradeName: 'Amoxil',
    formulation: 'tablet',
    strength: '500 mg',
    ingredients: ['penicillin'],
    marketApproved: true,
    matchTags: ['generic', 'first-line'],
    ...overrides,
  };
}

/** Build a complete synthetic draft so individual fields can be invalidated. */
function draft(overrides: Partial<DraftFields> = {}): DraftFields {
  return {
    patientId: 'synthetic-patient-001',
    prescriber: 'synthetic.prescriber',
    drugCode: 'DRUG-AMOX',
    drugVersion: 2,
    strength: '500 mg',
    formulation: 'tablet',
    dose: '1 tablet',
    route: 'oral',
    frequency: 'three times daily',
    quantity: '21',
    refills: '2',
    instructions: 'Take with water after meals',
    destination: 'synthetic-pharmacy',
    channel: 'e-prescribing',
    attested: true,
    overrideReason: '',
    ...overrides,
  };
}

/** Build a synthetic allergy record. */
function allergy(ingredient: string): VerifiedAllergy {
  return { ingredient, reaction: 'rash' };
}

describe('findAllergyFindings', () => {
  it('flags a verified ingredient match and an unverified ingredient as unknown', () => {
    const findings = findAllergyFindings(
      drug({ ingredients: ['Penicillin', 'clavulanate'] }),
      [allergy('penicillin')],
    );
    expect(findings).toEqual<AllergyFinding[]>([
      { kind: 'match', ingredient: 'Penicillin' },
      { kind: 'unknown', ingredient: 'clavulanate' },
    ]);
  });

  it('returns nothing for a drug with no declared ingredients', () => {
    expect(findAllergyFindings(drug({ ingredients: [] }), [allergy('penicillin')])).toEqual([]);
  });

  it('marks every ingredient unknown when no allergies are verified', () => {
    expect(findAllergyFindings(drug(), [])).toEqual([{ kind: 'unknown', ingredient: 'penicillin' }]);
  });
});

describe('matchesDrugQuery', () => {
  it.each([
    ['', '', true],
    ['amox', '', true],
    ['AMOX', '', true],
    ['amoxil', '', true],
    ['cef', '', false],
    ['', 'tablet', true],
    ['', 'syrup', false],
    ['amox', 'syrup', false],
  ] as const)('matches query %s and formulation %s as %s', (query, formulation, expected) => {
    expect(matchesDrugQuery(drug(), query, formulation)).toBe(expected);
  });
});

describe('selectDrugs', () => {
  it('excludes entries that are not market approved', () => {
    const approved = drug({ code: 'APPROVED' });
    const research = drug({ code: 'RESEARCH', marketApproved: false, genericName: 'Amoxicillin XR' });
    expect(selectDrugs([research, approved], 'amox', '')).toEqual([approved]);
  });
});

describe('refillsSupported', () => {
  it('supports refills for e-prescribing but not for a printable prescription', () => {
    expect(refillsSupported('e-prescribing')).toBe(true);
    expect(refillsSupported('printable')).toBe(false);
  });
});

describe('canSubmit', () => {
  it('allows a complete, attested draft without allergy findings', () => {
    expect(canSubmit(draft(), [])).toBe(true);
  });

  it('rejects a draft with an empty required field', () => {
    expect(canSubmit(draft({ patientId: '   ' }), [])).toBe(false);
  });

  it('rejects a draft without prescriber attestation', () => {
    expect(canSubmit(draft({ attested: false }), [])).toBe(false);
  });

  it('requires an override reason when allergy findings exist', () => {
    const findings: AllergyFinding[] = [{ kind: 'match', ingredient: 'penicillin' }];
    expect(canSubmit(draft(), findings)).toBe(false);
    expect(canSubmit(draft({ overrideReason: 'synthetic review' }), findings)).toBe(true);
  });
});

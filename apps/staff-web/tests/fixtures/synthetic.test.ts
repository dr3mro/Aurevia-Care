import { describe, expect, it } from 'vitest';
import {
  mixedScriptLabel,
  syntheticFacility,
  syntheticOrganizations,
  syntheticPerson,
  syntheticStaff,
} from '../../src/fixtures/synthetic';

describe('synthetic fixtures only', () => {
  it('uses clearly synthetic tenants, staff and facilities', () => {
    expect(syntheticOrganizations.length).toBeGreaterThan(1);
    for (const org of syntheticOrganizations) {
      expect(org.name).toContain('Synthetic');
    }
    expect(syntheticFacility.code).toBe('MAIN');
    expect(syntheticStaff.subject).toContain('synthetic');
    expect(syntheticStaff.roles).toContain('reception');
  });

  it('provides Latin and Arabic names plus a mixed-script clinical label', () => {
    expect(syntheticPerson.latinName).toMatch(/^[A-Za-z ]+$/);
    expect(syntheticPerson.localName).toMatch(/[\u0600-\u06FF]/);
    expect(mixedScriptLabel).toMatch(/[A-Za-z]/);
    expect(mixedScriptLabel).toMatch(/[\u0600-\u06FF]/);
  });
});

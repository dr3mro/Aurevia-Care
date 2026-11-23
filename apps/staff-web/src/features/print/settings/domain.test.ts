import { describe, expect, it } from 'vitest';
import {
  approveProfile,
  canManagePrintSettings,
  effectiveProfile,
  isApprovedLogo,
  nextProfileVersion,
  proposeProfile,
  validateProfileDraft,
  versionsForFacility,
} from './domain';
import type { PrintProfileDraft, PrintProfileVersion } from './domain';
import {
  anchorCivilDate,
  approvedLogoAssets,
  otherOrganizationId,
  settingsFacilityCode,
  settingsOrganizationId,
  syntheticVersions,
  unapprovedLogoReference,
} from './fixtures';

/** A fully valid synthetic draft using an approved logo asset. */
const validDraft: PrintProfileDraft = {
  facilityName: 'Synthetic Main Clinic',
  address: '1 Synthetic Way, Test City',
  logoReference: required(approvedLogoAssets[0]).reference,
  footerText: 'Synthetic footer — not a real notice',
  defaultLanguage: 'en',
  paperSize: 'A4',
  effectiveFrom: '2027-01-01',
};

const findVersion = (version: number): PrintProfileVersion => {
  const found = syntheticVersions.find(
    (entry) => entry.organizationId === settingsOrganizationId && entry.version === version,
  );
  if (!found) {
    throw new Error(`missing synthetic print profile version ${version}`);
  }
  return found;
};

describe('print settings permissions', () => {
  it('allows only an active tenant administrator to manage print profiles', () => {
    expect(canManagePrintSettings('tenant_admin')).toBe(true);
    expect(canManagePrintSettings('reception')).toBe(false);
    expect(canManagePrintSettings('privacy_auditor')).toBe(false);
  });
});

describe('approved logo assets', () => {
  it('accepts a trimmed reference from the approved asset list', () => {
    expect(
      isApprovedLogo(`  ${required(approvedLogoAssets[1]).reference}  `, approvedLogoAssets),
    ).toBe(true);
  });

  it('refuses an unapproved reference and an empty reference', () => {
    expect(isApprovedLogo(unapprovedLogoReference, approvedLogoAssets)).toBe(false);
    expect(isApprovedLogo('', approvedLogoAssets)).toBe(false);
  });
});

describe('print profile version history', () => {
  it('returns one tenant and facility newest version first', () => {
    const rows = versionsForFacility(
      syntheticVersions,
      settingsOrganizationId,
      settingsFacilityCode,
    );
    expect(rows.map((row) => row.version)).toEqual([2, 1]);
    expect(rows.every((row) => row.organizationId === settingsOrganizationId)).toBe(true);
    expect(
      versionsForFacility(syntheticVersions, otherOrganizationId, settingsFacilityCode),
    ).toHaveLength(1);
    expect(versionsForFacility(syntheticVersions, settingsOrganizationId, 'MISSING')).toEqual([]);
  });

  it('breaks equal-version ties deterministically by effective date', () => {
    const tied: PrintProfileVersion[] = [
      { ...findVersion(1), effectiveFrom: '2026-02-01' },
      { ...findVersion(1), effectiveFrom: '2026-01-01' },
    ];
    expect(
      versionsForFacility(tied, settingsOrganizationId, settingsFacilityCode).map(
        (row) => row.effectiveFrom,
      ),
    ).toEqual(['2026-02-01', '2026-01-01']);
  });

  it('selects the newest approved version already effective on the anchor date', () => {
    expect(
      effectiveProfile(
        syntheticVersions,
        settingsOrganizationId,
        settingsFacilityCode,
        anchorCivilDate,
      )?.version,
    ).toBe(1);
    const withApprovedPending = syntheticVersions.map((entry) =>
      entry.version === 2 ? approveProfile(entry, 'Synthetic administrator') : entry,
    );
    expect(
      effectiveProfile(
        withApprovedPending,
        settingsOrganizationId,
        settingsFacilityCode,
        '2030-01-01',
      )?.version,
    ).toBe(2);
    const other = effectiveProfile(
      syntheticVersions,
      otherOrganizationId,
      settingsFacilityCode,
      anchorCivilDate,
    );
    expect(other?.facilityName).toBe('Other tenant clinic');
    expect(other?.organizationId).toBe(otherOrganizationId);
    expect(
      effectiveProfile(
        syntheticVersions,
        '00000000-0000-4000-8000-000000000099',
        settingsFacilityCode,
        anchorCivilDate,
      ),
    ).toBeUndefined();
    expect(
      effectiveProfile(syntheticVersions, settingsOrganizationId, 'MISSING', anchorCivilDate),
    ).toBeUndefined();
  });

  it('allocates the next tenant and facility local version', () => {
    expect(
      nextProfileVersion(syntheticVersions, settingsOrganizationId, settingsFacilityCode),
    ).toBe(3);
    expect(nextProfileVersion(syntheticVersions, settingsOrganizationId, 'MISSING')).toBe(1);
  });
});

describe('print profile draft validation', () => {
  it('accepts a complete draft with an approved logo', () => {
    expect(validateProfileDraft(validDraft, approvedLogoAssets)).toEqual({});
  });

  it('refuses a logo that is not an approved asset', () => {
    expect(
      validateProfileDraft(
        { ...validDraft, logoReference: unapprovedLogoReference },
        approvedLogoAssets,
      ),
    ).toEqual({
      logoReference: 'logoNotApproved',
    });
    expect(validateProfileDraft({ ...validDraft, logoReference: '' }, approvedLogoAssets)).toEqual({
      logoReference: 'logoNotApproved',
    });
  });

  it('names every invalid field and never crashes on a bad civil date', () => {
    expect(
      validateProfileDraft(
        {
          ...validDraft,
          facilityName: '  ',
          address: '',
          footerText: '',
          effectiveFrom: 'not a date',
        },
        approvedLogoAssets,
      ),
    ).toEqual({
      facilityName: 'facilityNameRequired',
      address: 'addressRequired',
      footerText: 'footerTextRequired',
      effectiveFrom: 'invalidEffectiveDate',
    });
  });
});

describe('append-only print profile commands', () => {
  it('proposes a pending version bound to the requesting tenant and facility', () => {
    const created = proposeProfile(
      syntheticVersions,
      settingsOrganizationId,
      settingsFacilityCode,
      validDraft,
    );
    expect(created.organizationId).toBe(settingsOrganizationId);
    expect(created.facilityCode).toBe(settingsFacilityCode);
    expect(created.version).toBe(3);
    expect(created.approved).toBe(false);
    expect(created.approver).toBeUndefined();
    expect(created.facilityName).toBe(validDraft.facilityName);
  });

  it('approves a version without changing its effective date or content', () => {
    const pending = findVersion(2);
    const approved = approveProfile(pending, 'Synthetic administrator');
    expect(approved.approved).toBe(true);
    expect(approved.approver).toBe('Synthetic administrator');
    expect(approved.effectiveFrom).toBe(pending.effectiveFrom);
    expect(approved.version).toBe(pending.version);
    expect(pending.approved).toBe(false);
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}

import { describe, expect, it } from 'vitest';
import {
  EMPTY_POLICY_DRAFT,
  POLICY_VALUE_PREFIX,
  effectiveVersion,
  hasPolicyErrors,
  decodePolicyValue,
  encodePolicyValue,
  releaseAccess,
  releaseModeForKey,
  validatePolicyChange,
  versionsFor,
} from './domain';
import type { PolicyChangeDraft, PolicyVersion } from './domain';
import {
  alphaOrganizationId,
  betaOrganizationId,
  holdReleaseKey,
  policyAnchorCivilDate,
  syntheticReleaseVersions,
  syntheticTemplateVersions,
  templateKey,
} from './fixtures';

/** A base draft that passes every rule; individual tests override one field. */
const validDraft: PolicyChangeDraft = {
  ...EMPTY_POLICY_DRAFT,
  value: 'Synthetic proposed value',
  approver: 'Synthetic named approver',
  effectiveFrom: '2026-07-01',
  code: 'LMP',
  expiry: '2026-08-01',
};

describe('versionsFor', () => {
  it('returns one tenant and key newest first', () => {
    const versions = versionsFor(
      syntheticTemplateVersions,
      alphaOrganizationId,
      templateKey('soap'),
    );
    expect(versions.map((row) => row.version)).toEqual([3, 2, 1]);
  });

  it('never returns a colliding key from another tenant', () => {
    expect(versionsFor(syntheticTemplateVersions, betaOrganizationId, templateKey('soap'))).toHaveLength(1);
    expect(versionsFor(syntheticTemplateVersions, alphaOrganizationId, templateKey('obgyn'))).toHaveLength(2);
    expect(versionsFor(syntheticTemplateVersions, alphaOrganizationId, 'template.unknown')).toEqual([]);
  });
});

describe('effectiveVersion', () => {
  it('returns the highest approved version effective on the anchor date', () => {
    const found = effectiveVersion(
      syntheticTemplateVersions,
      alphaOrganizationId,
      templateKey('soap'),
      policyAnchorCivilDate,
    );
    expect(found?.version).toBe(2);
  });

  it('skips pending, undated and future-dated versions', () => {
    expect(
      effectiveVersion(
        syntheticTemplateVersions,
        alphaOrganizationId,
        templateKey('soap'),
        '2026-02-28',
      )?.version,
    ).toBe(1);
    expect(
      effectiveVersion(
        syntheticTemplateVersions,
        alphaOrganizationId,
        templateKey('obgyn'),
        '2026-02-28',
      )?.version,
    ).toBe(1);
    expect(
      effectiveVersion(
        syntheticTemplateVersions,
        alphaOrganizationId,
        templateKey('obgyn'),
        '2025-01-01',
      ),
    ).toBeUndefined();
  });

  it('returns undefined when no tenant row matches', () => {
    expect(
      effectiveVersion(
        syntheticTemplateVersions,
        betaOrganizationId,
        templateKey('obgyn'),
        policyAnchorCivilDate,
      ),
    ).toBeUndefined();
  });
});

describe('releaseModeForKey', () => {
  it('derives timed and hold modes from the key suffix', () => {
    expect(releaseModeForKey('release.timed')).toBe('timed');
    expect(releaseModeForKey('release.hold')).toBe('hold');
  });

  it('defaults other keys to immediate release', () => {
    expect(releaseModeForKey('release.default')).toBe('immediate');
    expect(releaseModeForKey('template.soap')).toBe('immediate');
  });
});

describe('releaseAccess', () => {
  const base = {
    approval: 'approved' as const,
    effectiveFrom: '2026-01-01',
    mode: 'immediate' as const,
  };

  it('holds a pending policy regardless of its mode', () => {
    expect(releaseAccess({ ...base, approval: 'pending', mode: 'hold' }, policyAnchorCivilDate)).toBe('held');
  });

  it('releases an approved immediate policy', () => {
    expect(releaseAccess(base, policyAnchorCivilDate)).toBe('released');
  });

  it('schedules a timed policy until its effective date and releases it after', () => {
    expect(releaseAccess({ ...base, mode: 'timed' }, '2026-03-09')).toBe('released');
    expect(releaseAccess({ ...base, mode: 'timed', effectiveFrom: '2026-04-01' }, policyAnchorCivilDate)).toBe('scheduled');
    expect(releaseAccess({ ...base, mode: 'timed', effectiveFrom: '' }, policyAnchorCivilDate)).toBe('scheduled');
  });

  it('holds a held policy until review is due', () => {
    expect(releaseAccess({ ...base, mode: 'hold' }, policyAnchorCivilDate)).toBe('held');
    expect(releaseAccess({ ...base, mode: 'hold', expiry: '' }, policyAnchorCivilDate)).toBe('held');
    expect(releaseAccess({ ...base, mode: 'hold', expiry: '2026-05-01' }, policyAnchorCivilDate)).toBe('held');
    expect(releaseAccess({ ...base, mode: 'hold', expiry: '2026-03-01' }, policyAnchorCivilDate)).toBe('review');
  });

  it('resolves the synthetic held release policy on the anchor date', () => {
    const held = syntheticReleaseVersions.find(
      (row) =>
        row.organizationId === alphaOrganizationId &&
        row.key === holdReleaseKey &&
        row.version === 1,
    ) as PolicyVersion;
    expect(releaseAccess(held, policyAnchorCivilDate)).toBe('held');
  });
});

describe('validatePolicyChange', () => {
  it('accepts a fully populated clinical change', () => {
    expect(validatePolicyChange(validDraft, true)).toEqual({});
  });

  it('refuses a change without a named approver', () => {
    expect(validatePolicyChange({ ...validDraft, approver: '   ' }, true)).toEqual({
      approver: 'approverRequired',
    });
  });

  it('does not require an approver when the caller says none is needed', () => {
    expect(validatePolicyChange({ ...validDraft, approver: '' }, false)).toEqual({});
  });

  it('refuses a missing or malformed value', () => {
    expect(validatePolicyChange({ ...validDraft, value: '' }, true)).toEqual({
      value: 'valueRequired',
    });
  });

  it('refuses a missing or malformed effective date', () => {
    expect(validatePolicyChange({ ...validDraft, effectiveFrom: '' }, true)).toEqual({
      effectiveFrom: 'effectiveDateRequired',
    });
    expect(validatePolicyChange({ ...validDraft, effectiveFrom: '07/01/2026' }, true)).toEqual({
      effectiveFrom: 'effectiveDateInvalid',
    });
  });

  it('refuses a hold without a reason', () => {
    expect(validatePolicyChange({ ...validDraft, mode: 'hold', reason: '  ' }, true)).toEqual({
      reason: 'reasonRequired',
    });
  });

  it('allows a hold with a reason and a real review date', () => {
    expect(validatePolicyChange({ ...validDraft, mode: 'hold', reason: 'Synthetic review' }, true)).toEqual({});
  });

  it('refuses timed and hold changes without a valid expiry', () => {
    expect(validatePolicyChange({ ...validDraft, mode: 'timed', expiry: '' }, true)).toEqual({
      expiry: 'expiryInvalid',
    });
    expect(validatePolicyChange({ ...validDraft, mode: 'hold', reason: 'Synthetic review', expiry: '31-08-2026' }, true)).toEqual({
      expiry: 'expiryInvalid',
    });
  });

  it('does not need an expiry for an immediate change', () => {
    expect(validatePolicyChange({ ...validDraft, mode: 'immediate', expiry: '' }, true)).toEqual({});
  });

  it('requires a code only for a dictionary field', () => {
    expect(validatePolicyChange({ ...validDraft, code: '' }, true)).toEqual({});
    expect(validatePolicyChange({ ...validDraft, code: '  ' }, true, true)).toEqual({
      code: 'codeRequired',
    });
    expect(validatePolicyChange({ ...validDraft, code: 'LMP' }, true, true)).toEqual({});
  });

  it('refuses an encoded value longer than the contract limit', () => {
    expect(
      validatePolicyChange({ ...validDraft, value: 'Synthetic '.repeat(450) }, true),
    ).toEqual({ value: 'valueTooLong' });
  });

  it('reports several refused fields together', () => {
    const errors = validatePolicyChange(
      { ...EMPTY_POLICY_DRAFT, mode: 'hold', effectiveFrom: '2026-07-01', expiry: '2026-08-01' },
      true,
      true,
    );
    expect(errors).toEqual({
      code: 'codeRequired',
      value: 'valueRequired',
      approver: 'approverRequired',
      reason: 'reasonRequired',
    });
  });
});

describe('hasPolicyErrors', () => {
  it('distinguishes a valid draft from a refused one', () => {
    expect(hasPolicyErrors({})).toBe(false);
    expect(hasPolicyErrors({ approver: 'approverRequired' })).toBe(true);
  });
});

describe('policy value envelope', () => {
  const draft: PolicyChangeDraft = {
    ...EMPTY_POLICY_DRAFT,
    value: '  Synthetic envelope text  ',
    approver: '  Synthetic named approver  ',
    effectiveFrom: '2026-07-01',
    mode: 'hold',
    reason: '  Synthetic specialist review  ',
    expiry: '2026-08-01',
  };

  it('round-trips the mode, reason, expiry and named approver', () => {
    const encoded = encodePolicyValue(draft);
    expect(encoded.startsWith(POLICY_VALUE_PREFIX)).toBe(true);
    expect(decodePolicyValue(encoded)).toEqual({
      text: 'Synthetic envelope text',
      approver: 'Synthetic named approver',
      mode: 'hold',
      reason: 'Synthetic specialist review',
      expiry: '2026-08-01',
    });
  });

  it('keeps a timed mode through the envelope', () => {
    expect(decodePolicyValue(encodePolicyValue({ ...draft, mode: 'timed' })).mode).toBe('timed');
  });

  it('treats plain historical text as an immediate release', () => {
    expect(decodePolicyValue('Synthetic plain value')).toEqual({
      text: 'Synthetic plain value',
      approver: '',
      mode: 'immediate',
      reason: '',
      expiry: '',
    });
  });

  it('falls back safely for a malformed envelope', () => {
    const malformed = `${POLICY_VALUE_PREFIX}{not json`;
    expect(decodePolicyValue(malformed).text).toBe(malformed);
    expect(decodePolicyValue(malformed).mode).toBe('immediate');
  });

  it('uses a key-derived fallback for plain and malformed values under a hold key', () => {
    expect(decodePolicyValue('Synthetic plain', 'hold').mode).toBe('hold');
    const malformed = `${POLICY_VALUE_PREFIX}{not json`;
    expect(decodePolicyValue(malformed, 'hold').mode).toBe('hold');
    expect(decodePolicyValue(`${POLICY_VALUE_PREFIX}{}`, 'timed').mode).toBe('timed');
  });

  it('defaults every malformed field inside a parseable envelope', () => {
    const broken = `${POLICY_VALUE_PREFIX}${JSON.stringify({
      text: 7,
      approver: null,
      mode: 'unknown',
      reason: false,
      expiry: 0,
    })}`;
    expect(decodePolicyValue(broken)).toEqual({
      text: '',
      approver: '',
      mode: 'immediate',
      reason: '',
      expiry: '',
    });
  });
});

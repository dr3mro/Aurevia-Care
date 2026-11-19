import {
  SUBMISSION_NOTICE,
  SUPPORT_CATEGORIES,
  buildSubmissionNotice,
  containsPotentialPhi,
  formatLockoutCountdown,
  getRowDirection,
  getTextAlign,
  isLocked,
  mapInvitationError,
  normalizeInvitationCode,
  validateInvitationCode,
  validateSupportRequest,
  type SupportDraft,
} from './model';

describe('PAT-03 invitation helpers', () => {
  it('normalizes pasted codes without echoing them', () => {
    expect(normalizeInvitationCode(' ab-cd 12-34 ')).toBe('ABCD1234');
    expect(normalizeInvitationCode('abcd1234')).toBe('ABCD1234');
  });

  it('validates shape and reports a stable reason', () => {
    expect(validateInvitationCode('')).toEqual({ valid: false, reason: 'empty' });
    expect(validateInvitationCode('short')).toEqual({ valid: false, reason: 'malformed' });
    expect(validateInvitationCode('ABCD12!4')).toEqual({ valid: false, reason: 'malformed' });
    expect(validateInvitationCode('ABCD1234')).toEqual({ valid: true });
  });

  it.each([
    ['EXPIRED', 'enrollment.expired', false],
    ['REUSED', 'enrollment.reused', false],
    ['REVOKED', 'enrollment.revoked', false],
    ['NETWORK', 'common.network', true],
    ['SOMETHING_ELSE', 'enrollment.invalid', true],
  ])('maps %s to a generic message without patient data', (code, messageKey, retryable) => {
    const failure = mapInvitationError(code as string);
    expect(failure).toEqual({ messageKey, retryable });
    expect(JSON.stringify(failure)).not.toContain('ABCD1234');
  });
});

describe('PAT-03 lockout helpers', () => {
  it.each([
    [-5, '00:00'],
    [0, '00:00'],
    [1.2, '00:02'],
    [59, '00:59'],
    [60, '01:00'],
    [125, '02:05'],
  ])('formats %s seconds as %s', (seconds, expected) => {
    expect(formatLockoutCountdown(seconds)).toBe(expected);
  });

  it('reports whether a countdown still blocks access', () => {
    expect(isLocked(1)).toBe(true);
    expect(isLocked(0)).toBe(false);
    expect(isLocked(-1)).toBe(false);
  });
});

describe('PAT-03 support helpers', () => {
  it('exposes a fixed category list', () => {
    expect(SUPPORT_CATEGORIES).toEqual(['identity_dispute', 'accessibility', 'record_access']);
  });

  it('detects phone-like and email-like text without rejecting plain text', () => {
    expect(containsPotentialPhi('call 123456 later')).toBe(true);
    expect(containsPotentialPhi('email me at patient@example.org')).toBe(true);
    expect(containsPotentialPhi('please use large print')).toBe(false);
    expect(containsPotentialPhi('')).toBe(false);
    expect(containsPotentialPhi('12345')).toBe(false);
  });

  it.each([
    [{ category: null, message: 'a valid request' }, 'category'],
    [{ category: 'accessibility', message: '   ' }, 'empty'],
    [{ category: 'accessibility', message: 'x'.repeat(501) }, 'too_long'],
    [{ category: 'record_access', message: 'my number is 1234567' }, 'phi'],
  ])('rejects an invalid support draft with a stable reason', (draft, reason) => {
    expect(validateSupportRequest(draft as SupportDraft)).toEqual({ ok: false, reason });
  });

  it('accepts a valid support draft for each category', () => {
    for (const category of SUPPORT_CATEGORIES) {
      expect(validateSupportRequest({ category, message: 'Please help me access my record' })).toEqual({ ok: true });
    }
  });

  it('returns a generic notice with no draft content', () => {
    expect(buildSubmissionNotice()).toBe(SUBMISSION_NOTICE);
    expect(buildSubmissionNotice()).not.toContain('patient@example.org');
  });
});

describe('PAT-03 locale helpers', () => {
  it('mirrors text alignment and row direction for RTL', () => {
    expect(getTextAlign(false)).toBe('left');
    expect(getTextAlign(true)).toBe('right');
    expect(getRowDirection(false)).toBe('row');
    expect(getRowDirection(true)).toBe('row-reverse');
  });
});

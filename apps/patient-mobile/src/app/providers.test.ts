/**
 * Placeholder providers are synthetic only. This test proves they return no
 * clinical payload and that the shared no-op callback is safe to reuse for
 * every presentational screen command.
 */
import {
  placeholderAction,
  placeholderAttachments,
  placeholderDevices,
  placeholderEnrollmentStrings,
  placeholderFacts,
  placeholderInstructions,
  placeholderLockoutStrings,
  placeholderNotice,
  placeholderNow,
  placeholderPatientId,
  placeholderPublicationAuthorized,
  placeholderRecoveryStrings,
  placeholderSupportStrings,
  placeholderUnit,
  placeholderUnitId,
} from './providers';

describe('placeholder providers', () => {
  it('returns empty synthetic projections for the remaining list screens', () => {
    expect(placeholderDevices()).toEqual([]);
    expect(placeholderAttachments()).toEqual([]);
    expect(placeholderInstructions()).toEqual([]);
    expect(placeholderFacts()).toEqual({});
  });

  it('returns generic, non-clinical values for the remaining props', () => {
    expect(placeholderNotice('en')).toEqual({
      title: expect.any(String),
      body: expect.any(String),
    });
    expect(placeholderNotice('en').title).not.toMatch(/diagnos|result|report/i);
    expect(placeholderPatientId()).toBeTruthy();
    expect(placeholderUnit().organizationId).toBeTruthy();
    expect(placeholderUnit().facilityCode).toBeTruthy();
    expect(placeholderUnit().unitName).toBeTruthy();
    expect(placeholderUnitId()).toBeTruthy();
    expect(placeholderPublicationAuthorized()).toBe(false);
    expect(placeholderNow()).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  it('returns localized strings for every account and messages screen', () => {
    for (const locale of ['en', 'ar'] as const) {
      const enrollment = placeholderEnrollmentStrings(locale);
      expect(enrollment.codeLabel).toBeTruthy();
      expect(enrollment.submit).toBeTruthy();
      const recovery = placeholderRecoveryStrings(locale);
      expect(recovery.verificationLabel).toBeTruthy();
      const lockout = placeholderLockoutStrings(locale);
      expect(lockout.title).toBeTruthy();
      expect(lockout.timerLabel).toBeTruthy();
      const support = placeholderSupportStrings(locale);
      expect(support.title).toBeTruthy();
      expect(support.messageLabel).toBeTruthy();
    }
  });

  it('localizes the generic appointment notice for the active locale', () => {
    const english = placeholderNotice('en');
    const arabic = placeholderNotice('ar');
    expect(english.title).toBeTruthy();
    expect(english.body).toBeTruthy();
    expect(arabic.title).toBeTruthy();
    expect(arabic.body).toBeTruthy();
    expect(arabic.title).not.toBe(english.title);
    expect(arabic.body).not.toBe(english.body);
  });

  it('exposes a single no-op callback for all placeholder commands', () => {
    expect(placeholderAction()).toBeUndefined();
  });
});

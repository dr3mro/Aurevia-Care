import {
  createSyntheticFlowClient,
  directionFor,
  flowLabel,
  genericNoticeText,
  isIanaTimeZone,
  isRtl,
  parseLocale,
  parseNoticeKind,
  parsePlatform,
  rowFlexDirection,
  secureStorageLabel,
  settingsLabel,
} from './presentation';
import type { PresentationFixture } from './types';

const ORG = '11111111-1111-1111-1111-111111111111';
const OTHER_ORG = '22222222-2222-2222-2222-222222222222';
const PATIENT = 'patient-user';
const OTHER_PATIENT = 'other-user';

function fixture(overrides: Partial<PresentationFixture> = {}): PresentationFixture {
  return {
    organizationId: ORG,
    activeMembers: [PATIENT],
    policyVersions: { [`${ORG}|en|android`]: 7, [`${ORG}|ar|ios`]: 9 },
    approvedNotices: ['appointment_status', 'new_message', 'new_instruction', 'reauth_required', 'account_locked', 'support_reply'],
    now: '2026-11-19T12:00:00Z',
    ...overrides,
  };
}

describe('S-G3-11 locale, direction and platform mapping (UX-01)', () => {
  it('parses only supported locale and platform wire keys', () => {
    expect(parseLocale('en')).toBe('en');
    expect(parseLocale('ar')).toBe('ar');
    expect(parseLocale('fr')).toBeNull();
    expect(parsePlatform('ios')).toBe('ios');
    expect(parsePlatform('android')).toBe('android');
    expect(parsePlatform('web')).toBeNull();
  });

  it('maps Arabic to RTL and English to LTR', () => {
    expect(isRtl('ar')).toBe(true);
    expect(isRtl('en')).toBe(false);
    expect(directionFor('ar')).toBe('rtl');
    expect(directionFor('en')).toBe('ltr');
    expect(rowFlexDirection('ar')).toBe('row-reverse');
    expect(rowFlexDirection('en')).toBe('row');
  });

  it('uses locale-aware, platform-correct settings and secure-storage wording', () => {
    expect(settingsLabel('ios', 'en')).toBe('Open iOS Settings');
    expect(settingsLabel('ios', 'ar')).toBe('افتح إعدادات iOS');
    expect(settingsLabel('android', 'en')).toBe('Open Android system settings');
    expect(settingsLabel('android', 'ar')).toBe('افتح إعدادات Android');
    expect(secureStorageLabel('ios', 'en')).toBe('Protected by iOS Keychain');
    expect(secureStorageLabel('ios', 'ar')).toBe('محمي بواسطة Keychain');
    expect(secureStorageLabel('android', 'en')).toBe('Protected by Android Keystore');
    expect(secureStorageLabel('android', 'ar')).toBe('محمي بواسطة Android Keystore');
  });

  it('validates IANA time zones without accepting invented zones', () => {
    expect(isIanaTimeZone('Europe/London')).toBe(true);
    expect(isIanaTimeZone('Asia/Riyadh')).toBe(true);
    expect(isIanaTimeZone('Mars/Phobos')).toBe(false);
    expect(isIanaTimeZone('')).toBe(false);
  });

  it('localizes flow labels in English and Arabic', () => {
    expect(flowLabel('appointments', 'en')).toBe('Appointments');
    expect(flowLabel('appointments', 'ar')).toBe('المواعيد');
    expect(flowLabel('messages', 'en')).toBe('Messages');
    expect(flowLabel('messages', 'ar')).toBe('الرسائل');
  });
});

describe('S-G3-11 generic notifications (PAT-01, MSG-05)', () => {
  it('exposes only approved, clinical-detail-free notice text', () => {
    expect(genericNoticeText('appointment_status', 'en')).toBe('Your appointment has been updated.');
    expect(genericNoticeText('new_message', 'en')).toBe('You have a new message.');
    expect(genericNoticeText('new_instruction', 'ar')).toBe('لديك تعليمات جديدة.');
    expect(genericNoticeText('reauth_required', 'en')).toBe('Please sign in again to continue.');
  });

  it('rejects unknown notice kinds', () => {
    expect(parseNoticeKind('diagnosis_result')).toBeNull();
    expect(parseNoticeKind('new_message')).toBe('new_message');
  });
});

describe('S-G3-11 synthetic flow presentation client', () => {
  it('returns a tenant-scoped, localized presentation for an active member', () => {
    const client = createSyntheticFlowClient(fixture());
    const result = client.presentation(PATIENT, { locale: 'en', platform: 'android', timeZone: 'Europe/London' });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.presentation.organizationId).toBe(ORG);
      expect(result.presentation.locale).toBe('en');
      expect(result.presentation.platform).toBe('android');
      expect(result.presentation.direction).toBe('ltr');
      expect(result.presentation.rtl).toBe(false);
      expect(result.presentation.policyVersion).toBe(7);
      expect(result.presentation.issuedAt).toBe('2026-11-19T12:00:00Z');
      expect(result.presentation.timeZoneContext).toBe('Europe/London');
      expect(result.presentation.accessibilityProfile).toBe('WCAG-2.2-AA');
      expect(result.presentation.noticeKinds.length).toBe(6);
    }
  });

  it('mirrors direction and platform wording for Arabic on iOS', () => {
    const client = createSyntheticFlowClient(fixture());
    const result = client.presentation(PATIENT, { locale: 'ar', platform: 'ios', timeZone: 'Asia/Riyadh' });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.presentation.rtl).toBe(true);
      expect(result.presentation.direction).toBe('rtl');
      expect(result.presentation.settingsLabel).toBe('افتح إعدادات iOS');
      expect(result.presentation.secureStorageLabel).toBe('محمي بواسطة Keychain');
      expect(result.presentation.policyVersion).toBe(9);
    }
  });

  it('defaults a missing policy version to zero', () => {
    const client = createSyntheticFlowClient(fixture());
    const result = client.presentation(PATIENT, { locale: 'en', platform: 'ios', timeZone: 'UTC' });
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.presentation.policyVersion).toBe(0);
  });

  it('fails closed for missing membership and invalid input', () => {
    const client = createSyntheticFlowClient(fixture());
    const query = { locale: 'en', platform: 'ios', timeZone: 'UTC' };
    expect(client.presentation(null, query)).toEqual({ ok: false, code: 'UNAUTHENTICATED' });
    expect(client.presentation(OTHER_PATIENT, query)).toEqual({ ok: false, code: 'FORBIDDEN' });
    for (const invalidQuery of [
      { locale: 'fr', platform: 'ios', timeZone: 'UTC' },
      { locale: 'en', platform: 'web', timeZone: 'UTC' },
      { locale: 'en', platform: 'ios', timeZone: 'Mars/Phobos' },
    ]) {
      expect(client.presentation(PATIENT, invalidQuery)).toEqual({ ok: false, code: 'INVALID_INPUT' });
    }
  });

  it('does not leak the other tenant policy version', () => {
    const client = createSyntheticFlowClient(fixture({
      policyVersions: { [`${OTHER_ORG}|en|android`]: 99 },
    }));
    const result = client.presentation(PATIENT, { locale: 'en', platform: 'android', timeZone: 'UTC' });
    expect(result).toMatchObject({ ok: true, presentation: { policyVersion: 0 } });
  });

  it('resolves approved generic notification text with the same guards', () => {
    const client = createSyntheticFlowClient(fixture());
    expect(client.notificationText(PATIENT, 'new_message', 'en')).toEqual({
      ok: true, text: 'You have a new message.',
    });
    expect(client.notificationText(null, 'new_message', 'en')).toEqual({
      ok: false, code: 'UNAUTHENTICATED',
    });
    expect(client.notificationText(OTHER_PATIENT, 'new_message', 'en')).toEqual({
      ok: false, code: 'FORBIDDEN',
    });
    expect(client.notificationText(PATIENT, 'diagnosis_result', 'en')).toEqual({
      ok: false, code: 'INVALID_INPUT',
    });
  });
});

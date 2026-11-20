import type {
  AppLocale,
  AppPlatform,
  FlowPresentation,
  FlowPresentationClient,
  NoticeKind,
  NoticeResult,
  PatientFlow,
  PresentationFixture,
  PresentationQuery,
  PresentationResult,
} from './types';

/** The accessibility conformance target published to every patient client (UX-01). */
const ACCESSIBILITY_PROFILE = 'WCAG-2.2-AA';

/**
 * Approved generic push-notification text, keyed by notice kind and locale.
 *
 * The strings deliberately contain no patient, clinical, result or scheduling
 * detail. Full content is fetched only after authentication (PAT-01, MSG-05).
 */
const NOTICES: Readonly<Record<NoticeKind, Readonly<Record<AppLocale, string>>>> = {
  appointment_status: { en: 'Your appointment has been updated.', ar: 'تم تحديث موعدك.' },
  new_message: { en: 'You have a new message.', ar: 'لديك رسالة جديدة.' },
  new_instruction: { en: 'You have new instructions.', ar: 'لديك تعليمات جديدة.' },
  reauth_required: { en: 'Please sign in again to continue.', ar: 'يرجى تسجيل الدخول مرة أخرى للمتابعة.' },
  account_locked: { en: 'Your account is locked.', ar: 'تم قفل حسابك.' },
  support_reply: { en: 'You have a reply from support.', ar: 'لديك رد من الدعم.' },
};

/** Localized labels for the patient-facing flows (PAT-01–03, MSG-01–07). */
const FLOW_LABELS: Readonly<Record<PatientFlow, Readonly<Record<AppLocale, string>>>> = {
  appointments: { en: 'Appointments', ar: 'المواعيد' },
  reports: { en: 'Reports', ar: 'التقارير' },
  messages: { en: 'Messages', ar: 'الرسائل' },
  enrollment: { en: 'Support and identity', ar: 'الدعم والهوية' },
};

const NOTICE_KINDS = Object.keys(NOTICES);

/**
 * Parse a supported locale wire key.
 *
 * @param value client-supplied locale key
 * @returns the locale, or null when unsupported
 */
export function parseLocale(value: string): AppLocale | null {
  return value === 'en' || value === 'ar' ? value : null;
}

/**
 * Parse a supported platform wire key.
 *
 * @param value client-supplied platform key
 * @returns the platform, or null when unsupported
 */
export function parsePlatform(value: string): AppPlatform | null {
  return value === 'ios' || value === 'android' ? value : null;
}

/**
 * Parse an approved generic notice kind.
 *
 * @param value client-supplied notice kind
 * @returns the notice kind, or null when it is not an approved generic notice
 */
export function parseNoticeKind(value: string): NoticeKind | null {
  return NOTICE_KINDS.includes(value) ? (value as NoticeKind) : null;
}

/**
 * Whether a locale is laid out right-to-left (UX-01).
 *
 * @param locale resolved application locale
 * @returns true for Arabic
 */
export function isRtl(locale: AppLocale): boolean {
  return locale === 'ar';
}

/**
 * The CSS layout direction for a locale (UX-01).
 *
 * @param locale resolved application locale
 * @returns 'rtl' for Arabic, otherwise 'ltr'
 */
export function directionFor(locale: AppLocale): 'ltr' | 'rtl' {
  return locale === 'ar' ? 'rtl' : 'ltr';
}

/**
 * The React Native row direction that mirrors layout for RTL locales (UX-01).
 *
 * @param locale resolved application locale
 * @returns 'row-reverse' for Arabic, otherwise 'row'
 */
export function rowFlexDirection(locale: AppLocale): 'row' | 'row-reverse' {
  return locale === 'ar' ? 'row-reverse' : 'row';
}

/**
 * Locale-aware, platform-correct wording for the OS settings entry point (PAT-03, UX-01).
 *
 * @param platform resolved patient platform
 * @param locale resolved application locale
 * @returns the settings label for that platform in the active locale
 */
export function settingsLabel(platform: AppPlatform, locale: AppLocale): string {
  const localized: Record<AppPlatform, Record<AppLocale, string>> = {
    ios: { en: 'Open iOS Settings', ar: 'افتح إعدادات iOS' },
    android: { en: 'Open Android system settings', ar: 'افتح إعدادات Android' },
  };
  return localized[platform][locale];
}

/**
 * Locale-aware wording for OS-backed credential storage (PAT-02, PAT-03, UX-01).
 *
 * @param platform resolved patient platform
 * @param locale resolved application locale
 * @returns the secure-storage label for that platform in the active locale
 */
export function secureStorageLabel(platform: AppPlatform, locale: AppLocale): string {
  const localized: Record<AppPlatform, Record<AppLocale, string>> = {
    ios: { en: 'Protected by iOS Keychain', ar: 'محمي بواسطة Keychain' },
    android: { en: 'Protected by Android Keystore', ar: 'محمي بواسطة Android Keystore' },
  };
  return localized[platform][locale];
}

/**
 * Localized label for a patient flow.
 *
 * @param flow patient flow identifier
 * @param locale resolved application locale
 * @returns the accessible label in the active locale
 */
export function flowLabel(flow: PatientFlow, locale: AppLocale): string {
  return FLOW_LABELS[flow][locale];
}

/**
 * The approved generic text for a notice kind in a locale.
 *
 * @param kind approved notice kind
 * @param locale resolved application locale
 * @returns clinical-detail-free notification text
 */
export function genericNoticeText(kind: NoticeKind, locale: AppLocale): string {
  return NOTICES[kind][locale];
}

/**
 * Validate an IANA time-zone identifier without accepting invented zones (UX-01).
 *
 * @param value client or server supplied zone identifier
 * @returns true when the platform recognises the zone
 */
export function isIanaTimeZone(value: string): boolean {
  if (value === '') {
    return false;
  }
  try {
    new Intl.DateTimeFormat('en', { timeZone: value });
    return true;
  } catch {
    return false;
  }
}

/**
 * Build a synthetic, deterministic flow policy client for S-G3-11.
 *
 * It mirrors the server's tenant scoping, active-membership check, locale and
 * platform validation and approved-notice catalog so both mobile platforms can
 * be exercised without a backend or patient data. Production clients must use
 * the generated API client and rely on server-side authorization.
 *
 * @param fixture synthetic membership, tenant policy versions and approved notices
 * @returns a typed flow presentation client
 */
export function createSyntheticFlowClient(fixture: PresentationFixture): FlowPresentationClient {
  return {
    presentation(subject: string | null, query: PresentationQuery): PresentationResult {
      if (subject === null || !fixture.activeMembers.includes(subject)) {
        return { ok: false, code: subject === null ? 'UNAUTHENTICATED' : 'FORBIDDEN' };
      }
      const locale = parseLocale(query.locale);
      const platform = parsePlatform(query.platform);
      if (locale === null || platform === null || !isIanaTimeZone(query.timeZone)) {
        return { ok: false, code: 'INVALID_INPUT' };
      }
      const policyKey = `${fixture.organizationId}|${locale}|${platform}`;
      const presentation: FlowPresentation = {
        organizationId: fixture.organizationId,
        locale,
        platform,
        rtl: isRtl(locale),
        direction: directionFor(locale),
        settingsLabel: settingsLabel(platform, locale),
        secureStorageLabel: secureStorageLabel(platform, locale),
        policyVersion: fixture.policyVersions[policyKey] ?? 0,
        issuedAt: fixture.now,
        timeZoneContext: query.timeZone,
        accessibilityProfile: ACCESSIBILITY_PROFILE,
        noticeKinds: [...fixture.approvedNotices],
      };
      return { ok: true, presentation };
    },

    notificationText(subject: string | null, kindValue: string, localeValue: string): NoticeResult {
      if (subject === null) {
        return { ok: false, code: 'UNAUTHENTICATED' };
      }
      if (!fixture.activeMembers.includes(subject)) {
        return { ok: false, code: 'FORBIDDEN' };
      }
      const kind = parseNoticeKind(kindValue);
      const locale = parseLocale(localeValue);
      if (kind === null || locale === null) {
        return { ok: false, code: 'INVALID_INPUT' };
      }
      return { ok: true, text: genericNoticeText(kind, locale) };
    },
  };
}

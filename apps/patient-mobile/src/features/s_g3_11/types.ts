/**
 * S-G3-11 domain types for accessible, RTL-aware patient flows on iOS and
 * Android (PAT-01–03, MSG-01–07, UX-01).
 *
 * Every value here is a transport-safe projection of a server-authoritative
 * presentation policy. The app never decides its own locale rules, clinical
 * content or authorization: it renders what the server published and applies
 * the shared direction and accessibility mapping.
 */

/** Supported application locales (UX-01). */
export type AppLocale = 'en' | 'ar';

/** Supported patient platforms; both are exercised in the mobile test suite. */
export type AppPlatform = 'ios' | 'android';

/**
 * Approved generic notification kinds. None may carry clinical detail: the
 * full content is retrieved only after authentication (PAT-01, MSG-05).
 */
export type NoticeKind =
  | 'appointment_status'
  | 'new_message'
  | 'new_instruction'
  | 'reauth_required'
  | 'account_locked'
  | 'support_reply';

/** Patient-facing flows composed by this slice. */
export type PatientFlow = 'appointments' | 'reports' | 'messages' | 'enrollment';

/** Client request for the server-authored presentation policy. */
export interface PresentationQuery {
  readonly locale: string;
  readonly platform: string;
  readonly timeZone: string;
}

/** The server-authoritative presentation policy applied by the app. */
export interface FlowPresentation {
  readonly organizationId: string;
  readonly locale: AppLocale;
  readonly platform: AppPlatform;
  readonly rtl: boolean;
  readonly direction: 'ltr' | 'rtl';
  readonly settingsLabel: string;
  readonly secureStorageLabel: string;
  readonly policyVersion: number;
  /** Server UTC instant this policy was issued (UX-01: UTC with zone context). */
  readonly issuedAt: string;
  readonly timeZoneContext: string;
  readonly accessibilityProfile: string;
  readonly noticeKinds: readonly NoticeKind[];
}

/** Result of requesting a presentation policy. */
export type PresentationResult =
  | { readonly ok: true; readonly presentation: FlowPresentation }
  | { readonly ok: false; readonly code: 'UNAUTHENTICATED' | 'FORBIDDEN' | 'INVALID_INPUT' };

/** Result of resolving an approved generic notice. */
export type NoticeResult =
  | { readonly ok: true; readonly text: string }
  | { readonly ok: false; readonly code: 'UNAUTHENTICATED' | 'FORBIDDEN' | 'INVALID_INPUT' };

/** Synthetic, deterministic fixture for the in-memory flow policy client. */
export interface PresentationFixture {
  /** Owning tenant of this synthetic installation. */
  readonly organizationId: string;
  /** Subjects whose membership is currently active. */
  readonly activeMembers: readonly string[];
  /** Policy versions keyed by `${organizationId}|${locale}|${platform}`. */
  readonly policyVersions: Readonly<Record<string, number>>;
  /** Notice kinds the tenant has approved for generic push notifications. */
  readonly approvedNotices: readonly NoticeKind[];
  /** Server instant used only for deterministic fixtures. */
  readonly now: string;
}

/** Typed boundary for the patient flow presentation client. */
export interface FlowPresentationClient {
  presentation(subject: string | null, query: PresentationQuery): PresentationResult;
  notificationText(subject: string | null, kind: string, locale: string): NoticeResult;
}

/**
 * Placeholder screen providers for the navigation shell (PAT-01–03, UX-01).
 *
 * Every value here is synthetic. These providers exist only so the four tabs
 * can mount their presentational screens without authentication or a network
 * client. The authenticated session and API client replace them in a later,
 * separately approved task; no token, endpoint or patient payload lives here.
 */
import type { AppointmentView } from '../features/appointments/appointments';
import type { DeviceToken, GenericNotice } from '../features/appointments/types';
import type { CareUnitRef, PendingAttachment } from '../features/attachments/domain';
import type { Conversation } from '../features/conversations/domain';
import type {
  EnrollmentStrings,
  LockoutStrings,
  RecoveryStrings,
  SupportStrings,
} from '../features/enrollment/screens';
import type { DeliveryFacts, InstructionView } from '../features/acknowledgments/types';
import type { ReleasedReport } from '../features/reports/types';

import type { Locale } from './messages';

/** Empty synthetic appointment projection. */
export function placeholderAppointments(): readonly AppointmentView[] {
  return [];
}

/** Empty synthetic device list. */
export function placeholderDevices(): readonly DeviceToken[] {
  return [];
}

/** Empty synthetic released-report projection. */
export function placeholderReports(): readonly ReleasedReport[] {
  return [];
}

/** Empty synthetic conversation projection. */
export function placeholderConversations(): readonly Conversation[] {
  return [];
}

/** Empty synthetic pending-attachment projection. */
export function placeholderAttachments(): readonly PendingAttachment[] {
  return [];
}

/** Empty synthetic delivery-fact projection. */
export function placeholderFacts(): Readonly<Record<string, DeliveryFacts>> {
  return {};
}

/** Empty synthetic instruction projection. */
export function placeholderInstructions(): readonly InstructionView[] {
  return [];
}

/** Generic notice with no clinical detail; never interpolates patient data. */
export function placeholderNotice(): GenericNotice {
  return {
    title: 'Appointment update',
    body: 'You have a new update. Open the app for details.',
  };
}

/** Synthetic patient identifier used only to scope device rows in the list. */
export function placeholderPatientId(): string {
  return 'synthetic-patient';
}

/** Synthetic recipient unit shown on the attachment review screen. */
export function placeholderUnit(): CareUnitRef {
  return {
    organizationId: 'synthetic-organization',
    facilityCode: 'synthetic-facility',
    unitName: 'Synthetic care unit',
  };
}

/** Identifier of the synthetic recipient unit. */
export function placeholderUnitId(): string {
  return 'synthetic-unit';
}

/** Publication is not authorized for placeholder attachments. */
export function placeholderPublicationAuthorized(): boolean {
  return false;
}

/** Fixed UTC instant so the instruction screen is deterministic in tests. */
export function placeholderNow(): string {
  return '2026-01-01T00:00:00.000Z';
}

/** Shared no-op used for every presentational screen command. */
export function placeholderAction(): void {
  // A real command handler replaces this in a later task.
}

/** Localized placeholder strings for the four pushed account screens. */
interface PlaceholderStrings {
  /** Enrollment screen labels. */
  readonly enrollment: EnrollmentStrings;
  /** Recovery screen labels. */
  readonly recovery: RecoveryStrings;
  /** Lockout screen labels. */
  readonly lockout: LockoutStrings;
  /** Support request screen labels. */
  readonly support: SupportStrings;
}

/** Placeholder strings with identical keys in both supported languages. */
const STRINGS: Record<Locale, PlaceholderStrings> = {
  en: {
    enrollment: {
      codeLabel: 'Invitation code',
      codeHint: 'Enter the single-use code from your clinic',
      submit: 'Verify code',
      expired: 'This code has expired.',
      reused: 'This code has already been used.',
      locked: 'Access is temporarily locked.',
      invalid: 'That code could not be verified.',
    },
    recovery: {
      codeLabel: 'Recovery code',
      verificationLabel: 'Verification detail',
      submit: 'Recover access',
      expired: 'This code has expired.',
      reused: 'This code has already been used.',
      locked: 'Access is temporarily locked.',
      invalid: 'That code could not be verified.',
    },
    lockout: {
      title: 'Access is locked',
      support: 'Contact support',
      retry: 'Try again',
      timerLabel: 'Time remaining',
    },
    support: {
      title: 'Contact support',
      messageLabel: 'How can we help?',
      submit: 'Send request',
      category: 'Reason for contact',
      invalidCategory: 'Choose a reason.',
      empty: 'Enter a short description.',
      tooLong: 'Description is too long.',
      phi: 'Remove phone numbers or email addresses.',
    },
  },
  ar: {
    enrollment: {
      codeLabel: 'رمز الدعوة',
      codeHint: 'أدخل الرمز لمرة واحدة من عيادتك',
      submit: 'تحقق من الرمز',
      expired: 'انتهت صلاحية هذا الرمز.',
      reused: 'تم استخدام هذا الرمز بالفعل.',
      locked: 'الوصول مقفل مؤقتاً.',
      invalid: 'تعذّر التحقق من هذا الرمز.',
    },
    recovery: {
      codeLabel: 'رمز الاستعادة',
      verificationLabel: 'بيانات التحقق',
      submit: 'استعادة الوصول',
      expired: 'انتهت صلاحية هذا الرمز.',
      reused: 'تم استخدام هذا الرمز بالفعل.',
      locked: 'الوصول مقفل مؤقتاً.',
      invalid: 'تعذّر التحقق من هذا الرمز.',
    },
    lockout: {
      title: 'الوصول مقفل',
      support: 'اتصل بالدعم',
      retry: 'حاول مرة أخرى',
      timerLabel: 'الوقت المتبقي',
    },
    support: {
      title: 'اتصل بالدعم',
      messageLabel: 'كيف يمكننا المساعدة؟',
      submit: 'إرسال الطلب',
      category: 'سبب التواصل',
      invalidCategory: 'اختر سبباً.',
      empty: 'أدخل وصفاً مختصراً.',
      tooLong: 'الوصف طويل جداً.',
      phi: 'احذف أرقام الهواتف أو عناوين البريد الإلكتروني.',
    },
  },
};

/** Placeholder labels for the enrollment screen. */
export function placeholderEnrollmentStrings(locale: Locale): EnrollmentStrings {
  return STRINGS[locale].enrollment;
}

/** Placeholder labels for the recovery screen. */
export function placeholderRecoveryStrings(locale: Locale): RecoveryStrings {
  return STRINGS[locale].recovery;
}

/** Placeholder labels for the lockout screen. */
export function placeholderLockoutStrings(locale: Locale): LockoutStrings {
  return STRINGS[locale].lockout;
}

/** Placeholder labels for the support request screen. */
export function placeholderSupportStrings(locale: Locale): SupportStrings {
  return STRINGS[locale].support;
}

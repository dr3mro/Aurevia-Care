/**
 * Patient screen providers for the navigation shell (PAT-01–03, UX-01).
 *
 * The loaders use the authenticated transport and validate every server payload
 * at this trust boundary. The remaining `placeholder*` values are synthetic and
 * exist only so screens without a wired command can still mount. No token,
 * endpoint secret or real patient payload lives here.
 */
import { loadToken } from '../api/tokenStore';
import { createTransport } from '../api/transport';
import type { FetchImpl, Transport } from '../api/transport';
import { appointmentView } from '../features/appointments/appointments';
import { deriveConfirmation } from '../features/appointments/confirmation';
import type { AppointmentView } from '../features/appointments/appointments';
import type {
  Appointment,
  AppointmentStatus,
  DeviceToken,
  GenericNotice,
  ZoneFormatter,
} from '../features/appointments/types';
import type { CareUnitRef, PendingAttachment } from '../features/attachments/domain';
import type { Conversation, ConversationState } from '../features/conversations/domain';
import type {
  EnrollmentStrings,
  LockoutStrings,
  RecoveryStrings,
  SupportStrings,
} from '../features/enrollment/screens';
import type { DeliveryFacts, InstructionView } from '../features/acknowledgments/types';
import { parseReleasedReport } from '../features/reports/reportClient';
import type { ReleasedReport } from '../features/reports/types';

import type { Locale } from './messages';

/**
 * Placeholder origin for the synthetic build. A real deployment supplies its
 * own HTTPS origin through configuration; no credential or patient data lives
 * in this value.
 */
export const PATIENT_API_BASE_URL = 'https://api.synthetic.example';

/** Appointment statuses the scheduling domain can return to a patient. */
const APPOINTMENT_STATUSES: readonly AppointmentStatus[] = [
  'booked',
  'checked_in',
  'in_progress',
  'completed',
  'cancelled',
  'no_show',
];

/** Conversation lifecycle states the messaging domain can return. */
const CONVERSATION_STATES: readonly ConversationState[] = [
  'awaiting_staff',
  'awaiting_patient',
  'resolved',
  'closed',
];

/** Narrow an untrusted JSON value to a plain object. */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Narrow an untrusted JSON value to a non-empty string. */
function isText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

/** Narrow an untrusted JSON value to a non-negative integer. */
function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

/**
 * Normalize an appointment status from the wire to the domain value.
 *
 * The OpenAPI contract documents lower-case values, but the Java
 * `AppointmentStatus` enum is currently serialized by Jackson as its constant
 * name (`BOOKED`). Accepting either form keeps the patient list working with
 * the deployed server; the API-side mismatch is tracked for the owning story.
 */
function appointmentStatusFromWire(value: unknown): AppointmentStatus {
  if (typeof value !== 'string') {
    throw new Error('MALFORMED_APPOINTMENT');
  }
  const normalized = value.toLowerCase();
  if (!(APPOINTMENT_STATUSES as readonly string[]).includes(normalized)) {
    throw new Error('MALFORMED_APPOINTMENT');
  }
  return normalized as AppointmentStatus;
}

/** Narrow a string to one of the known conversation states. */
function isConversationState(value: unknown): value is ConversationState {
  return typeof value === 'string' && (CONVERSATION_STATES as readonly string[]).includes(value);
}

/**
 * Format an instant in the facility's IANA zone. An unknown zone is a server
 * data error; showing the UTC instant is safer than inventing a local time.
 */
export function facilityZoneFormatter(): ZoneFormatter {
  return {
    format(instant: string, timeZone: string): string {
      try {
        return new Intl.DateTimeFormat('en-GB', {
          timeZone,
          hour: '2-digit',
          minute: '2-digit',
          hour12: false,
        }).format(new Date(instant));
      } catch {
        return instant;
      }
    },
  };
}

/**
 * Create the READY-47 transport for patient screens.
 *
 * The credential is read from OS storage on every request; the caller supplies
 * the HTTPS origin, locale and fetch implementation so tests stay synthetic.
 */
export function createPatientTransport(
  baseUrl: string,
  language: Locale,
  fetchImpl: FetchImpl,
): Transport {
  return createTransport({ baseUrl, getToken: loadToken, fetchImpl, language });
}

/** Validate one patient appointment projection from the server. */
function parseAppointment(value: unknown): Appointment {
  if (!isRecord(value)) {
    throw new Error('MALFORMED_APPOINTMENT');
  }
  const { id, version, patientId, facilityCode, facilityTimeZone, startsAt, status, confirmationEnabled, confirmedAt } = value;
  const normalizedStatus = appointmentStatusFromWire(status);
  if (
    !isText(id)
    || !isCount(version)
    || !isText(patientId)
    || !isText(facilityCode)
    || !isText(facilityTimeZone)
    || !isText(startsAt)
    || typeof confirmationEnabled !== 'boolean'
    || !(confirmedAt === null || isText(confirmedAt))
  ) {
    throw new Error('MALFORMED_APPOINTMENT');
  }
  return {
    id,
    version,
    patientId,
    facilityCode,
    facilityTimeZone,
    startsAt,
    status: normalizedStatus,
    confirmationEnabled,
    confirmedAt,
  };
}

/**
 * Load the verified patient's appointments from their own endpoint.
 *
 * `GET /api/v1/appointments` is a staff route; patient data comes only from
 * `/api/v1/patient/appointments`, scoped by the linked patient identifier.
 */
export async function loadAppointments(
  transport: Transport,
  patientId: string,
  organizationId: string,
): Promise<readonly AppointmentView[]> {
  const body = await transport.get<unknown>(
    `/api/v1/patient/appointments?patientId=${encodeURIComponent(patientId)}`,
  );
  if (!Array.isArray(body)) {
    throw new Error('MALFORMED_APPOINTMENTS');
  }
  const formatter = facilityZoneFormatter();
  return body.map((value) => {
    const appointment = parseAppointment(value);
    const confirmation = deriveConfirmation(appointment, appointment.confirmationEnabled);
    return appointmentView(appointment, organizationId, formatter, confirmation);
  });
}

/** Load the revisions currently released to the authenticated patient. */
export async function loadReports(transport: Transport): Promise<readonly ReleasedReport[]> {
  const body = await transport.get<unknown>('/api/v1/patient/reports');
  if (!Array.isArray(body)) {
    throw new Error('MALFORMED_REPORTS');
  }
  return body.map(parseReleasedReport);
}

/** Patient inbox projection and the server-authored unread counts. */
export interface ConversationInboxData {
  /** Conversations prepared for the existing inbox component. */
  readonly conversations: readonly Conversation[];
  /** Authoritative unread count per conversation identifier. */
  readonly unreadByConversation: Readonly<Record<string, number>>;
}

/** Validate and map one server inbox entry without inventing message content. */
function parseInboxEntry(entry: unknown, organizationId: string): {
  conversation: Conversation;
  unreadCount: number;
} {
  if (!isRecord(entry) || !isRecord(entry.conversation)) {
    throw new Error('MALFORMED_CONVERSATIONS');
  }
  const source = entry.conversation;
  if (
    !isText(source.id)
    || !isText(source.unitKey)
    || !isConversationState(source.state)
    || !isCount(source.patientReadSequence)
    || !isCount(entry.unreadCount)
  ) {
    throw new Error('MALFORMED_CONVERSATIONS');
  }
  return {
    conversation: {
      id: source.id,
      organizationId,
      unitId: source.unitKey,
      unitName: source.unitKey,
      topic: isText(source.topic) ? source.topic : '',
      state: source.state,
      // A closed thread is read-only; connection revocation is checked by the
      // server before a reply is accepted (MSG-02, MSG-07).
      messagingPermitted: source.state !== 'closed',
      messages: [],
      lastReadSequence: source.patientReadSequence,
    },
    unreadCount: entry.unreadCount,
  };
}

/** Load the patient's conversation inbox from its own endpoint. */
export async function loadConversations(
  transport: Transport,
  organizationId: string,
): Promise<ConversationInboxData> {
  const body = await transport.get<unknown>('/api/v1/conversations/inbox');
  if (!isRecord(body) || !Array.isArray(body.entries)) {
    throw new Error('MALFORMED_CONVERSATIONS');
  }
  const conversations: Conversation[] = [];
  const unreadByConversation: Record<string, number> = {};
  for (const entry of body.entries) {
    const parsed = parseInboxEntry(entry, organizationId);
    conversations.push(parsed.conversation);
    unreadByConversation[parsed.conversation.id] = parsed.unreadCount;
  }
  return { conversations, unreadByConversation };
}

/** Empty synthetic device list. */
export function placeholderDevices(): readonly DeviceToken[] {
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

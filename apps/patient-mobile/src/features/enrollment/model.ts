/**
 * Pure helpers for the PAT-03 privacy-safe enrollment, recovery, lockout and
 * support-request flows. Nothing here performs network access or persists a
 * credential: the server is authoritative for expiry, reuse and lockout.
 */

/** Server-side state of an enrollment or recovery submission. */
export type EnrollmentStatus =
  | 'idle'
  | 'submitting'
  | 'success'
  | 'expired'
  | 'reused'
  | 'locked'
  | 'error';

/** Sensitive action that a renewed-authentication prompt will confirm. */
export type StepUpReason = 'unlink_chart' | 'change_contact';

/** Purpose of a support request to a verified care unit. */
export type SupportCategory = 'identity_dispute' | 'accessibility' | 'record_access';

/** Presence-independent rendering direction for an RTL locale (UX-01). */
export type TextAlign = 'left' | 'right';

/** Presence-independent row direction for an RTL locale (UX-01). */
export type RowDirection = 'row' | 'row-reverse';

/** Result of validating a single-use invitation code before submission. */
export interface CodeValidation {
  /** Whether the code has the expected shape. */
  valid: boolean;
  /** Stable reason when invalid. */
  reason?: 'empty' | 'malformed';
}

/** User-facing mapping of a server error code. Never contains the submitted code. */
export interface InvitationFailure {
  /** Localization key safe to render. */
  messageKey: string;
  /** Whether the user may retry the same screen. */
  retryable: boolean;
}

/** Draft of a support request before submission. */
export interface SupportDraft {
  /** Selected purpose, or null when the user has not chosen one. */
  category: SupportCategory | null;
  /** Generic, non-clinical description typed by the patient. */
  message: string;
}

/** Reason a support draft cannot be submitted. */
export type SupportInvalidReason = 'category' | 'empty' | 'too_long' | 'phi';

/** Result of validating a support draft. */
export type SupportValidation =
  | { ok: true }
  | { ok: false; reason: SupportInvalidReason };

/** Normalize a pasted invitation code: trim, uppercase, drop spaces and dashes. */
export function normalizeInvitationCode(raw: string): string {
  return raw.trim().toUpperCase().replace(/[\s-]/g, '');
}

/** Check the local shape of a code; the server still decides expiry and reuse. */
export function validateInvitationCode(code: string): CodeValidation {
  if (code.length === 0) {
    return { valid: false, reason: 'empty' };
  }
  if (!/^[A-Z0-9]{8,16}$/.test(code)) {
    return { valid: false, reason: 'malformed' };
  }
  return { valid: true };
}

/**
 * Map any server error code to one generic failure. The code is deliberately
 * not interpreted: a distinct message per reason would disclose whether a
 * code or account exists and what state it is in (PAT-03, no enumeration).
 * The server keeps the detailed reason for its own audit and support flow.
 */
export function mapInvitationError(_code: string): InvitationFailure {
  return { messageKey: 'enrollment.invalid', retryable: true };
}

/** Format a remaining lockout window as zero-padded MM:SS. */
export function formatLockoutCountdown(totalSeconds: number): string {
  const seconds = Math.max(0, Math.ceil(totalSeconds));
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
}

/** Whether a lockout countdown still blocks access. */
export function isLocked(remainingSeconds: number): boolean {
  return remainingSeconds > 0;
}

/**
 * Whether a status is a terminal failure. Only the fact of failure may reach
 * the screen: the precise reason stays server-side (PAT-03, no enumeration).
 */
export function isFailedStatus(status: EnrollmentStatus): boolean {
  return status !== 'idle' && status !== 'submitting' && status !== 'success';
}

/** The three supported purposes, in stable display order. */
export const SUPPORT_CATEGORIES: readonly SupportCategory[] = [
  'identity_dispute',
  'accessibility',
  'record_access',
];

/** Detect phone-number-like or email-like text and warn without rejecting the person. */
export function containsPotentialPhi(text: string): boolean {
  return /\d{6,}/.test(text) || /[^\s@]+@[^\s@]+\.[^\s@]+/.test(text);
}

/** Validate a support draft before it is sent to the care unit. */
export function validateSupportRequest(draft: SupportDraft): SupportValidation {
  if (draft.category === null) {
    return { ok: false, reason: 'category' };
  }
  if (draft.message.trim().length === 0) {
    return { ok: false, reason: 'empty' };
  }
  if (draft.message.length > 500) {
    return { ok: false, reason: 'too_long' };
  }
  if (containsPotentialPhi(draft.message)) {
    return { ok: false, reason: 'phi' };
  }
  return { ok: true };
}

/** A fixed notice with no patient, chart or message content (PAT-01, PAT-03). */
export const SUBMISSION_NOTICE = 'Your request was received. The care unit will reply in this app.';

/** Return the generic submission notice; it never interpolates draft text. */
export function buildSubmissionNotice(): string {
  return SUBMISSION_NOTICE;
}

/** Text alignment for the active locale direction. */
export function getTextAlign(isRTL: boolean): TextAlign {
  return isRTL ? 'right' : 'left';
}

/** Row direction for the active locale direction. */
export function getRowDirection(isRTL: boolean): RowDirection {
  return isRTL ? 'row-reverse' : 'row';
}
